/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.transfer.s3.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkTestInternalApi;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.services.s3.internal.resource.S3AccessPointResource;
import software.amazon.awssdk.services.s3.internal.resource.S3ArnConverter;
import software.amazon.awssdk.services.s3.internal.resource.S3Resource;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.transfer.s3.CompletedDownload;
import software.amazon.awssdk.transfer.s3.CompletedUpload;
import software.amazon.awssdk.transfer.s3.Download;
import software.amazon.awssdk.transfer.s3.DownloadRequest;
import software.amazon.awssdk.transfer.s3.S3ClientConfiguration;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.TransferProgressSnapshot;
import software.amazon.awssdk.transfer.s3.S3TransferManagerOverrideConfiguration;
import software.amazon.awssdk.transfer.s3.Upload;
import software.amazon.awssdk.transfer.s3.UploadDirectoryRequest;
import software.amazon.awssdk.transfer.s3.UploadDirectoryTransfer;
import software.amazon.awssdk.transfer.s3.UploadRequest;
import software.amazon.awssdk.transfer.s3.internal.NotifyingAsyncRequestBody.AsyncRequestBodyListener;
import software.amazon.awssdk.transfer.s3.internal.NotifyingAsyncResponseTransformer.AsyncResponseTransformerListener;
import software.amazon.awssdk.utils.CompletableFutureUtils;
import software.amazon.awssdk.utils.Validate;

@SdkInternalApi
public final class DefaultS3TransferManager implements S3TransferManager {
    private final S3CrtAsyncClient s3CrtAsyncClient;
    private final TransferManagerConfiguration transferConfiguration;
    private final UploadDirectoryHelper uploadDirectoryManager;

    public DefaultS3TransferManager(DefaultBuilder tmBuilder) {
        transferConfiguration = resolveTransferManagerConfiguration(tmBuilder);
        s3CrtAsyncClient = initializeS3CrtClient(tmBuilder);
        uploadDirectoryManager = new UploadDirectoryHelper(transferConfiguration, this::upload);
    }

    @SdkTestInternalApi
    DefaultS3TransferManager(S3CrtAsyncClient s3CrtAsyncClient,
                             UploadDirectoryHelper uploadDirectoryManager,
                             TransferManagerConfiguration configuration) {
        this.s3CrtAsyncClient = s3CrtAsyncClient;
        this.transferConfiguration = configuration;
        this.uploadDirectoryManager = uploadDirectoryManager;
    }

    private TransferManagerConfiguration resolveTransferManagerConfiguration(DefaultBuilder tmBuilder) {
        TransferManagerConfiguration.Builder transferConfigBuilder = TransferManagerConfiguration.builder();
        tmBuilder.transferManagerConfiguration.uploadDirectoryConfiguration()
                                              .ifPresent(transferConfigBuilder::uploadDirectoryConfiguration);
        tmBuilder.transferManagerConfiguration.executor().ifPresent(transferConfigBuilder::executor);
        return transferConfigBuilder.build();
    }

    private S3CrtAsyncClient initializeS3CrtClient(DefaultBuilder tmBuilder) {
        S3CrtAsyncClient.S3CrtAsyncClientBuilder clientBuilder = S3CrtAsyncClient.builder();
        tmBuilder.s3ClientConfiguration.credentialsProvider().ifPresent(clientBuilder::credentialsProvider);
        tmBuilder.s3ClientConfiguration.maxConcurrency().ifPresent(clientBuilder::maxConcurrency);
        tmBuilder.s3ClientConfiguration.minimumPartSizeInBytes().ifPresent(clientBuilder::minimumPartSizeInBytes);
        tmBuilder.s3ClientConfiguration.region().ifPresent(clientBuilder::region);
        tmBuilder.s3ClientConfiguration.targetThroughputInGbps().ifPresent(clientBuilder::targetThroughputInGbps);
        ClientAsyncConfiguration clientAsyncConfiguration =
            ClientAsyncConfiguration.builder()
                                    .advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR,
                                                    transferConfiguration.option(TransferConfigurationOption.EXECUTOR))
                                    .build();
        clientBuilder.asyncConfiguration(clientAsyncConfiguration);

