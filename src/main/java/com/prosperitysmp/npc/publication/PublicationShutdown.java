package com.prosperitysmp.npc.publication;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One terminal source-release handoff per plugin instance, including an already-completed drain. */
public final class PublicationShutdown {
    private PublicationShutdown() { }

    public static CompletionStage<Void> releaseAfter(CompletionStage<Void> drained, Runnable release) {
        Objects.requireNonNull(drained);
        Objects.requireNonNull(release);
        var result = new CompletableFuture<Void>();
        drained.whenComplete((ignored, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                Thread.ofPlatform().daemon(true).name("prosperity-npc-db-close").start(() -> {
                    try {
                        release.run();
                        result.complete(null);
                    }
                    catch (Throwable error) {
                        result.completeExceptionally(error);
                        if (error instanceof Error fatal) throw fatal;
                    }
                });
            } catch (Throwable startFailure) {
                result.completeExceptionally(startFailure);
                if (startFailure instanceof Error fatal) throw fatal;
            }
        });
        return result.minimalCompletionStage();
    }
}
