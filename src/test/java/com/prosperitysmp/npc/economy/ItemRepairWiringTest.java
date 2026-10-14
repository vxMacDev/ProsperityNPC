package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.commerce.ItemDeliveryEvidence;
import com.prosperitysmp.npc.economy.ItemRepairRepository.Decision;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Source checks that keep item repairs off every money path and keep commerce wiring as it was. */
class ItemRepairWiringTest {
    private static final List<String> MONEY = List.of("Vault", "net.milkbowl", "Economy ", "TransferJournal", "JournaledTransfer",
            "CapAllocator", "EconomyEvent", "PayoutGate", "AccountingProjection", "RentReservation");
    /** Matched without case: money types, and every plugin accessor that reaches a money path. The VAULT block is not money. */
    private static final List<String> MONEY_ANY_CASE = List.of("vaultaccess", "milkbowl", "transferjournal", "journaledtransfer",
            "capallocator", "economyevent", "payoutgate", "accountingprojection", "rentreservation", "economyledger",
            "depositplayer", "withdrawplayer", "utilitypayments(", "autocare(", "financialfence(", "purchaseservice(",
            "purchaserepository", "bridgeapi(", "economylimits(", "commercepayments", "paymentruntime(");
    private static final List<String> ITEM_REPAIR_FILES = List.of("economy/ItemRepairRuntime.java", "economy/ItemRepairRepository.java",
            "economy/ItemRepairRecoveryTool.java", "economy/ItemRepairRecipes.java", "economy/ItemRepairQuote.java",
            "economy/ItemRepairSettings.java", "economy/ItemRepairCopy.java", "commerce/ItemCollectionEvidence.java",
            "commerce/InventoryCustodyGate.java", "commerce/ItemInventoryCustody.java");

