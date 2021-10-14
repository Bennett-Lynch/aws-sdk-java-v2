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

package software.amazon.awssdk.transfer.s3;

/**
 * The {@link TransferListener} interface may be implemented by your application in order to receive event-driven updates on the
 * progress of a transfer initiated by {@link S3TransferManager}. When you construct an {@link UploadRequest} or {@link
 * DownloadRequest} request to submit to {@link S3TransferManager}, you may provide a variable number of {@link TransferListener}s
 * to be associated with that request. Then, throughout the lifecycle of the request, {@link S3TransferManager} will invoke the
 * provided {@link TransferListener}s when important events occur, like additional bytes being transferred, allowing you to
 * monitor the ongoing progress of the transfer.
 * <p>
 * Each {@link TransferListener} callback is invoked with an immutable {@link Context} object. Depending on the current lifecycle
 * of the request, different {@link Context} objects have different attributes available (indicated by the provided interface).
 * Most notably, every callback is given access to the current {@link TransferProgressSnapshot}, which contains helpful
 * progress-related methods like {@link TransferProgressSnapshot#totalBytesTransferred()} and {@link
 * TransferProgressSnapshot#percentageTransferred()}.
 * <p>
 * There are a few important rules and best practices that govern the usage of {@link TransferListener}s:
 * <ol>
 *     <li>{@link TransferListener} implementations must not block, sleep, or otherwise delay the calling thread.
 *     {@link TransferListener} callbacks are invoked from the SDK's core event loop I/O thread, and any delays may
 *     severely impact performance. If you need to perform blocking operations, you should schedule them in a separate thread
 *     or executor that you control.</li>
 *     <li>Be mindful that {@link #bytesTransferred(Context.BytesTransferred)} may be called extremely often (subject to I/O
 *     buffer sizes). Be careful in implementing expensive operations as a side effect. Consider rate-limiting your side
 *     effect operations, if needed.</li>
 *     <li>{@link TransferListener}s may be invoked by different threads. If your {@link TransferListener} is stateful, 
 *     ensure that it is also thread-safe.</li>
 *     <li>{@link TransferListener}s are not intended to be used for control flow, and therefore your implementation
 *     should not <i>throw</i>. Any thrown exceptions will be suppressed and logged as an error.</li>
 * </ol>
 * <p>
 * A classical use case for {@link TransferListener}s is to create a progress bar to monitor an ongoing transfer's progress.
 * Consider the following as a basic example:
 * <pre>{@code
 * public class ProgressPrintingTransferListener implements TransferListener {
 *     private final ProgressBar progressBar = new ProgressBar(20);
 *
 *     @Override
 *     public void transferInitiated(Context.TransferInitiated context) {
 *         System.out.println("Transfer initiated...");
 *         context.progressSnapshot().ratioTransferred().ifPresent(progressBar::update);
 *     }
 *
 *     @Override
 *     public void bytesTransferred(Context.BytesTransferred context) {
 *         context.progressSnapshot().ratioTransferred().ifPresent(progressBar::update);
 *     }
 *
 *     @Override
 *     public void transferComplete(Context.TransferComplete context) {
 *         context.progressSnapshot().ratioTransferred().ifPresent(progressBar::update);
 *         System.out.println("Transfer complete!");
 *     }
 *
 *     @Override
 *     public void transferFailed(Context.TransferFailed context) {
 *         System.out.println("Transfer failed.");
 *         context.exception().printStackTrace();
 *     }
 *
 *     private static class ProgressBar {
 *         private final int maxTicks;
 *         private final AtomicInteger prevTicks = new AtomicInteger(-1);
 *
 *         public ProgressBar(int maxTicks) {
 *             this.maxTicks = maxTicks;
 *         }
 *
 *         public void update(double ratio) {
 *             int ticks = (int) Math.floor(ratio * maxTicks);
 *             if (prevTicks.getAndSet(ticks) != ticks) {
 *                 System.out.printf("|%s%s| %s%n",
 *                                   "=".repeat(ticks),
 *                                   " ".repeat(maxTicks - ticks),
 *                                   round(ratio * 100, 1) + "%");
 *             }
 *         }
 *
 *         private static double round(double value, int places) {
 *             BigDecimal bd = BigDecimal.valueOf(value);
 *             bd = bd.setScale(places, RoundingMode.HALF_DOWN);
 *             return bd.doubleValue();
 *         }
 *     }
 * }
 * }</pre>
 * Provide the listener as part of your Transfer request, e.g.,
 * <pre>{@code
 * Upload upload = tm.upload(UploadRequest.builder()
 *                                        .putObjectRequest(b -> b.bucket("bucket").key("key"))
 *                                        .source(Paths.get(...))
 *                                        .listeners(new ProgressPrintingTransferListener())
 *                                        .build());
 * }</pre>
 * And then a successful transfer may output something like:
 * <pre>
 * Transfer initiated...
 * |                    | 0.0%
 * |==                  | 12.5%
 * |=====               | 25.0%
 * |=======             | 37.5%
 * |==========          | 50.0%
 * |============        | 62.5%
 * |===============     | 75.0%
 * |=================   | 87.5%
 * |====================| 100.0%
 * Transfer complete!
 * </pre>
 */
public interface TransferListener {

    /**
     * A new transfer has been initiated.
     * <p>
     * Available context attributes:
     * <ol>
     *     <li>{@link Context.TransferInitiated#request()}</li>
     *     <li>{@link Context.TransferInitiated#progressSnapshot()}</li>
     * </ol>
     */
    default void transferInitiated(Context.TransferInitiated context) {
    }

    /**
     * Additional bytes have been submitted or received.
     * <p>
     * Available context attributes:
     * <ol>
     *     <li>{@link Context.BytesTransferred#request()}</li>
     *     <li>{@link Context.BytesTransferred#progressSnapshot()}</li>
     * </ol>
     */
    default void bytesTransferred(Context.BytesTransferred context) {
    }

    /**
     * The transfer has completed successfully.
     * <p>
     * Available context attributes:
     * <ol>
     *     <li>{@link Context.TransferComplete#request()}</li>
     *     <li>{@link Context.TransferComplete#progressSnapshot()}</li>
     *     <li>{@link Context.TransferComplete#completedTransfer()}</li>
     * </ol>
     */
    default void transferComplete(Context.TransferComplete context) {
    }

    /**
     * The transfer failed.
     * <p>
     * Available context attributes:
     * <ol>
     *     <li>{@link Context.TransferFailed#request()}</li>
     *     <li>{@link Context.TransferFailed#progressSnapshot()}</li>
     *     <li>{@link Context.TransferFailed#exception()}</li>
     * </ol>
     */
    default void transferFailed(Context.TransferFailed context) {
    }
}
