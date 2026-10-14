package net.alex9849.arm;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import net.alex9849.arm.regions.Region;
import org.bukkit.Bukkit;

/** Prosperity compatibility boundary; no database or wallet operations. */
public final class NpcTransactionGuard {
    public static final int PROTOCOL = 1;
    private static BiFunction<UUID, String, Runnable> writer;
    private NpcTransactionGuard() { }

    public static void install(BiFunction<UUID, String, Runnable> implementation) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (writer != null) throw new IllegalStateException("Ownership hook replacement requires restart");
        writer = Objects.requireNonNull(implementation);
    }

    public static Runnable begin(Region region) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        // ARM loads before NPC. NPC must verify registration before accepting transactions.
        if (writer == null) return () -> { };
        return Objects.requireNonNull(writer.apply(region.getRegionworld().getUID(), region.getRegion().getId()));
    }
}