    static List<String> moneyMentions(String code) {
        String lower = code.toLowerCase(java.util.Locale.ROOT);
        var out = new java.util.ArrayList<String>();
        for (String money : MONEY) if (code.contains(money)) out.add(money);
        for (String money : MONEY_ANY_CASE) if (lower.contains(money)) out.add(money);
        return out;
    }

    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/" + file)).replace("\r\n", "\n");
    }

    /** Code only: comments may name what the code never touches. */
    static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    @Test void itemRepairCodeHasNoMoneyDependency() throws Exception {
        for (String file : ITEM_REPAIR_FILES) assertEquals(List.of(), moneyMentions(code(source(file))), file + " reaches money");
        // The same scan does find money in the cash repair path and in plugin calls of any case, so the empty results above are real.
        assertTrue(moneyMentions(code(source("economy/UtilityPaymentRuntime.java"))).contains("TransferJournal"));
        assertEquals(List.of("utilitypayments("), moneyMentions("var p = plugin.utilityPayments();"));
        assertEquals(List.of("vaultaccess"), moneyMentions("plugin.vaultAccess()"));
        assertEquals(List.of(), moneyMentions(code("/** Vault */ int x; // Vault")), "comments are skipped");
    }

    /** Every whole-word mention in code: a call, a method reference or a reflective string all count. */
    static int mentions(String code, String name) {
        var matcher = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(code);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static java.util.Map<String, Integer> mentionsInMain(String name) throws Exception {
        var found = new java.util.TreeMap<String, Integer>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int count = mentions(code(Files.readString(file)), name);
                if (count > 0) found.put(file.getFileName().toString(), count);
            }
        }
        return found;
    }

    /** Every whitespace run (spaces, tabs, line breaks) as one space, so a pin does not depend on indentation or wrapping. */
    static String squash(String text) { return text.replaceAll("\\s+", " ").strip(); }

    /** The code between two markers, which must both be present in order. */
    private static String between(String code, String from, String to) {
        int start = code.indexOf(from), end = code.indexOf(to, start + 1);
        assertTrue(start >= 0 && end > start, from + " .. " + to);
        return code.substring(start, end);
    }

    /**
     * reserveReturn and reconcileHeldReturn must stay public because their caller is in another package (Java has no
     * narrower access across packages). They create or settle an item grant, so only ItemRepairRepository may use them:
     * reserveReturn in the settle and in the offline return resolution, reconcileHeldReturn only in the latter.
     */
    @Test void onlyItemRepairsCreateOrSettleReturns() throws Exception {
        // The scan sees a method reference and a reflective name, but not a longer identifier.
        assertEquals(1, mentions("Function<?, ?> f = ItemCustodyRepository::reserveReturn;", "reserveReturn"));
        assertEquals(1, mentions("type.getMethod(\"reserveReturn\", Connection.class)", "reserveReturn"));
        assertEquals(0, mentions("reserveReturnLater();", "reserveReturn"));

        String custody = code(source("commerce/ItemCustodyRepository.java"));
        String repairs = code(source("economy/ItemRepairRepository.java"));
        // The custody repository only declares them and names them in its refusals; it never calls them itself.
        String custodyRest = custody.replace("public static Receipt reserveReturn(Connection c, ItemCustody request)", "")
                .replace("\"Repair returns use reserveReturn\"", "").replace("\"Only repair returns use reserveReturn\"", "")
                .replace("public static Reconciled reconcileHeldReturn(Connection c,", "")
                .replace("\"Only repair returns use reconcileHeldReturn\"", "");
        assertEquals(0, mentions(custodyRest, "reserveReturn"), "the custody repository never creates a return itself");
        assertEquals(0, mentions(custodyRest, "reconcileHeldReturn"));
        assertEquals(java.util.Map.of("ItemCustodyRepository.java", 3, "ItemRepairRepository.java", 2), mentionsInMain("reserveReturn"));
        assertEquals(java.util.Map.of("ItemCustodyRepository.java", 2, "ItemRepairRepository.java", 1), mentionsInMain("reconcileHeldReturn"));
        // Where the item repair uses sit: the proven take, and the staff-proven non-delivery.
        assertEquals(1, mentions(between(repairs, "private Settlement collected(", "private Settlement settlement("), "reserveReturn"));
        String resolve = between(repairs, "public ReturnResolution resolveReturn(", "static UUID reissueNonce(");
        assertEquals(1, mentions(resolve, "reserveReturn"));
        assertEquals(1, mentions(resolve, "reconcileHeldReturn"));
    }

    @Test void commerceStatusGateAndDrainGoThroughTheTestedWiring() throws Exception {
        // The behaviour is tested in CommerceWiringTest; these pin that the plugin calls it with the right inputs.
        String plugin = code(source("ProsperityNpcPlugin.java"));
        String flat = squash(plugin);
        assertTrue(flat.contains(squash("""
                        return com.prosperitysmp.npc.commerce.CommerceWiring.commerceStatus(commercePayments != null, commerce != null,
                                itemDelivery != null, () -> commercePayments.status(), () -> itemDelivery.ready());
                """)));
        assertTrue(flat.contains(squash("""
                        com.prosperitysmp.npc.commerce.CommerceWiring.deliveryGate(() -> killSwitches.isSystemEnabled(),
                                () -> killSwitches.isEconomyEnabled(), () -> getConfig().getBoolean("town.commerce-enabled", false),
                                () -> database != null && database.isConnected()), inventoryGate);
                """)));
        assertTrue(flat.contains(squash("""
                        for (var part : com.prosperitysmp.npc.commerce.CommerceWiring.closeOrder(commerce != null, commercePayments != null,
                                itemRepairs != null, itemDelivery != null))
                            stopComponent(() -> drains.add(switch (part) {
                                case COMMERCE -> commerce.closeAsync().toCompletableFuture();
                                case COMMERCE_PAYMENTS -> commercePayments.closeAsync().toCompletableFuture();
                                case ITEM_REPAIRS -> itemRepairs.closeAsync().toCompletableFuture();
                                case ITEM_DELIVERY -> itemDelivery.closeAsync().toCompletableFuture();
                            }), drains);
                """)));
        // No other close of these four, and the block sits after housing and before the price book.
        String disable = between(plugin, "public void onDisable()", "public void trackDatabaseWorker(");
        for (String runtime : List.of("commerce", "commercePayments", "itemRepairs", "itemDelivery"))
            assertEquals(1, disable.split(java.util.regex.Pattern.quote(runtime + ".closeAsync()"), -1).length - 1, runtime);
        assertTrue(disable.indexOf("housingRuntime::close") < disable.indexOf("CommerceWiring.closeOrder(")
                && disable.indexOf("CommerceWiring.closeOrder(") < disable.indexOf("commercePrices::invalidate"));
        // Commerce still gets the one delivery runtime, built at the same point in initCommerce.
        String commerce = between(plugin, "private void initCommerce()", "public com.prosperitysmp.npc.commerce.ItemDeliveryRuntime itemCustody()");
        assertTrue(commerce.indexOf("commercePayments = new") < commerce.indexOf("itemDelivery = itemCustody();")
                && commerce.indexOf("itemDelivery = itemCustody();") < commerce.indexOf("commerce = new"));
    }

    @Test void returnResolutionNeedsRecordedCountsAndAMatchingFile() {
        var delivered = new ItemDeliveryEvidence(java.util.UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "c".repeat(64), 10, 74, 0, true);
        var nothing = new ItemDeliveryEvidence(java.util.UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "b".repeat(64), 10, 10, 64, true);
        var partial = new ItemDeliveryEvidence(java.util.UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "c".repeat(64), 10, 50, 24, true);
        var delivery = ItemCustodyRepository.Decision.VERIFIED_DELIVERED;
        var none = ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED;
        assertNull(ItemRepairRecoveryTool.returnDecisionProblem(delivery, 74, 64, delivered), "matching counts are clear");
        assertNull(ItemRepairRecoveryTool.returnDecisionProblem(none, 10, 64, nothing), "matching counts are clear");
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(delivery, 73, 64, delivered), "file off by one");
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(delivery, 74, 63, delivered), "not the whole stack");
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(delivery, 50, 64, partial), "a partial delivery is never clear");
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(delivery, 10, 64, nothing));
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(none, 11, 64, nothing), "file off by one");
        // Added in memory but missing from the file: a lost save and items moved away look the same, so never clear.
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(none, 10, 64, delivered), "saying not delivered here could give the items twice");
        assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(none, 10, 64, partial));
        for (var decision : ItemCustodyRepository.Decision.values())
            assertNotNull(ItemRepairRecoveryTool.returnDecisionProblem(decision, 10, 64, null), "no recorded counts: never clear");
    }

    private static final java.util.UUID PLAYER = new java.util.UUID(5, 5), HAND_IN = new java.util.UUID(6, 6);

    /** Take counts as the plugin records them: start, end, and a take that returned normally and was saved. */
    private static ItemCollectionEvidence took(List<Integer> before, List<Integer> after) {
        return new ItemCollectionEvidence(PLAYER, HAND_IN, "a".repeat(64), "b".repeat(64), before, after, true, true);
    }

    private static String handIn(Decision decision, List<Integer> saved, ItemCollectionEvidence recorded) {
        return ItemRepairRecoveryTool.decisionProblem(decision, saved, List.of(20, 8), List.of(16, 8), recorded);
    }

    @Test void handInCollectedNeedsRecordedTakeAndAMatchingFile() {
        var whole = took(List.of(20, 8), List.of(4, 0));
        assertNull(handIn(Decision.VERIFIED_COLLECTED, List.of(4, 0), whole), "matching counts are clear");
        // The take was recorded but the save was not proven: the stopped server's file can still settle it.
        var unproven = new ItemCollectionEvidence(PLAYER, HAND_IN, "a".repeat(64), "b".repeat(64), List.of(20, 8), List.of(4, 0), true, false);
        assertNull(handIn(Decision.VERIFIED_COLLECTED, List.of(4, 0), unproven), "a save that was not proven");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(4, 1), whole), "file off by one");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(20, 8), whole), "the file still holds everything: a lost save");
        // The file agrees with the recorded counts in these three; only the recipe check can refuse them.
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(5, 0), took(List.of(20, 8), List.of(5, 0))), "one item short of the recipe");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(4, 8), took(List.of(20, 8), List.of(4, 8))), "one line not taken");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(3, 0), took(List.of(20, 8), List.of(3, 0))), "more than the recipe");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, List.of(5, 0), took(List.of(21, 8), List.of(5, 0))),
                "recorded counts that start somewhere else are not this hand-in's take");
    }

    @Test void itemsMovedIntoAChestNeverLookLikeATake() {
        // The player put the recipe in a chest. The file shows before minus quantity on every line, exactly like a take.
        var moved = List.of(4, 0);
        assertNull(handIn(Decision.VERIFIED_COLLECTED, moved, took(List.of(20, 8), List.of(4, 0))), "a real take");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, moved, null), "no recorded take: the file alone is never enough");
        assertNotNull(handIn(Decision.VERIFIED_COLLECTED, moved, took(List.of(20, 8), List.of(20, 8))),
                "the plugin recorded nothing taken: the items went somewhere else");
        assertTrue(handIn(Decision.VERIFIED_COLLECTED, moved, null).contains("chest"), "the refusal says why");
    }

    @Test void handInNotCollectedNeedsRecordedNothingTakenAndTheBeforeCount() {
        var nothing = took(List.of(20, 8), List.of(20, 8));
        assertNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(20, 8), nothing), "matching counts are clear");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(20, 7), nothing), "file off by one");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(4, 0), nothing), "items gone from the file");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(20, 8), took(List.of(20, 8), List.of(4, 0))),
                "the plugin recorded the take: saying not collected loses the items");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(20, 8), took(List.of(20, 8), List.of(20, 0))), "a partial take");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(21, 8), took(List.of(21, 8), List.of(21, 8))),
                "recorded counts that start somewhere else");
        assertNotNull(handIn(Decision.VERIFIED_NOT_COLLECTED, List.of(20, 8), null), "no recorded counts: never clear");
    }

    @Test void handInRefusesAFileThatDoesNotListEveryLine() {
        var whole = took(List.of(20, 8), List.of(4, 0));
        for (var decision : Decision.values()) {
            assertNotNull(handIn(decision, List.of(4), whole));
            assertNotNull(ItemRepairRecoveryTool.decisionProblem(decision, List.of(4, 0), List.of(20, 8), List.of(16), whole));
        }
    }

    @Test void recoveryToolChecksDataVersionAndRecordedCountsOnBothPaths() throws Exception {
        String tool = squash(code(source("economy/ItemRepairRecoveryTool.java")));
        assertTrue(tool.contains(squash("""
                var recorded = repository.takeEvidence(collection).orElse(null);""")), "the hand-in path reads the recorded take");
        assertTrue(tool.contains(squash("""
                String problem = handInProblem(read, decision, quote.beforeUnits(), quote.quantities(), recorded);""")));
        assertTrue(tool.contains(squash("""
                String problem = returnProblem(read, decision, request.quantity(), recorded);""")));
        // Both start with the file that cannot be proven, then the DataVersion, then the counts.
        assertTrue(tool.contains(squash("""
                return problems(UNPROVEN_FILE, ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()),
                        decisionProblem(decision, read.units(), before, quantities, recorded));""")));
        assertTrue(tool.contains(squash("""
                return problems(UNPROVEN_FILE, ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()),
                        returnDecisionProblem(decision, read.units().getFirst(), quantity, recorded));""")));
        assertEquals(1, mentions(tool, "handInProblem") - 1, "one call besides the declaration");
        assertEquals(1, mentions(tool, "returnProblem") - 1, "one call besides the declaration");
        assertEquals(2, mentions(tool, "readSaved"), "both paths read through the DataVersion-aware reader");
        assertEquals(java.util.Map.of(), mentionsInMain("readUnits"), "no reader skips the DataVersion");
        String runtime = squash(code(source("economy/ItemRepairRuntime.java")));
        assertTrue(runtime.contains("for (UUID reference : heldReferences(held)) alertHeld(reference);"), "startup alerts every held reference");
    }

    /** Positions of each marker in order; fails when one is missing or out of order. */
    private static void inOrder(String code, String... markers) {
        int at = -1;
        for (String marker : markers) {
            int next = code.indexOf(marker, at + 1);
            assertTrue(next > at, "missing or out of order: " + marker);
            at = next;
        }
    }

    /**
     * Both hand-ins save before the take and read that save back before writing their intent, refuse a take
     * when the inventory is not exactly as saved, and prove the take's save against that read. The proof itself is
     * tested in ItemSaveProofTest; captureCollection refuses a missing read, so no runtime can skip it.
     */
    @Test void bothHandInsProveTheTakeAgainstASaveMadeJustBefore() throws Exception {
        String repairs = squash(code(source("economy/ItemRepairRuntime.java")));
        inOrder(repairs, "next.preState = ItemInventoryCustody.saveBeforeTake(player, next.collectionId);",
                "var baseline = saveProof.baseline(playerDataWorld, f.player.getUniqueId(), f.collectionId,",
                "if (baseline.isEmpty()) { post(() -> abandonBeforeTake(f, ItemRepairCopy.UNAVAILABLE)); return; }",
                "begun = repository.begin(quote, runId, at, days.dayAt(at));", "f.baseline = baseline.get();",
                "!ItemInventoryCustody.inventoryState(f.player).equals(f.preState)", "refusal = ItemRepairCopy.ITEMS_CHANGED;",
                "if (refusal != null) { cancel(f, refusal); return; }", "evidence = ItemInventoryCustody.collect(f.player, f.collectionId, f.takes);",
                "new ItemSaveProof.Expectation(line.prototype(), line.beforeUnits() - line.quantity(), line.quantity())",
                "saveProof.captureCollection(playerDataWorld, f.player.getUniqueId(), expectations, f.baseline);");
        String contracts = squash(code(source("town/ContractRuntime.java")));
        inOrder(contracts, "f.preState = ItemInventoryCustody.saveBeforeTake(player, f.runId);",
                "var baseline = saveProof.baseline(playerDataWorld, playerId, f.runId, List.of(f.prototype));",
                "post(() -> finish(f, Outcome.refused(com.prosperitysmp.npc.interaction.ContractPresentation.UNAVAILABLE)));",
                "result = begin.run(f);", "f.baseline = baseline.get(); began(f, result);",
                "!ItemInventoryCustody.inventoryState(f.player).equals(f.preState)",
                "refusal = com.prosperitysmp.npc.interaction.ContractPresentation.ITEMS_CHANGED;",
                "contracts.cancelBeforeTake(f.contractId, f.player.getUniqueId(), f.runId);",
                "evidence = ItemInventoryCustody.collect(f.player, f.runId,",
                "f.prototype, Math.max(0, f.expectedBefore - f.quantity), f.quantity)), f.baseline);");
        // The take marks its own save; the save before it carries the other marker.
        String custody = squash(code(source("commerce/ItemInventoryCustody.java")));
        inOrder(custody, "inventory.setItem(slot,", "mark(player, ItemSaveProof.marker(collectionId, true)); player.saveData(); normal = true;");
        assertTrue(custody.contains("mark(player, ItemSaveProof.marker(collectionId, false)); player.saveData(); return inventoryHash(player);"));
        assertEquals(4, mentions(custody, "saveData"), "the take's save, the save before it, the delivery's save and the save before a return");
    }

    /**
     * A REPAIR_RETURN delivery is proven like a take: a save just before it with the return's before marker, read back
     * before the delivery starts; the after marker set in the delivery's tick before its own save; then the bound proof.
     * Unmarked commerce deliveries skip all of it and keep the plain capture. The proof itself is tested in
     * ItemSaveProofTest; capture() refuses a return, so a return can never fall back to the plain proof. A return is always
     * marked: marked() never reads the commerce key for it (ItemDeliveryRuntimeHelpersTest).
     */
    @Test void aReturnDeliveryIsProvenAgainstASaveMadeJustBefore() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        inOrder(delivery, "boolean returned = custody.source() == ItemCustody.Source.REPAIR_RETURN;",
                "boolean marked = marked(custody.source(), this::saveMarkerOn);",
                "if (marked && !savedBefore(operation)) return;",
                "if (marked) {", "var read = saveProof.deliveryBaseline(playerDataWorld, custody);",
                "if (read.isEmpty()) { post(() -> { saveCheckRefused(custody); finish(operation, Result.NOT_READY); }); return; }",
                "var start = repository.beginDelivery(custody, runId, Instant.now());",
                "post(() -> dispatch(operation, start, before));",
                "private boolean savedBefore(Pending operation) {",
                "try { ItemInventoryCustody.saveBeforeDelivery(operation.login(), operation.custody()); return true; }",
                "catch (RuntimeException | LinkageError unsaved) { saveCheckRefused(operation.custody()); finish(operation, Result.NOT_READY); return false; }",
                "private void dispatch(",
                "if (baseline != null) ItemInventoryCustody.markDelivered(operation.login(), operation.custody());",
                "evidence = ItemInventoryCustody.deliver(operation.login(), operation.custody());",
                "var persisted = baseline == null ? saveProof.capture(playerDataWorld, operation.custody(), evidence)",
                ": saveProof.captureDelivery(playerDataWorld, operation.custody(), evidence, baseline);",
                "if (persisted.isEmpty()) { ambiguous(operation, baseline); return; }");
        String custody = squash(code(source("commerce/ItemInventoryCustody.java")));
        assertTrue(custody.contains("mark(player, ItemSaveProof.markerKeyFor(custody.source()), ItemSaveProof.marker(custody.requestNonce(), false)); player.saveData();"));
        assertTrue(custody.contains("mark(player, ItemSaveProof.markerKeyFor(custody.source()), ItemSaveProof.marker(custody.requestNonce(), true));"));
        // One call site each: the before-save inside savedBefore, the after marker in dispatch under baseline != null.
        assertEquals(1, mentions(delivery, "saveBeforeDelivery"));
        assertEquals(1, mentions(delivery, "markDelivered"));
        assertEquals(java.util.Map.of("ItemDeliveryRuntime.java", 1, "ItemInventoryCustody.java", 1), mentionsInMain("saveBeforeDelivery"));
        assertEquals(java.util.Map.of("ItemDeliveryRuntime.java", 1, "ItemInventoryCustody.java", 1), mentionsInMain("markDelivered"));
        // savedBefore: its declaration, the marked return path and the marked order path (afterPreflight).
        assertEquals(3, mentions(delivery, "savedBefore"));
        assertTrue(between(delivery, "private void afterPreflight(", "static Result preflightReply(").contains("if (!savedBefore(operation)) return;"));
    }

    /**
     * A return whose stored items cannot be read is held in the database before anyone hears "held": the reply is HELD
     * only when the row really is AMBIGUOUS. Commerce keeps its plain check and reply, with nothing written.
     */
    @Test void anUnreadableReturnIsHeldInTheDatabaseBeforeTheReplySaysHeld() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        String deliver = between(delivery, "public void deliver(", "private void dispatch(");
        inOrder(deliver, "boolean returned = custody.source() == ItemCustody.Source.REPAIR_RETURN;", "if (!returned) {",
                "try { if (!ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(custody))) { finish(operation, Result.NO_SPACE); return; } }"
                        + " catch (RuntimeException | LinkageError invalid) { finish(operation, Result.HELD); return; }",
                "} else {", "try { item = ItemInventoryCustody.decode(custody); }",
                "catch (RuntimeException | LinkageError unreadable) { holdUnreadable(operation); return; }",
                "if (!ItemInventoryCustody.canReceive(player, item)) { finish(operation, Result.NO_SPACE); return; }",
                "catch (RuntimeException | LinkageError unclear) { finish(operation, Result.NOT_READY); return; }",
                "if (marked && !savedBefore(operation)) return;");
        assertEquals(1, mentions(deliver, "holdUnreadable"), "only the unreadable return is held this way");
        String checks = deliver.substring(0, deliver.indexOf("submit("));
        assertEquals(1, checks.split(java.util.regex.Pattern.quote("Result.HELD"), -1).length - 1,
                "before any SQL, the only HELD reply is the commerce one; an unreadable return hears HELD only through holdUnreadable");
        // The worker step failing: before beginDelivery a return held nothing (NOT_READY); after it, the row may be a started
        // intent, so it is read back and held where staff see it. Commerce keeps HELD.
        String worker = between(deliver, "submit(", "static boolean marked(");
        inOrder(worker, "var read = saveProof.deliveryBaseline(playerDataWorld, custody);",
                "startTried.set(true); var start = repository.beginDelivery(custody, runId, Instant.now());",
                "}, () -> { if (!marked) finish(operation, Result.HELD); else if (startTried.get()) unclearStart(operation);"
                        + " else finish(operation, Result.NOT_READY); });");
        assertEquals(1, worker.split(java.util.regex.Pattern.quote("Result.HELD"), -1).length - 1);
        String unclear = between(deliver, "private void unclearStart(", "static boolean nothingStarted(");
        // A return has no baseline here, so heldReport does nothing for it.
        inOrder(unclear, "private void unclearStart(Pending operation) { unclearStart(operation, null); }",
                "private void unclearStart(Pending operation, ItemSaveProof.Baseline baseline) {",
                "boolean waiting = nothingStarted(repository.find(custody.playerId(), custody.requestNonce(), Instant.now()));",
                "if (!waiting) repository.markAmbiguous(custody, runId, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);",
                "post(() -> { if (!waiting) heldReport(operation, baseline); finish(operation, waiting ? Result.NOT_READY : Result.HELD); });",
                "}, () -> { heldReport(operation, baseline); finish(operation, Result.HELD); });");
        String hold = between(delivery, "private void holdUnreadable(", "static Result unreadableReply(");
        inOrder(hold, "var receipt = repository.holdUnreadableReturn(custody, runId);",
                "post(() -> finish(operation, unreadableReply(receipt.state())));", "finish(operation, Result.NOT_READY);");
        assertEquals(java.util.Map.of("ItemCustodyRepository.java", 1, "ItemDeliveryRuntime.java", 1), mentionsInMain("holdUnreadableReturn"));
        // The reply itself is tested in commerce.UnreadableReturnReplyTest.
    }

    /**
     * A return's caller is always told when the delivery finishes, even if the player left. Otherwise the claim stays
     * set (HOME_BUSY until a restart) and a held return raises no staff alert. Nothing is said in chat unless the
     * player is here.
     */
    @Test void aReturnCallerIsToldAfterThePlayerLeavesAndSaysNothingToThem() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        String finish = between(delivery, "private void finish(", "static boolean tellsCaller(");
        inOrder(finish, "if (!pending.remove(operation.login().getUniqueId(), operation)) return;",
                "inventoryGate.release(operation.login().getUniqueId(), operation);",
                "if (!tellsCaller(left, operation.custody().source())) return;", "operation.reply().accept(result);");
        String repair = squash(code(source("economy/ItemRepairRuntime.java")));
        String callback = between(repair, "deliveries.deliver(player, custody, () -> present(player), result -> {", "private static String itemName(");
        int lines = callback.split(java.util.regex.Pattern.quote("reply.accept("), -1).length - 1;
        assertEquals(5, lines, "one chat line per result");
        assertEquals(lines, callback.split(java.util.regex.Pattern.quote("if (present(player)) reply.accept("), -1).length - 1,
                "every chat line checks the player is here");
        for (String clears : new String[] {"case NO_SPACE -> { claiming.remove(", "case HELD -> { claiming.remove(",
                "case BUSY -> { claiming.remove(", "default -> { claiming.remove("})
            assertTrue(callback.contains(clears), clears);
        inOrder(callback, "case DELIVERED -> {", "deliverNext(player, waiting, index + 1, reply);");
        inOrder(callback, "case HELD -> {", "alertHeld(custody.requestNonce());",
                "if (present(player)) reply.accept(ItemRepairCopy.held(custody.requestNonce()));");
        assertTrue(between(repair, "private void deliverNext(", "deliveries.deliver(").contains(
                "if (closed || deliveries == null || !present(player)) { claiming.remove(player.getUniqueId()); return; }"),
                "the next stack after a player left clears the claim");
    }

    /** Every start reports each held paid order, including one cut off by a stop or crash, and a failed read only skips it. */
    @Test void aHeldOrderIsReportedAtEveryStart() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        String start = between(delivery, "java.util.function.Predicate<ItemCustody.Source> gates, InventoryCustodyGate inventoryGate) {",
                "public boolean ready()");
        inOrder(start, "while (repository.recoverOtherRuns(runId, 100) != 0) { if (closed) return; }",
                "post(() -> ready = true);",
                "try { held = repository.heldOrders(HELD_REPORT_LIMIT); total = repository.heldOrderCount(); }",
                "catch (java.sql.SQLException | RuntimeException unreadable) { held = java.util.List.of(); total = 0; }",
                "post(() -> reportHeldOrders(report, all));", "}, () -> ready = false);");
        String report = between(delivery, "private void reportHeldOrders(", "public boolean ready()");
        inOrder(report, "for (var order : held) {", "if (!gates.test(order.source()) || !saveMarkerOn()) continue;",
                "DeliverySaveCheckWatch.orderHeldAtStartLine(order.requestNonce(), order.playerId(), order.outcome())",
                "com.prosperitysmp.npc.alert.StaffAlerts.Kind.COMMERCE_DELIVERY_HELD",
                "if (reported > 0 && total > held.size()) plugin.getLogger().warning(DeliverySaveCheckWatch.orderHeldMoreLine(total - held.size(), total));");
        String more = com.prosperitysmp.npc.commerce.DeliverySaveCheckWatch.orderHeldMoreLine(3, 67);
        assertTrue(more.startsWith("3 more held order deliveries") && more.contains("67 held in all") && !more.contains("\u2014"), more);
        String line = com.prosperitysmp.npc.commerce.DeliverySaveCheckWatch.orderHeldAtStartLine(new java.util.UUID(1, 2),
                PLAYER, "INTERRUPTED_RUN");
        assertTrue(line.contains("INTERRUPTED_RUN") && line.contains(PLAYER.toString()) && !line.contains("\u2014"), line);
    }

    /** The saved file holds the ridden vehicle and its items too, so the 'nothing changed' hash covers them. */
    @Test void theInventoryHashCoversTheRiddenVehicle() throws Exception {
        String custody = squash(code(source("commerce/ItemInventoryCustody.java")));
        String hash = between(custody, "private static String inventoryHash(", "private static void update(");
        inOrder(hash, "player.getEnderChest()}) update(hash, inventory);", "org.bukkit.entity.Entity root = player.getVehicle();",
                "while (root != null && root.getVehicle() != null) root = root.getVehicle();",
                "if (root == null) hash.update((byte) 0);", "if (entity instanceof Player) continue;",
                "hash.update(entity.getUniqueId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));",
                "if (entity instanceof org.bukkit.inventory.InventoryHolder holder) update(hash, holder.getInventory());",
                "if (entity instanceof org.bukkit.entity.LivingEntity living && living.getEquipment() != null) update(hash, equipment(living.getEquipment()));",
                "next.addAll(entity.getPassengers());");
        String slots = between(custody, "private static ItemStack[] equipment(", "private static void update(MessageDigest hash, ItemStack[] items)");
        inOrder(slots, "org.bukkit.inventory.EquipmentSlot.values()", "ItemStack item = equipment.getItem(slots[i]);",
                "catch (IllegalArgumentException unsupported) { items[i] = null; }");
    }

    /**
     * A refused save before a marked delivery is said once per delivery per run, then raised to staff after repeated
     * refusals. Returns keep their own watch, lines and alert kind; orders use orderSaveChecks and COMMERCE_SAVE_CHECK.
     */
    @Test void aRefusedSaveCheckBeforeAReturnIsLoggedAndRaised() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        assertEquals(4, mentions(delivery, "saveCheckRefused"),
                "the declaration, the refused save (savedBefore), and the unreadable baseline of a return and of an order");
        String refused = between(delivery, "private void saveCheckRefused(", "private boolean authorized(");
        inOrder(refused, "if (custody.source() == ItemCustody.Source.REPAIR_RETURN) {",
                "switch (saveChecks.refused(custody.requestNonce()))",
                "case LOG -> plugin.getLogger().warning(DeliverySaveCheckWatch.logLine(custody.requestNonce(), custody.playerId()));",
                "case ALERT -> plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.ITEM_REPAIR_HELD,",
                "DeliverySaveCheckWatch.staffAlert(custody.requestNonce()));", "return; }",
                "switch (orderSaveChecks.refused(custody.requestNonce()))",
                "case LOG -> plugin.getLogger().warning(DeliverySaveCheckWatch.orderLogLine(custody.requestNonce(), custody.playerId()));",
                "case ALERT -> plugin.alerts().raise(com.prosperitysmp.npc.alert.StaffAlerts.Kind.COMMERCE_SAVE_CHECK,",
                "\"order-save-\" + custody.requestNonce(), DeliverySaveCheckWatch.orderStaffAlert(custody.requestNonce()));");
        // Commerce never raises ITEM_REPAIR_HELD, and returns never touch the order counts.
        String returns = refused.substring(0, refused.indexOf("return; }"));
        assertEquals(1, mentions(refused, "ITEM_REPAIR_HELD"));
        assertEquals(1, mentions(returns, "ITEM_REPAIR_HELD"));
        assertEquals(0, mentions(returns, "orderSaveChecks"));
        assertEquals(0, mentions(refused.substring(refused.indexOf("return; }")), "saveChecks"));
        assertEquals(2, mentions(delivery, "orderSaveChecks"), "declared, and used only for orders");
        // Two separate watches: an alias would let order refusals clear the return counts at the tracking cap.
        assertTrue(delivery.contains("private final DeliverySaveCheckWatch saveChecks = new DeliverySaveCheckWatch();"));
        assertTrue(delivery.contains("private final DeliverySaveCheckWatch orderSaveChecks = new DeliverySaveCheckWatch();"));
    }

    /**
     * A marked order (town.commerce.delivery-save-marker on) is proven like a return, with three more steps: a preflight
     * that writes nothing before the save, a main-thread check that the inventory is still exactly as saved before the
     * start, and beginDelivery in its own worker task, skipped once the runtime closed.
     */
    @Test void aCommerceDeliveryWithTheMarkerIsProvenAgainstASaveMadeJustBefore() throws Exception {
        String delivery = squash(code(source("commerce/ItemDeliveryRuntime.java")));
        inOrder(delivery, "boolean orderProof = marked && !returned;", "if (orderProof) { preflight(operation); return; }",
                "if (marked && !savedBefore(operation)) return;",
                "private void preflight(", "var refusal = repository.wouldRefuse(custody, Instant.now());",
                "post(() -> afterPreflight(operation, refusal));",
                "private void afterPreflight(", "Result refused = preflightReply(refusal, operation.custody().requestNonce());",
                "if (refused != null) { finish(operation, refused); return; }",
                "if (!authorized(operation) || player.isDead()) { finish(operation, Result.UNAUTHORIZED); return; }",
                "if (!savedBefore(operation)) return;",
                "try { preState = ItemInventoryCustody.inventoryState(player); }",
                "orderBaseline(operation, preState);",
                "private void orderBaseline(", "var read = saveProof.deliveryBaseline(playerDataWorld, custody);",
                "if (read.isEmpty()) { post(() -> { saveCheckRefused(custody); finish(operation, Result.NOT_READY); }); return; }",
                "post(() -> beforeStart(operation, preState, baseline));",
                "private void beforeStart(", "boolean alive = authorized(operation) && !player.isDead();",
                "ItemInventoryCustody.inventoryState(player).equals(preState)",
                "Result refused = startRefusal(alive, same, receivable);", "orderStart(operation, baseline);",
                "private void orderStart(", "submit(() -> { if (closed) return; startTried.set(true);",
                "var start = repository.beginDelivery(custody, runId, Instant.now());",
                "post(() -> dispatch(operation, start, baseline));",
                "private void dispatch(",
                "if (orderProof(baseline, operation) && operation.login().isDead()) { ambiguous(operation, baseline); return; }",
                "if (baseline != null) ItemInventoryCustody.markDelivered(operation.login(), operation.custody());",
                "evidence = ItemInventoryCustody.deliver(operation.login(), operation.custody());",
                ": saveProof.captureDelivery(playerDataWorld, operation.custody(), evidence, baseline);",
                "confirmedOutcome(operation.custody().source(), baseline != null)");
        // The baseline read and the start are separate worker tasks, with the main-thread checks between them.
        String read = between(delivery, "private void orderBaseline(", "private void beforeStart(");
        String start = between(delivery, "private void orderStart(", "private void unclearStart(");
        assertEquals(1, mentions(read, "deliveryBaseline"));
        assertEquals(0, mentions(read, "beginDelivery"));
        assertEquals(1, mentions(start, "beginDelivery"));
        assertEquals(0, mentions(start, "deliveryBaseline"));
        inOrder(start, "if (closed) return;", "startTried.set(true);", "repository.beginDelivery(");
        // A start that failed after it may have committed carries the order's baseline, so a hold there is reported too.
        inOrder(start, "}, () -> { if (startTried.get()) unclearStart(operation, baseline); else finish(operation, Result.NOT_READY); });");
        // The before-save comes after the preflight and never before it: deliver hands an order to preflight first.
        String deliver = between(delivery, "public void deliver(", "static boolean marked(");
        assertTrue(deliver.indexOf("if (orderProof) { preflight(operation); return; }") < deliver.indexOf("savedBefore("));
        // A held marked order is reported once it started: ambiguous and a recorded hold, both from main.
        inOrder(between(delivery, "private void ambiguous(", "private void heldReport("),
                "post(() -> { heldReport(operation, baseline); finish(operation, Result.HELD); });");
        assertTrue(delivery.contains("if (!delivered) heldReport(operation, baseline);"));
        String held = between(delivery, "private void heldReport(", "private void holdUnreadable(");
        inOrder(held, "if (!orderProof(baseline, operation)) return;", "DeliverySaveCheckWatch.orderHeldLine(",
                "baseline.units().getFirst()", "StaffAlerts.Kind.COMMERCE_DELIVERY_HELD", "\"order-held-\" + custody.requestNonce()");
        String dispatch = between(delivery, "private void dispatch(", "private static boolean orderProof(");
        assertEquals(0, dispatch.split(java.util.regex.Pattern.quote("ambiguous(operation);"), -1).length - 1,
                "every hold in dispatch carries the baseline, so a marked order is reported");
        assertEquals(5, dispatch.split(java.util.regex.Pattern.quote("ambiguous(operation, baseline);"), -1).length - 1, "five holds, each with the baseline");
    }

    /**
     * With the key off a commerce delivery takes the plain path: the front check, no before-save, one worker task
     * whose failure is HELD, no death check, the count-only capture and INVENTORY_CONFIRMED.
     */
    @Test void flagOffCommerceIsTheDev59Path() throws Exception {
        String raw = source("commerce/ItemDeliveryRuntime.java");
        assertTrue(raw.contains("""
                        if (!returned) {
                            // Commerce checks for space here; stored items that cannot be read answer HELD.
                            try {
                                if (!ItemInventoryCustody.canReceive(player, ItemInventoryCustody.decode(custody))) {
                                    finish(operation, Result.NO_SPACE); return;
                                }
                            } catch (RuntimeException | LinkageError invalid) { finish(operation, Result.HELD); return; }
                """), "the commerce front check");
        String delivery = squash(code(raw));
        String deliver = between(delivery, "public void deliver(", "static boolean marked(");
        // Unmarked commerce never saves before: the only before-save in deliver is behind marked, and the baseline read too.
        assertEquals(1, mentions(deliver, "savedBefore"));
        assertTrue(deliver.contains("if (marked && !savedBefore(operation)) return;"));
        inOrder(deliver, "submit(() -> { ItemSaveProof.Baseline baseline = null; if (marked) {",
                "var read = saveProof.deliveryBaseline(playerDataWorld, custody);");
        assertTrue(deliver.contains("}, () -> { if (!marked) finish(operation, Result.HELD);"), "the commerce worker failure reply is HELD");
        // The shared path has no death check and never skips the start on close: a stop still leaves a started intent for
        // recovery. Only the order path checks either.
        assertEquals(0, mentions(deliver, "isDead"));
        assertEquals(0, mentions(deliver, "closed"));
        assertEquals(2, delivery.split(java.util.regex.Pattern.quote("if (closed) return;"), -1).length - 1,
                "the recovery loop in the constructor and orderStart");
        String dispatch = between(delivery, "private void dispatch(", "private static boolean orderProof(");
        assertTrue(dispatch.contains("var persisted = baseline == null ? saveProof.capture(playerDataWorld, operation.custody(), evidence)"));
        // The death check is guarded by the order proof, which is false without a baseline and for a return.
        assertEquals(1, mentions(dispatch, "isDead"));
        assertTrue(dispatch.contains("if (orderProof(baseline, operation) && operation.login().isDead())"));
        assertTrue(delivery.contains("private static boolean orderProof(ItemSaveProof.Baseline baseline, Pending operation) {"
                + " return baseline != null && operation.custody().source() != ItemCustody.Source.REPAIR_RETURN; }"));
        // The order path does check death, twice before the start.
        assertEquals(2, mentions(between(delivery, "private void afterPreflight(", "private void orderStart("), "isDead"));
        // authorized() itself does not check death, so a return is not refused for it.
        assertFalse(between(delivery, "private boolean authorized(", "private void finish(").contains("isDead"));
    }


    /** RETURNED_BY_STAFF always needs a reason, and the report tells staff when it is the decision that fits. */
    @Test void returnedByStaffNeedsAReasonAndTheReportSaysWhenItFits() {
        var decision = ItemCustodyRepository.Decision.valueOf("RETURNED_BY_STAFF");
        var nothing = new ItemDeliveryEvidence(PLAYER, "a".repeat(64), "b".repeat(64), "b".repeat(64), 10, 10, 64, true);
        for (var recorded : java.util.Arrays.asList(nothing, null))
            assertEquals(ItemRepairRecoveryTool.STAFF_RETURN, ItemRepairRecoveryTool.returnDecisionProblem(decision, 10, 64, recorded));
        var read = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(List.of(10), 4790, List.of(4790), List.of(10), null, null);
        assertThrows(IllegalArgumentException.class, () -> ItemRepairRecoveryTool.checkedOverride(
                ItemRepairRecoveryTool.returnProblem(read, decision, 64, null), ""), "no reason, no decision");
        var request = new com.prosperitysmp.npc.commerce.ItemCustody(new java.util.UUID(3, 3), PLAYER, new java.util.UUID(4, 4),
                com.prosperitysmp.npc.commerce.ItemCustody.Source.REPAIR_RETURN, 64,
                new com.prosperitysmp.npc.commerce.CustodyBytes(new byte[]{1}), java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(900));
        var binding = new ItemRepairRepository.ReturnBinding(HAND_IN, 0);
        var unreadable = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.AMBIGUOUS, new java.util.UUID(1, 1),
                ItemCustodyRepository.PAYLOAD_UNREADABLE);
        var uncertain = new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.AMBIGUOUS, new java.util.UUID(1, 1),
                "DELIVERY_UNCERTAIN");
        assertTrue(ItemRepairRecoveryTool.returnReport(unreadable, binding, "PAPER", read, null, "f".repeat(64))
                .endsWith("\n" + ItemRepairRecoveryTool.UNREADABLE_LINE));
        assertFalse(ItemRepairRecoveryTool.returnReport(uncertain, binding, "PAPER", read, null, "f".repeat(64))
                .contains("RETURNED_BY_STAFF"), "only an unreadable return points at it");
    }

    @Test void theReportSaysWhenNoDataVersionWasRecorded() {
        var none = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(List.of(4), 4790, List.of(), List.of(4), null, null);
        assertEquals("DataVersion: file 4790 | recorded with the items none, so not compared", ItemRepairRecoveryTool.versionLine(none));
        var both = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(List.of(4), null, List.of(4790), List.of(4), null, null);
        assertEquals("DataVersion: file none | recorded with the items [4790]", ItemRepairRecoveryTool.versionLine(both));
    }

    /** Every decision needs override_reason, so the report is staff's evidence: both counts and the marker, on both paths. */
    @Test void theReportShowsBothCountsAndWhetherThisMarkerIsInTheFile() throws Exception {
        var read = new com.prosperitysmp.npc.commerce.ItemSaveProof.SavedUnits(List.of(4, 0), 4790, List.of(4790), List.of(20, 8),
                null, null);
        assertEquals("storage and ender chest 4 | whole file 20", ItemRepairRecoveryTool.counts(read, 0));
        assertEquals("storage and ender chest 0 | whole file 8", ItemRepairRecoveryTool.counts(read, 1));
        String after = com.prosperitysmp.npc.commerce.ItemSaveProof.marker(HAND_IN, true);
        String before = com.prosperitysmp.npc.commerce.ItemSaveProof.marker(HAND_IN, false);
        String other = com.prosperitysmp.npc.commerce.ItemSaveProof.marker(PLAYER, true);
        assertEquals("Marker: this hand-in's after marker. The file was saved after the take.",
                ItemRepairRecoveryTool.markerLine("hand-in", "take", HAND_IN, after));
        assertEquals("Marker: this hand-in's before marker. The file was saved before the take, so it does not show it.",
                ItemRepairRecoveryTool.markerLine("hand-in", "take", HAND_IN, before));
        assertEquals("Marker: none for this return. The file was saved before this return started, or the marker was removed.",
                ItemRepairRecoveryTool.markerLine("return", "delivery", HAND_IN, null));
        assertEquals("Marker: " + other + ", which belongs to another return. This file is not this return's save.",
                ItemRepairRecoveryTool.markerLine("return", "delivery", HAND_IN, other));
        String tool = squash(code(source("economy/ItemRepairRecoveryTool.java")));
        assertTrue(tool.contains(squash("""
                String report = report(stored, read, recorded, fileHash) + "\\n" + markerLine("hand-in", "take", collection, read.handInMarker())
                        + "\\n" + versionLine(read);""")), "the hand-in report");
        assertTrue(tool.contains(squash("""
                String report = returnReport(receipt, binding, line.material(), read, recorded, fileHash)
                        + "\\n" + markerLine("return", "delivery", nonce, read.returnMarker()) + "\\n" + versionLine(read);""")), "the return report");
        assertTrue(tool.contains(squash("""
                + "\\n" + material + " x" + request.quantity() + ": " + counts(read, 0)""")));
        assertTrue(tool.contains(squash("""
                out.append('\\n').append(line.material()).append(": ").append(counts(read, i))""")));
    }

    @Test void everyProblemIsReportedAndNoneMeansClear() {
        assertNull(ItemRepairRecoveryTool.problems(null, null), "nothing found");
        assertEquals("A.", ItemRepairRecoveryTool.problems(null, "A."));
        assertEquals("A.", ItemRepairRecoveryTool.problems("A.", null));
        assertEquals("A. B.", ItemRepairRecoveryTool.problems("A.", "B."));
    }

    @Test void squashJoinsEveryWhitespaceRun() {
        assertEquals("a b c", squash("  a\n\t b\r\n\nc \n"));
        assertEquals(squash("return x(a, b);"), squash("return x(a,\n        b);"), "a pin survives a moved line break");
    }

    private static ItemRepairRepository.Summary row(ItemRepairRepository.State state, String outcome, int undelivered,
                                                    java.util.UUID held, String heldState) {
        return new ItemRepairRepository.Summary(HAND_IN, PLAYER, 7, state, outcome, undelivered, held, heldState);
    }

    @Test void staffListNamesAHeldReturnsReference() {
        var nonce = new java.util.UUID(9, 9);
        assertEquals(List.of(HAND_IN + " | player " + PLAYER + " | problem #7 | RETURN_ISSUED OWNERSHIP_CHANGED | 2 stacks to give back"
                        + " | held return " + nonce),
                ItemRepairRuntime.staffLines(List.of(row(ItemRepairRepository.State.RETURN_ISSUED, "OWNERSHIP_CHANGED", 2, nonce, "AMBIGUOUS"))));
        assertEquals(List.of(HAND_IN + " | player " + PLAYER + " | problem #7 | RETURN_ISSUED OWNERSHIP_CHANGED | 2 stacks to give back"
                        + " | return " + nonce + " mid-delivery"),
                ItemRepairRuntime.staffLines(List.of(row(ItemRepairRepository.State.RETURN_ISSUED, "OWNERSHIP_CHANGED", 2, nonce, "DELIVERY_INTENT"))));
        assertEquals(List.of(HAND_IN + " | player " + PLAYER + " | problem #7 | AMBIGUOUS INTERRUPTED_RUN"),
                ItemRepairRuntime.staffLines(List.of(row(ItemRepairRepository.State.AMBIGUOUS, "INTERRUPTED_RUN", 0, null, null))));
    }

    @Test void startupAlertsHeldHandInsAndHeldOrInterruptedReturns() {
        java.util.UUID held = new java.util.UUID(9, 1), interrupted = new java.util.UUID(9, 2);
        var rows = List.of(row(ItemRepairRepository.State.AMBIGUOUS, "INTERRUPTED_RUN", 0, null, null),
                row(ItemRepairRepository.State.RETURN_ISSUED, "OWNERSHIP_CHANGED", 2, held, "AMBIGUOUS"),
                row(ItemRepairRepository.State.RETURN_ISSUED, "OWNERSHIP_CHANGED", 2, interrupted, "DELIVERY_INTENT"),
                row(ItemRepairRepository.State.RETURN_ISSUED, "OWNERSHIP_CHANGED", 2, null, null),
                row(ItemRepairRepository.State.INTENT, null, 0, null, null));
        assertEquals(List.of(HAND_IN, held, interrupted), ItemRepairRuntime.heldReferences(rows));
    }
}