        return clientBuilder.build();
    }

    @Override
    public Upload upload(UploadRequest uploadRequest) {
        try {
            Validate.paramNotNull(uploadRequest, "uploadRequest");
            assertNotUnsupportedArn(uploadRequest.putObjectRequest().bucket(), "upload");

            PutObjectRequest putObjectRequest = uploadRequest.putObjectRequest();
            AsyncRequestBody requestBody = requestBodyFor(uploadRequest);
        
        TransferProgressSnapshot progressSnapshot = createInitialUploadSnapshot(requestBody);
        DefaultTransferProgress progress = new DefaultTransferProgress(progressSnapshot);
        TransferListenerContext listenerContext = TransferListenerContext.builder()
                                                                         .request(uploadRequest)
                                                                         .progressSnapshot(progressSnapshot)
                                                                         .build();
        TransferListenerInvoker listenerInvoker = new TransferListenerInvoker(uploadRequest.listeners());
        listenerInvoker.transferInitiated(listenerContext);

            CompletableFuture<PutObjectResponse> putObjFuture = s3CrtAsyncClient.putObject(putObjectRequest, requestBody);

            CompletableFuture<CompletedUpload> future = putObjFuture.thenApply(r -> CompletedUpload.builder()
                                                                                                   .response(r)
                                                                                                   .build());
            return new DefaultUpload(CompletableFutureUtils.forwardExceptionTo(future, putObjFuture));
        } catch (Throwable throwable) {
            return new DefaultUpload(CompletableFutureUtils.failedFuture(throwable));
        }
    }

    @Override
    public UploadDirectoryTransfer uploadDirectory(UploadDirectoryRequest uploadDirectoryRequest) {
        try {
            Validate.paramNotNull(uploadDirectoryRequest, "uploadDirectoryRequest");
            assertNotUnsupportedArn(uploadDirectoryRequest.bucket(), "uploadDirectory");

            return uploadDirectoryManager.uploadDirectory(uploadDirectoryRequest);
        } catch (Throwable throwable) {
            return UploadDirectoryTransfer.builder().completionFuture(CompletableFutureUtils.failedFuture(throwable)).build();
        }
    }

    @Override
    public Download download(DownloadRequest downloadRequest) {
        try {
            Validate.paramNotNull(downloadRequest, "downloadRequest");
            assertNotUnsupportedArn(downloadRequest.getObjectRequest().bucket(), "download");

            CompletableFuture<GetObjectResponse> getObjectFuture =
                s3CrtAsyncClient.getObject(downloadRequest.getObjectRequest(),
                                           AsyncResponseTransformer.toFile(downloadRequest.destination()));
            CompletableFuture<CompletedDownload> future =
                getObjectFuture.thenApply(r -> CompletedDownload.builder().response(r).build());

            getObjectFuture.whenComplete((r, t) -> {
            if (t != null) {
                listenerInvoker.transferFailed(TransferListenerFailedContext.builder()
                                                                            .transferContext(listenerContext.copy(b -> {
                                                                                       b.progressSnapshot(progress.snapshot());
                                                                                   }))
                                                                            .exception(t)
                                                                            .build());
            }
        });

        return new DefaultDownload(CompletableFutureUtils.forwardExceptionTo(future, getObjectFuture), progress);
        } catch (Throwable throwable) {
            return new DefaultDownload(CompletableFutureUtils.failedFuture(throwable));
        }
    }

    @Override
    public void close() {
        s3CrtAsyncClient.close();
        transferConfiguration.close();
    }

    public static Builder builder() {
        return new DefaultBuilder();
    }

    private static void assertNotUnsupportedArn(String bucket, String operation) {
        if (!bucket.startsWith("arn:")) {
            return;
        }

        if (isObjectLambdaArn(bucket)) {
            String error = String.format("%s does not support S3 Object Lambda resources", operation);
            throw new IllegalArgumentException(error);
        }

        Arn arn = Arn.fromString(bucket);

        if (isMrapArn(arn)) {
            String error = String.format("%s does not support S3 multi-region access point ARN", operation);
            throw new IllegalArgumentException(error);
        }
    }

    private static boolean isObjectLambdaArn(String arn) {
        return arn.contains(":s3-object-lambda");
    }

    private static boolean isMrapArn(Arn arn) {
        S3Resource s3Resource = S3ArnConverter.create().convertArn(arn);

        S3AccessPointResource s3EndpointResource =
            Validate.isInstanceOf(S3AccessPointResource.class, s3Resource,
                                  "An ARN was passed as a bucket parameter to an S3 operation, however it does not "
                                  + "appear to be a valid S3 access point ARN.");

        return !s3EndpointResource.region().isPresent();
    }

    private AsyncRequestBody requestBodyFor(UploadRequest uploadRequest) {
        return AsyncRequestBody.fromFile(uploadRequest.source());
    }

    private TransferProgressSnapshot createInitialUploadSnapshot(AsyncRequestBody requestBody) {
        TransferProgressSnapshot.Builder snapshotBuilder = TransferProgressSnapshot.builder();
        try {
            requestBody.contentLength().ifPresent(snapshotBuilder::totalTransferSize);
        } catch (Exception ignored) {
            // Any potential exception is ignored here to defer failure
            // to the s3CrtAsyncClient call and its associated future
        }
        return snapshotBuilder.build();
    }

    private AsyncRequestBody wrapRequestBody(AsyncRequestBody requestBody, DefaultTransferProgress progress,
                                             TransferListenerContext listenerContext,
                                             TransferListenerInvoker listenerInvoker) {
        return new NotifyingAsyncRequestBody(
            requestBody,
            new AsyncRequestBodyListener() {
                @Override
                public void onNext(ByteBuffer byteBuffer) {
                    TransferProgressSnapshot snapshot = progress.updateAndGet(b -> {
                        b.totalBytesTransferred(b.totalBytesTransferred() + byteBuffer.limit());
                    });
                    listenerInvoker.bytesTransferred(listenerContext.copy(b -> b.progressSnapshot(snapshot)));
                }
            });
    }

    private AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> wrapResponseTransformer(
        AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> responseTransformer, DefaultTransferProgress progress,
        TransferListenerContext listenerContext, TransferListenerInvoker listenerInvoker) {
        return new NotifyingAsyncResponseTransformer<>(
            responseTransformer,
            new AsyncResponseTransformerListener<GetObjectResponse, GetObjectResponse>() {
                @Override
                public void onResponse(GetObjectResponse response) {
                    if (response.contentLength() != null) {
                        progress.updateAndGet(b -> b.totalTransferSize(response.contentLength()));
                    }
                }

                @Override
                public void onNext(ByteBuffer byteBuffer) {
                    TransferProgressSnapshot snapshot = progress.updateAndGet(b -> {
                        b.totalBytesTransferred(b.totalBytesTransferred() + byteBuffer.limit());
                    });
                    listenerInvoker.bytesTransferred(listenerContext.copy(b -> b.progressSnapshot(snapshot)));
                }
            });
    }

    private static class DefaultBuilder implements S3TransferManager.Builder {
        private S3ClientConfiguration s3ClientConfiguration = S3ClientConfiguration.builder().build();
        private S3TransferManagerOverrideConfiguration transferManagerConfiguration =
            S3TransferManagerOverrideConfiguration.builder().build();

        private DefaultBuilder() {
        }

        @Override
        public Builder s3ClientConfiguration(S3ClientConfiguration configuration) {
            this.s3ClientConfiguration = configuration;
            return this;
        }

        @Override
        public Builder transferConfiguration(S3TransferManagerOverrideConfiguration transferManagerConfiguration) {
            this.transferManagerConfiguration = transferManagerConfiguration;
            return this;
        }

        @Override
        public S3TransferManager build() {
            return new DefaultS3TransferManager(this);
        }
    }
}
