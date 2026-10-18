package com.prosperitysmp.npc.property.guard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves ARM is unchanged at every readiness site: each still contains its original fence null test, and the
 * guard check sits beside it. For ARM the guard is ready exactly when the fence is set, so both halves are true
 * together. The payout predicate for ARM's write hook is covered by PropertyGuardTest.
 */
class GuardReadinessSourceTest {
    private static final String GUARD = "plugin.propertyGuard().ready()";
    private static final Path J = Path.of("src/main/java/com/prosperitysmp/npc");

    private static String read(String file) throws Exception {
        return Files.readString(J.resolve(file));
    }

    private static void site(String source, String original, String file) {
        int at = source.indexOf(original);
        assertTrue(at >= 0, file + " lost its original check: " + original);
        int guard = source.indexOf(GUARD, at);
        assertTrue(guard > at && guard - at < original.length() + 120, file + ": the guard check is not beside " + original);
    }

    @Test void everyRuntimeGateKeepsItsFenceTestAndAddsTheGuard() throws Exception {
        String repair = read("economy/ItemRepairRuntime.java");
        site(repair, "plugin.propertyService() != null && plugin.ownershipFence() != null", "ItemRepairRuntime");
        String utility = read("economy/UtilityPaymentRuntime.java");
        site(utility, "return !closed && startupReady && plugin.isEnabled() && plugin.ownershipFence() != null", "UtilityPaymentRuntime");
        String housing = read("market/HousingRuntime.java");
        site(housing, "return !closed && plugin.isEnabled() && plugin.ownershipFence() != null", "HousingRuntime ready");
        site(housing, "if (closed || busy || !plugin.isEnabled() || plugin.ownershipFence() == null", "HousingRuntime incidents");
        String property = read("property/PropertyService.java");
        site(property, "if (fence == null", "PropertyService enroll");
        site(property, "cache.size() >= 512 || plugin.ownershipFence() == null", "PropertyService resume");
        site(property, "|| plugin.ownershipFence() == null || !plugin.propertyGuard", "PropertyService set rent");
        site(property, "p.isEmpty() || plugin.ownershipFence() == null", "PropertyService unenroll");
        assertEquals(4, count(property, GUARD), "four PropertyService gates");
        // The due-rent step keeps its fence-only switch: with a fence it always takes the leased path.
        assertTrue(property.contains("if (fence != null) return applyDueRentChangesLeased(fence, today, done);"));
    }

    @Test void payoutsAskThePredicateBeforeAnythingIsReserved() throws Exception {
        String utility = read("economy/UtilityPaymentRuntime.java");
        int batch = utility.indexOf("public boolean requestRentBatch(");
        int predicate = utility.indexOf("plugin.propertyGuard().payoutsAllowed()", batch);
        int lease = utility.indexOf("financialFence().acquire(\"property-rent\")", batch);
        assertTrue(batch > 0 && predicate > batch && predicate < lease, "rent batch checks payouts before its lease");
        int appreciation = utility.indexOf("private CompletableFuture<Integer> payAppreciation(");
        int check = utility.indexOf("plugin.propertyGuard().payoutsAllowed()", appreciation);
        int acquire = utility.indexOf("plugin.ownershipFence().acquire(owner)", appreciation);
        assertTrue(appreciation > 0 && check > appreciation && check < acquire, "appreciation checks before any lease");
    }

    @Test void constructionAndShutdownKeepOnlyTheFenceTest() throws Exception {
        String plugin = read("ProsperityNpcPlugin.java");
        assertTrue(plugin.contains("if (ownershipFence != null && database != null && database.isConnected())\n            housingRuntime = new HousingRuntime(this);"));
        assertTrue(plugin.contains("if (ownershipFence != null && propertyService != null) {\n            try {\n                utilityPayments = new UtilityPaymentRuntime(this);"));
        assertTrue(plugin.contains("if (ownershipFence != null) ownershipFence.stop();"));
        // ARM: the guard becomes the write hook at the same moment the fence is set.
        int set = plugin.indexOf("propertyGuard = PropertyGuard.writeHook();");
        assertTrue(set > 0 && plugin.indexOf("ownershipFence = fence;", set) - set < 80);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
