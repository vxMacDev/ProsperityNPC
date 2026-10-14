package com.prosperitysmp.npc.publication;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only one subscription obtains bytes. Does not prove a client cannot replay previously buffered bytes. */
final class SingleUseRequestBody implements HttpRequest.BodyPublisher {
    private final HttpRequest.BodyPublisher delegate;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    SingleUseRequestBody(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length == 0 || bytes.length > 8192) throw new IllegalArgumentException("Invalid Chronicle request size");
        delegate = HttpRequest.BodyPublishers.ofByteArray(bytes.clone());
    }
    @Override public long contentLength() { return delegate.contentLength(); }
    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber);
        if (subscribed.compareAndSet(false, true)) {
            delegate.subscribe(subscriber);
            return;
        }
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { }
        });
        subscriber.onError(new IOException("Chronicle request body replay refused"));
    }
}
