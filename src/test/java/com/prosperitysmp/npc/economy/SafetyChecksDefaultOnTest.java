package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The nine safety checks ship on, and each is inert while its parent feature is off:
 * appreciation-ledger-rent-hold without economy.appreciation-enabled, away-debit-recheck with housing.vacation-days 0.
 * (rent-change-lease-guard: GuardedRentStepIT; payout-retirement: PayoutRetirementIT;
 * require-skin-for-roster without residents.cast-file-enabled: ResidentCastIT; item-repairs.require-survival and
 * block-cash-while-items-held without item-repairs.enabled: ItemRepairSettingsTest and ItemRepairRepositoryIT;
 * town.contracts.require-survival without contracts and partnerships: contractSurvivalIsOnlyReadWhenAHandOverStarts;
 * town.commerce.delivery-save-marker without town.commerce-enabled: commerceSaveMarkerIsOnlyReadAfterTheCommerceGate.)
 */
class SafetyChecksDefaultOnTest {
    private static final List<String> SAFETY = List.of(UtilityPaymentRuntime.APPRECIATION_LEDGER_RENT_HOLD,
            UtilityPaymentRuntime.AWAY_DEBIT_RECHECK, PropertyService.RENT_CHANGE_LEASE_GUARD, UtilityPaymentRuntime.PAYOUT_RETIREMENT,
            com.prosperitysmp.npc.market.HousingRuntime.REQUIRE_SKIN_FOR_ROSTER,
            ItemRepairSettings.REQUIRE_SURVIVAL, ItemRepairSettings.BLOCK_CASH, "town.contracts.require-survival",
            com.prosperitysmp.npc.commerce.CommerceWiring.SAVE_MARKER);

