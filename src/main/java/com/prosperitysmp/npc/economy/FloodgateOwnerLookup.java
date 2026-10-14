package com.prosperitysmp.npc.economy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;

/**
 * linked-accounts.lookup-floodgate-owners. Once per economy day, asks Floodgate's PlayerLink about home owners
 * whose UUID is a Floodgate ID, so a player who linked and now plays only on Java still has their
 * Bedrock-era homes in the group. With global linking each ask is an HTTPS call to api.geysermc.org; with
 * Floodgate's own local linking it reads Floodgate's database. Bounded: at most the daily limit, each owner
 * once a day, a timeout on each. Anything that fails saves nothing, which keeps the old, looser-for-nobody
 * behavior. start is main thread only. PlayerLink is never touched while the sub-flag is off.
 */
public final class FloodgateOwnerLookup {
    /** Saves one checked pair (FLOODGATE_LOOKUP). Runs on the database executor. */
    @FunctionalInterface public interface Saver { void save(UUID java, UUID bedrock) throws Exception; }

    private final FloodgateApi api;
    private final Duration timeout;
    private final Executor database;
    private final Consumer<String> info;
    // Main thread only: the economy day the set belongs to, and every owner asked on it.
    private LocalDate day;
    private final Set<UUID> asked = new HashSet<>();

    public FloodgateOwnerLookup(FloodgateApi api, Duration timeout, Executor database, Consumer<String> info) {
        this.api = Objects.requireNonNull(api);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Lookup timeout");
        this.database = Objects.requireNonNull(database);
        this.info = Objects.requireNonNull(info);
    }

    /** The plugin's entry point, so the plugin class itself never names a Floodgate type. */
    public static FloodgateOwnerLookup create(Duration timeout, Executor database, Consumer<String> info) {
        return new FloodgateOwnerLookup(FloodgateApi.getInstance(), timeout, database, info);
    }

    /**
     * Starts today's bounded pass and returns how many owners it asked about (0 when off). The pass logs one
     * INFO line when every ask has finished. Owners already asked today are skipped, and the day's total never
     * passes dailyLimit, however often the daily cycle calls this.
     */
    public int start(boolean subFlagOn, LocalDate today, Collection<UUID> owners, int dailyLimit, Saver saver) {
        Objects.requireNonNull(today); Objects.requireNonNull(owners); Objects.requireNonNull(saver);
        if (!subFlagOn || dailyLimit <= 0) return 0;
        if (!today.equals(day)) { day = today; asked.clear(); }
        int room = dailyLimit - asked.size();
        if (room <= 0) return 0;
        var wanted = new ArrayList<UUID>();
        for (UUID owner : new LinkedHashSet<>(owners)) {
            if (wanted.size() >= room) break;
            if (owner == null || asked.contains(owner) || !api.isFloodgateId(owner)) continue;
            wanted.add(owner);
        }
        if (wanted.isEmpty()) return 0;
        PlayerLink link = api.getPlayerLink();
        if (link == null || !link.isEnabled()) return 0;
        asked.addAll(wanted);
        var saved = new AtomicInteger(); var failed = new AtomicInteger();
        List<CompletableFuture<Void>> asks = new ArrayList<>();
        for (UUID bedrock : wanted) {
            CompletableFuture<LinkedPlayer> answer;
            try { answer = link.getLinkedPlayer(bedrock); }
            catch (RuntimeException | LinkageError refused) { answer = CompletableFuture.failedFuture(refused); }
            if (answer == null) answer = CompletableFuture.completedFuture(null);
            asks.add(answer.toCompletableFuture().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((lp, failure) -> {
                        if (failure != null) { failed.incrementAndGet(); return CompletableFuture.<Void>completedFuture(null); }
                        if (lp == null) return CompletableFuture.<Void>completedFuture(null);
                        UUID java = lp.getJavaUniqueId();
                        if (!bedrock.equals(lp.getBedrockId()) || java == null || api.isFloodgateId(java)) {
                            failed.incrementAndGet(); return CompletableFuture.<Void>completedFuture(null);
                        }
                        try {
                            return CompletableFuture.runAsync(() -> {
                                try { saver.save(java, bedrock); saved.incrementAndGet(); }
                                catch (Exception | LinkageError notSaved) { failed.incrementAndGet(); }
                            }, database).exceptionally(rejected -> { failed.incrementAndGet(); return null; });
                        } catch (RejectedExecutionException busy) {
                            // A full worker refuses the save right away, inside runAsync. Nothing is saved; count it.
                            failed.incrementAndGet(); return CompletableFuture.<Void>completedFuture(null);
                        }
                    }).thenCompose(next -> next));
        }
        int count = wanted.size();
        CompletableFuture.allOf(asks.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                info.accept("Linked accounts lookup: asked " + count + ", saved " + saved.get() + ", failed " + failed.get() + "."));
        return count;
    }
}
