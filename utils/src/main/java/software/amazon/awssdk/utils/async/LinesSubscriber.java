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

package software.amazon.awssdk.utils.async;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.annotations.SdkProtectedApi;
import software.amazon.awssdk.utils.StringUtils;

@SdkProtectedApi
public class LinesSubscriber extends DelegatingSubscriber<String, List<String>> {

    private String buf;
    private int index;
    private Subscription subscription;
    String newline = System.lineSeparator();

    public LinesSubscriber(Subscriber<? super List<String>> subscriber) {
        super(subscriber);
        buf = "";
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        super.onSubscribe(subscription);
    }

    @Override
    public void onNext(String s) {
        buf += s;
        String[] lines = buf.split(newline);
        // if buf ends with newline, we have nothing left to buffer
        if (buf.endsWith(newline)) {
            List<String> list = Arrays.asList(lines);
            subscriber.onNext(list);
            return;
        }
        // if size is 1, we didn't find any newlines, maintain buffer
        if (lines.length == 1) {
            subscription.request(1);
            return;
        }
        // delim found, but continue to buffer the last part
        List<String> list = new ArrayList<>(Arrays.asList(lines));
        buf = list.remove(list.size() - 1);
        subscriber.onNext(list);
    }

    @Override
    public void onComplete() {
        // Deliver any remaining items before calling on complete
        if (!StringUtils.isEmpty(buf)) {
            subscriber.onNext(Collections.singletonList(buf));
        }
        super.onComplete();
    }

    // Optional<Integer> endOfLine = indexOfLineSeparator(index);
    // while (endOfLine.isPresent()) {
    //     String line = buf.substring(index, endOfLine.get());
    //     index = skipLineSeparator(endOfLine.get());
    //     endOfLine = indexOfLineSeparator(index);
    // }
    // index = buf.length() - 1;

    // private Optional<Integer> indexOfLineSeparator(int start) {
    //     for (int current = start; current < buf.length(); current++) {
    //         char ch = buf.charAt(current);
    //         if (ch == '\n' || ch == '\r') {
    //             return Optional.of(current);
    //         }
    //     }
    //     return Optional.empty();
    // }
    //
    // private int skipLineSeparator(int start) {
    //     if (start < fence) {
    //         if (getChar(value, start) == '\r') {
    //             int next = start + 1;
    //             if (next < fence && getChar(value, next) == '\n') {
    //                 return next + 1;
    //             }
    //         }
    //         return start + 1;
    //     }
    //     return fence;
    // }
}