    @Test void shippedOnWithTheirParentFeaturesOff() throws Exception {
        String yaml;
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.loadFromString(yaml);
        for (String key : SAFETY) {
            assertEquals(Boolean.TRUE, config.get(key), key);
            // The comment block right above the key says what it is.
            String leaf = key.substring(key.lastIndexOf('.') + 1) + ": true";
            // Two keys share the leaf name (require-survival), so find it inside its own section.
            int section = 0;
            String[] parts = key.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                section = yaml.indexOf("\n" + "  ".repeat(i) + parts[i] + ":", section);
                assertTrue(section >= 0, key + " section");
            }
            int at = yaml.indexOf(leaf, section);
            assertTrue(at > 0, key);
            int block = yaml.lastIndexOf("# Safety check, on by default.", at);
            assertTrue(block > 0 && yaml.substring(block, at).lines().allMatch(l -> l.isBlank() || l.strip().startsWith("#")),
                    key + " comment");
        }
        // Parents still ship off, so a fresh server gets no behavior change from the safety checks.
        assertEquals(Boolean.FALSE, config.get(UtilityPaymentRuntime.APPRECIATION));
        assertEquals(0, config.getInt(OwnerAway.CONFIG));
        assertEquals(0, config.getInt(PropertyService.RENT_CHANGE_DELAY));
        assertEquals(Boolean.FALSE, config.get(UtilityPaymentRuntime.PAYMENTS_OPT_IN));
        assertEquals(Boolean.FALSE, config.get("residents.cast-file-enabled"));
        assertEquals(Boolean.FALSE, config.get(ItemRepairSettings.ENABLED));
        assertEquals(Boolean.FALSE, config.get("town.contracts.enabled"));
        assertEquals(Boolean.FALSE, config.get("town.partnerships.enabled"));
        assertEquals(Boolean.FALSE, config.get("town.commerce-enabled"));
    }

    // ---- town.commerce.delivery-save-marker: read once per delivery, only after the commerce gate let it through

    @Test void commerceSaveMarkerIsOnlyReadAfterTheCommerceGate() throws Exception {
        String read = "CommerceWiring.SAVE_MARKER";
        // Exactly one read outside CommerceWiring, in ItemDeliveryRuntime.saveMarkerOn().
        var found = new java.util.TreeMap<String, Integer>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("CommerceWiring.java")) continue;
                int count = Files.readString(file).split(java.util.regex.Pattern.quote(read), -1).length - 1;
                if (count > 0) found.put(file.getFileName().toString(), count);
            }
        }
        assertEquals(java.util.Map.of("ItemDeliveryRuntime.java", 1), found);
        // Nor is the key read by its literal name, whole or in part. The one literal in code is saveMarkerOn's warning.
        String warning = "\"town.commerce.delivery-save-marker is not true or false; the check stays on.\"";
        var literals = new java.util.TreeMap<String, Integer>();
        int warnings = 0;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("CommerceWiring.java")) continue;
                String code = ItemRepairWiringTest.code(Files.readString(file));
                warnings += code.split(java.util.regex.Pattern.quote(warning), -1).length - 1;
                int count = code.replace(warning, "").split(java.util.regex.Pattern.quote("delivery-save-marker"), -1).length - 1;
                if (count > 0) literals.put(file.getFileName().toString(), count);
            }
        }
        assertEquals(java.util.Map.of(), literals, "the key read by its literal name");
        assertEquals(1, warnings, "the scan sees the warning's literal");
        String runtime = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/commerce/ItemDeliveryRuntime.java"))
                .replace("\r\n", "\n");
        int at = runtime.indexOf(read);
        assertTrue(at >= 0, "the read exists");
        int method = runtime.indexOf("private boolean saveMarkerOn() {");
        int next = runtime.indexOf("\n    }\n", method);
        assertTrue(method > 0 && at > method && at < next, "the read sits inside saveMarkerOn()");
        // saveMarkerOn is used once, from deliver, after the gate refused nothing: a closed commerce gate answers
        // UNAUTHORIZED first, so the key is never read with commerce off.
        String use = "marked(custody.source(), this::saveMarkerOn)";
        assertEquals(2, runtime.split(java.util.regex.Pattern.quote("saveMarkerOn()"), -1).length - 1,
                "declared once, and read once by the startup report");
        assertEquals(1, runtime.split(java.util.regex.Pattern.quote("this::saveMarkerOn"), -1).length - 1, "used once");
        int deliver = runtime.indexOf("public void deliver(");
        int gate = runtime.indexOf("if (!authorized(operation)) { reply.accept(Result.UNAUTHORIZED); return; }", deliver);
        int call = runtime.indexOf(use, deliver);
        int end = runtime.indexOf("private void preflight(", deliver);
        assertTrue(deliver > 0 && gate > deliver && call > gate && call < end, "the only use is in deliver, after the gate");
        // The startup report reads it only for an order whose commerce gate is open.
        int report = runtime.indexOf("private void reportHeldOrders(");
        String body = runtime.substring(report, runtime.indexOf("\n    }\n", report));
        assertTrue(report > 0 && body.contains("if (!gates.test(order.source()) || !saveMarkerOn()) continue;"),
                "the startup report checks the commerce gate first");
        // Apart from that report, the constructor and ready() never mention it.
        String startup = runtime.substring(runtime.indexOf("public ItemDeliveryRuntime(ProsperityNpcPlugin plugin, Database database,"),
                runtime.indexOf("private static java.util.function.Predicate<ItemCustody.Source> sameGate("));
        assertTrue(startup.contains(body), "the report sits between the constructor and ready()");
        startup = startup.replace(body, "");
        assertFalse(startup.contains("saveMarkerOn") || startup.contains(read));
    }

    // ---- town.contracts.require-survival: read only when a hand-over starts, which needs a contract flag

    @Test void contractSurvivalIsOnlyReadWhenAHandOverStarts() throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/town/ContractRuntime.java"));
        String controller = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/interaction/ResidentEncounterController.java"));
        // The one read sits in the hand-over refusal check.
        int read = runtime.indexOf("catalogue.requireSurvival()");
        assertTrue(read > runtime.indexOf("private String refusal(Player player, BooleanSupplier authority)"));
        assertEquals(read, runtime.lastIndexOf("catalogue.requireSurvival()"));
        // A hand-over is dispatched only after the flag check for its kind.
        int gate = controller.indexOf("if (!contractActionAllowed(selected.kind())");
        int dispatch = controller.indexOf("contractHandover(player, ticket, token, selected); return;");
        assertTrue(gate > 0 && dispatch > gate);
        assertTrue(controller.contains("case CONTRACT_ACCEPT, HANDOVER, CONTRACT_CANCEL, CONTRACT_DIRECTIONS -> plugin.contractsOn();"));
        assertTrue(controller.contains("case PARTNER, PARTNER_CONFIRM, PARTNER_END, PARTNER_END_CONFIRM -> plugin.partnershipsOn();"));
    }

    // ---- appreciation-ledger-rent-hold: never consulted unless appreciation is on

    @Test void ledgerRentHoldIsNeverReachedWithAppreciationOff() {
        var rent = CompletableFuture.completedFuture(4);
        AtomicInteger counted = new AtomicInteger(99);
        var out = UtilityPaymentRuntime.thenAppreciation(rent, false, () -> { throw new AssertionError("rent-deferred read"); },
                () -> { throw new AssertionError("appreciation phase started"); }, counted::set, Runnable::run);
        assertSame(rent, out, "the rent sequence is returned unchanged");
        assertEquals(99, counted.get(), "no appreciation count recorded");
        // With appreciation on, the phase does start, so the checks above are real.
        AtomicInteger started = new AtomicInteger();
        UtilityPaymentRuntime.thenAppreciation(rent, true, () -> false,
                () -> { started.incrementAndGet(); return CompletableFuture.completedFuture(0); }, counted::set, Runnable::run).join();
        assertEquals(1, started.get());
        assertEquals(0, counted.get());
    }

    @Test void ledgerRentHoldIsOnlyReadAfterTheAppreciationSwitch() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        int phase = code.indexOf("private CompletableFuture<Integer> payAppreciation(");
        int off = code.indexOf("if (!stillOn.getAsBoolean()) return CompletableFuture.completedFuture(-1);", phase);
        assertTrue(phase > 0 && off > phase);
        String read = "plugin.getConfig().get(APPRECIATION_LEDGER_RENT_HOLD)";
        int reads = 0;
        for (int at = code.indexOf(read); at >= 0; at = code.indexOf(read, at + 1)) {
            reads++;
            assertTrue(at > off, "every ledger-hold read is after the appreciation off-switch returned");
        }
        assertEquals(2, reads);
        // The only caller of the phase is behind the batch's appreciation flag.
        assertEquals(2, code.split("payAppreciation\\(", -1).length - 1, "definition plus one call");
        assertTrue(code.contains("return thenAppreciation(sequence, appreciate, () -> appreciationHeld(rentDeferred[0], dueChangeHeld[0]),\n"
                + "                        () -> payAppreciation(properties, sha, limits, day, pinned, authorized),"));
        assertTrue(code.contains("boolean appreciate = appreciationEnabled(plugin.getConfig().get(APPRECIATION));"));
    }

    @Test void theBatchCopiesItsDueChangeSkipCountIntoTheAppreciationGate() throws Exception {
        // requestRentBatch is Bukkit-bound, so its wiring is pinned by source order: the count declared for the batch,
        // filled from this batch's own reservation step, then read by the appreciation gate. Without the copy the gate
        // would always see 0 and a rent change applied mid-batch would let appreciation take that rent's headroom.
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        String copy = "dueChangeHeld[0] = step.heldForDueChange();";
        int declared = code.indexOf("int[] dueChangeHeld = {0};");
        int filled = code.indexOf(copy, declared);
        int gate = code.indexOf("appreciationHeld(rentDeferred[0], dueChangeHeld[0])", filled);
        assertTrue(declared > 0, "batch declares the skip count");
        assertTrue(filled > declared, "batch copies the reservation step's skip count");
        assertTrue(gate > filled, "the appreciation gate reads the copied count");
        assertEquals(1, code.split(java.util.regex.Pattern.quote(copy), -1).length - 1, "copied exactly once");
    }

    // ---- away-debit-recheck: vacation-days 0 means no away decision exists, so the recheck never holds

    @Test void awayRecheckIsInertWithVacationDaysZero() {
        AtomicInteger sightings = new AtomicInteger();
        var away = new OwnerAway(() -> 0, () -> LocalDate.of(2026, 10, 13), () -> 2_000_000_000_000L,
                owner -> { sightings.incrementAndGet(); return new OwnerAway.Seen(false, 1, 1); });
        away.requireDurable();
        var home = new Property(UUID.randomUUID(), UUID.randomUUID(), "r", "m", "small", 3, 1000, 1000,
                UUID.randomUUID(), 0, 80, true);
        assertFalse(away.enabled());
        assertTrue(away.settled());
        assertEquals(Optional.of(Set.of()), away.held(List.of(home)));
        for (Object flag : new Object[] {Boolean.TRUE, Boolean.FALSE, null}) {
            assertFalse(UtilityPaymentRuntime.awayHeldNow(flag, () -> away.holds(home)), String.valueOf(flag));
            for (var leg : TransferProof.Leg.values())
                assertDoesNotThrow(() -> UtilityPaymentRuntime.requireAwayDebitGate(leg,
                        () -> UtilityPaymentRuntime.awayHeldNow(flag, () -> away.holds(home))));
        }
        assertFalse(UtilityPaymentRuntime.awayBlocks(away.holds(home), true, 0, false));
        assertEquals(0, sightings.get(), "no owner sighting read and no decision made");

        // With vacation-days above 0 and a long-gone owner, the same recheck holds the debit.
        var on = new OwnerAway(() -> 3, () -> LocalDate.of(2026, 10, 13), () -> 2_000_000_000_000L,
                owner -> { sightings.incrementAndGet(); return new OwnerAway.Seen(false, 1, 1); });
        assertTrue(UtilityPaymentRuntime.awayHeldNow(Boolean.TRUE, () -> on.holds(home)));
        assertFalse(UtilityPaymentRuntime.awayHeldNow(Boolean.FALSE, () -> on.holds(home)));
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireAwayDebitGate(TransferProof.Leg.DEBIT,
                () -> UtilityPaymentRuntime.awayHeldNow(Boolean.TRUE, () -> on.holds(home))));
        assertTrue(sightings.get() > 0);
    }
}
