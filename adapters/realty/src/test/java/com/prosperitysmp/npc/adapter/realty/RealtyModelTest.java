package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.contract.RecordingHooks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.prosperitysmp.npc.adapter.realty.RealtySubject.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The backend against the model, one step of a Realty operation at a time. The payout helper does what the core
 * does: it reads the owner when a payment is planned and reads it again when the payment is debited, and pays only
 * when the token matches and nothing was reported in between.
 */
class RealtyModelTest {
    static final UUID BUYER = new UUID(0, 201), TENANT_TWO = new UUID(0, 202);
    static final Duration MONTH = Duration.ofDays(30);

    /** A subject with the guard installed. */
    static final class Rig {
        final RealtySubject subject = new RealtySubject();
        final RealtyModel model = subject.model;
        final ModelView view = subject.view;
        final RealtyPropertyBackend backend = subject.backend;
        final RecordingHooks hooks = new RecordingHooks();

        Rig() {
            subject.install(hooks);
        }

        /** The unit is rented to TENANT and the refresh has happened. */
        Rig rented() {
            model.rent(WORLD, UNIT, TENANT, MONTH).runAll();
            view.pump();
            hooks.clearBumps();
            return this;
        }

        Optional<OwnershipToken> read(String region, UUID owner) {
            return backend.ownerReadRegion(WORLD, region, owner);
        }

        /** A payment planned now. Null when the backend names nobody to pay. */
        Plan plan(String region, UUID owner) {
            return read(region, owner).map(token -> new Plan(token, region, owner, hooks.bumpsOf(WORLD, region))).orElse(null);
        }

        boolean debit(Plan plan) {
            var again = read(plan.region, plan.owner);
            return again.isPresent() && plan.token.sameIncarnation(again.get()) && hooks.bumpsOf(WORLD, plan.region) == plan.bumps;
        }

        UUID databaseHolder(String region) {
            return model.plotOf(model.region(WORLD, region)).map(RealtyView.Plot::holderId).orElse(null);
        }
    }

    record Plan(OwnershipToken token, String region, UUID owner, long bumps) { }

    /**
     * The steps after which a payment planned for {@code old} still goes through while the database names someone
     * else. The plan is taken after each possible number of steps and the debit after each later step, with and
     * without the queued reloads running in between.
     */
    static Set<String> wrongPayments(Function<Rig, RealtyModel.Steps> operation, String region, UUID old, boolean pump) {
        var wrong = new TreeSet<String>();
        int size = operation.apply(new Rig()).size();
        for (int planned = 0; planned <= size; planned++) {
            var rig = new Rig();
            var steps = operation.apply(rig);
            for (int i = 0; i < planned; i++) {
                steps.runNext();
                if (pump) rig.view.pump();
            }
            Plan plan = rig.plan(region, old);
            if (plan == null) continue;
            for (int debited = planned; debited <= size; debited++) {
                if (debited > planned) {
                    steps.runNext();
                    if (pump) rig.view.pump();
                }
                String after = debited == 0 ? "start" : steps.name(debited - 1);
                if (rig.debit(plan) && !old.equals(rig.databaseHolder(region))) wrong.add(after);
            }
        }
        return wrong;
    }

    static void assertWindow(Function<Rig, RealtyModel.Steps> operation, String region, UUID old, Set<String> expected) {
        assertEquals(expected, wrongPayments(operation, region, old, false), "readers idle between steps");
        assertEquals(expected, wrongPayments(operation, region, old, true), "reloads run between steps");
    }

    // The window of each operation

    @Test void aBuyIsOpenOnlyBetweenTheDatabaseCommitAndTheWorldGuardWrite() {
        assertWindow(rig -> rig.model.buy(WORLD, PLOT, BUYER), PLOT, OWNER, Set.of("db-commit", "payment"));
    }

    @Test void aTransferIsOpenOnlyBetweenTheDatabaseCommitAndTheWorldGuardWrite() {
        assertWindow(rig -> rig.model.transfer(WORLD, PLOT, BUYER), PLOT, OWNER, Set.of("db-commit"));
    }

    @Test void anAuctionPaymentIsOpenOnlyBetweenTheDatabaseCommitAndTheWorldGuardWrite() {
        assertWindow(rig -> rig.model.auctionWon(WORLD, PLOT, BUYER), PLOT, OWNER, Set.of("db-commit"));
    }

    @Test void unsetTitleholderIsOpenForOneStepAndClosesWithoutAnyEvent() {
        assertWindow(rig -> rig.model.unsetTitleholder(WORLD, PLOT), PLOT, OWNER, Set.of("db-commit"));
    }

    @Test void aFailedPurchaseIsOpenUntilTheRollbackCommits() {
        assertWindow(rig -> rig.model.failedPurchase(WORLD, PLOT, BUYER), PLOT, OWNER, Set.of("db-commit", "payment-fails"));
    }

    @Test void anUnrentIsOpenOnlyBetweenTheDatabaseCommitAndTheWorldGuardClear() {
        assertWindow(rig -> rig.rented().model.unrent(WORLD, UNIT), UNIT, TENANT, Set.of("db-commit", "refund"));
    }

    @Test void aSetTenantIsOpenOnlyBetweenTheDatabaseCommitAndTheWorldGuardWrite() {
        assertWindow(rig -> rig.rented().model.setTenant(WORLD, UNIT, TENANT_TWO), UNIT, TENANT, Set.of("db-commit"));
    }

    @Test void unsetTenantIsOpenForOneStepAndClosesWithoutAnyEvent() {
        assertWindow(rig -> rig.rented().model.unsetTenant(WORLD, UNIT), UNIT, TENANT, Set.of("db-commit"));
    }

    @Test void aWorldGuardOnlyChangeNeverNamesAPayer() {
        List<Function<Rig, RealtyModel.Steps>> edits = List.of(
                rig -> rig.model.staffSetOwners(WORLD, PLOT, BUYER),
                rig -> rig.model.staffAddOwner(WORLD, PLOT, BUYER));
        for (var edit : edits) {
            for (boolean pump : List.of(false, true)) {
                var rig = new Rig();
                var steps = edit.apply(rig);
                Plan before = rig.plan(PLOT, OWNER);
                assertNotNull(before);
                while (!steps.done()) {
                    steps.runNext();
                    if (pump) rig.view.pump();
                    assertNull(rig.plan(PLOT, OWNER), "the old holder is not paid");
                    assertNull(rig.plan(PLOT, BUYER), "the new name on the list is not paid");
                    assertFalse(rig.debit(before));
                }
            }
        }
    }

    @Test void landlordAuthorityAndCascadeChangesKeepThePayer() {
        assertPayerKept(rig -> rig.rented().model.setLandlord(WORLD, UNIT, RealtyModel.PartyKind.ACCOUNT), UNIT, TENANT);
        assertPayerKept(rig -> rig.model.setLandlord(WORLD, UNIT, RealtyModel.PartyKind.GROUP), PLOT, OWNER);
    }

    /** A plan taken before the operation still debits after every step, because nothing about the payer moved. */
    static void assertPayerKept(Function<Rig, RealtyModel.Steps> operation, String region, UUID payer) {
        var rig = new Rig();
        var steps = operation.apply(rig);
        Plan plan = rig.plan(region, payer);
        assertNotNull(plan);
        for (int i = 0; i < steps.size(); i++) {
            steps.runNext();
            rig.view.pump();
            assertTrue(rig.read(region, payer).isPresent(), steps.name(i));
            assertTrue(rig.debit(plan), steps.name(i));
        }
    }

    @Test void aChangeMadeOnlyInTheDatabaseIsOpenUntilTheNextLoad() {
        // This window stays open: a direct backend call, or another server on the same database, fires nothing here.
        assertWindow(rig -> rig.model.databaseOnlyTransfer(WORLD, PLOT, BUYER), PLOT, OWNER, Set.of("db-commit"));
        var rig = new Rig();
        Plan plan = rig.plan(PLOT, OWNER);
        rig.model.databaseOnlyTransfer(WORLD, PLOT, BUYER).runAll();
        assertTrue(rig.debit(plan), "before the reload the old holder still reads as the payer");
        rig.subject.settle();
        assertFalse(rig.debit(plan), "after the reload it does not");
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
    }

    @Test void theEndStateOfEachOperationNamesTheNewHolderWithANewToken() {
        record Case(String name, Function<Rig, RealtyModel.Steps> operation, String region, UUID before, UUID after) { }
        var cases = List.of(
                new Case("buy", rig -> rig.model.buy(WORLD, PLOT, BUYER), PLOT, OWNER, BUYER),
                new Case("transfer", rig -> rig.model.transfer(WORLD, PLOT, BUYER), PLOT, OWNER, BUYER),
                new Case("auction", rig -> rig.model.auctionWon(WORLD, PLOT, BUYER), PLOT, OWNER, BUYER),
                new Case("set tenant", rig -> rig.rented().model.setTenant(WORLD, UNIT, TENANT_TWO), UNIT, TENANT, TENANT_TWO),
                new Case("unrent then rent", rig -> rig.rented().model.unrent(WORLD, UNIT), UNIT, TENANT, null));
        for (var c : cases) {
            var rig = new Rig();
            var steps = c.operation.apply(rig);
            var before = rig.read(c.region, c.before).orElseThrow(() -> new AssertionError(c.name));
            steps.runAll();
            rig.view.pump();
            assertTrue(rig.read(c.region, c.before).isEmpty(), c.name + ": the old holder reads nothing");
            if (c.after == null) continue;
            var after = rig.read(c.region, c.after).orElseThrow(() -> new AssertionError(c.name));
            assertFalse(before.sameIncarnation(after), c.name);
        }
    }

    @Test void aLeaseThatAlreadyEndedReadsUnownedThroughoutItsSweep() {
        Function<Rig, RealtyModel.Steps> sweep = rig -> {
            rig.rented();
            rig.model.advance(MONTH.plusSeconds(1));
            return rig.model.expirySweep(WORLD, UNIT);
        };
        var rig = new Rig();
        sweep.apply(rig);
        assertNull(rig.plan(UNIT, TENANT), "the lease reads as ended before the sweep runs");
        assertWindow(sweep, UNIT, TENANT, Set.of());
    }

    // Unset, then the old owner comes back

    @Test void aWorldGuardLoadThatRestoresTheOldOwnerAfterAnUnsetPaysNobody() {
        var rig = new Rig();
        rig.model.unsetTitleholder(WORLD, PLOT).runAll();
        assertTrue(rig.read(PLOT, OWNER).isEmpty(), "the owner list is empty, so the read disagrees and reloads");
        rig.view.pump();
        rig.model.guard(WORLD, PLOT).owners.add(OWNER);
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.plan(PLOT, OWNER) == null);
    }

    @Test void anOwnerWhoComesBackBeforeAnyReadIsAKnownWindow() {
        // This window stays open: nothing read the plot between the clear and the return, so nothing marked the slot.
        var rig = new Rig();
        rig.model.unsetTitleholder(WORLD, PLOT).runAll();
        rig.model.guard(WORLD, PLOT).owners.add(OWNER);
        assertTrue(rig.read(PLOT, OWNER).isPresent());
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
    }

    // Stale lists and titles taken without paying

    @Test void aPlayerOnAStaleOwnerListWhoTransfersTheTitleToThemselvesHoldsThePlot() {
        var rig = new Rig();
        rig.model.staffAddOwner(WORLD, PLOT, BUYER).runAll();
        assertTrue(rig.read(PLOT, OWNER).isEmpty(), "two owners on the list");
        rig.view.pump();
        rig.model.transfer(WORLD, PLOT, BUYER).runAll();
        rig.view.pump();
        assertTrue(rig.backend.held().contains(WORLD_NAME + " " + PLOT));
        assertTrue(rig.read(PLOT, BUYER).isEmpty(), "a payout to the new holder is held");
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty(), "the core's reconcile reads nothing");
        assertTrue(rig.subject.logged.getLast().contains("held until staff release it"));
        assertTrue(rig.backend.release(WORLD_NAME, PLOT));
        assertTrue(rig.read(PLOT, BUYER).isPresent());
    }

    @Test void aPlayerOnTheListOfAnUnsoldFreeholdWhoTransfersTheTitleToThemselvesHoldsThePlot() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "lot", null, null, 30, 30, 35, 35);
        rig.model.staffAddOwner(WORLD, "lot", BUYER).runAll();
        rig.subject.settle();
        assertTrue(rig.read("lot", BUYER).isEmpty(), "an unsold plot pays nobody");
        rig.model.transfer(WORLD, "lot", BUYER).runAll();
        rig.view.pump();
        assertTrue(rig.backend.held().contains(WORLD_NAME + " lot"));
        assertTrue(rig.read("lot", BUYER).isEmpty());
    }

    @Test void aSoldFreeholdInsideAFreeholdBlocksTheOuterOne() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "cottage", BUYER, PLOT, 10, 10, 12, 12);
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.read("cottage", BUYER).isPresent());
    }

    @Test void aRentedUnitInsideARentedLeaseBlocksTheOuterLease() {
        var rig = new Rig().rented();
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        rig.model.addLease(WORLD, "room", TENANT_TWO, rig.model.dbNow().plus(MONTH), UNIT, 6, 6, 7, 7);
        rig.subject.settle();
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
        assertTrue(rig.read("room", TENANT_TWO).isPresent());
    }

    @Test void aRegionDeletedAndRegisteredAgainUnderAnotherHolderIsHeld() {
        var rig = new Rig();
        rig.model.deleteRegion(WORLD, PLOT, true);
        rig.model.fire("RegionDeletedEvent", WORLD, PLOT);
        rig.model.addFreehold(WORLD, PLOT, BUYER, null, 0, 0, 20, 20);
        rig.model.fire("RegionCreatedEvent", WORLD, PLOT);
        rig.view.pump();
        assertTrue(rig.read(PLOT, BUYER).isEmpty());
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        assertEquals(Presence.PRESENT, rig.backend.observe(WORLD, PLOT));
    }

    @Test void aLeaseTurnoverIsNotHeldAndTheNewTenantPays() {
        // A new tenant takes over the enrolled home at the next reconcile.
        var rig = new Rig().rented();
        rig.model.unrent(WORLD, UNIT).runAll();
        rig.model.rent(WORLD, UNIT, TENANT_TWO, MONTH).runAll();
        rig.view.pump();
        assertTrue(rig.backend.held().isEmpty());
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
        assertTrue(rig.read(UNIT, TENANT_TWO).isPresent());
        assertEquals(Optional.of(TENANT_TWO), rig.backend.findRegion(WORLD_NAME, UNIT).orElseThrow().ownerUuid());
    }

    // Party kinds and the owner list

    @Test void aSoldPlotPaysItsTitleHolderWhateverTheAuthorityIs() {
        for (var kind : RealtyModel.PartyKind.values()) {
            var rig = new Rig();
            rig.model.region(WORLD, PLOT).authority = kind;
            rig.subject.settle();
            assertTrue(rig.read(PLOT, OWNER).isPresent(), kind.toString());
        }
    }

    @Test void aPlotForSalePaysNobodyWhateverTheAuthorityIs() {
        for (var kind : RealtyModel.PartyKind.values()) {
            var rig = new Rig();
            rig.model.addFreehold(WORLD, "sale", null, null, 30, 30, 35, 35).authority = kind;
            rig.subject.settle();
            assertTrue(rig.read("sale", OWNER).isEmpty(), kind.toString());
            assertEquals(Optional.empty(), rig.backend.findRegion(WORLD_NAME, "sale").orElseThrow().ownerUuid());
        }
    }

    @Test void aRentedUnitPaysItsTenantWhateverTheLandlordIs() {
        for (var kind : RealtyModel.PartyKind.values()) {
            var rig = new Rig().rented();
            rig.model.region(WORLD, UNIT).landlord = kind;
            rig.subject.settle();
            assertTrue(rig.read(UNIT, TENANT).isPresent(), kind.toString());
            assertTrue(rig.read(UNIT, OWNER).isEmpty(), "the parent's title holder does not pay for the unit");
        }
    }

    @Test void aVacantUnitPaysNobodyWhateverTheLandlordIs() {
        for (var kind : RealtyModel.PartyKind.values()) {
            var rig = new Rig();
            rig.model.region(WORLD, UNIT).landlord = kind;
            rig.model.guard(WORLD, UNIT).owners.add(OWNER);
            rig.subject.settle();
            for (UUID who : List.of(OWNER, TENANT, new UUID(0, 99))) assertTrue(rig.read(UNIT, who).isEmpty(), kind + " " + who);
        }
    }

    @Test void aZeroHolderPaysNobody() {
        var rig = new Rig();
        rig.subject.setOwner(PLOT, new UUID(0, 0));
        assertTrue(rig.read(PLOT, new UUID(0, 0)).isEmpty());
    }

    @Test void aRegionWithBothContractsOrNoContractIsNotAHome() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "both", OWNER, null, 30, 30, 35, 35).lease = true;
        rig.model.defineGuard(WORLD, "bare", null, 40, 40, 45, 45).owners.add(OWNER);
        rig.subject.settle();
        assertTrue(rig.read("both", OWNER).isEmpty());
        assertEquals(Presence.MISSING, rig.backend.observe(WORLD, "bare"));
        assertTrue(rig.read("bare", OWNER).isEmpty());
        assertTrue(rig.backend.regionAt(rig.subject.at(42, 42)).isEmpty());
    }

    @Test void theWorldGuardOwnerListMustHoldExactlyTheHolder() {
        UUID other = new UUID(0, 99);
        record Edit(String name, Consumer<RealtyModel.Wg> change, boolean pays) { }
        var edits = List.of(
                new Edit("empty", wg -> wg.owners.clear(), false),
                new Edit("two owners", wg -> wg.owners.add(other), false),
                new Edit("another owner", wg -> { wg.owners.clear(); wg.owners.add(other); }, false),
                new Edit("plus a legacy name", wg -> wg.names = 1, false),
                new Edit("plus a group", wg -> wg.groups = 1, false),
                new Edit("a member is added", wg -> wg.members.add(other), true));
        for (var edit : edits) {
            var rig = new Rig();
            edit.change.accept(rig.model.guard(WORLD, PLOT));
            assertEquals(edit.pays, rig.read(PLOT, OWNER).isPresent(), edit.name);
        }
    }

    @Test void aMissingWorldGuardRegionPaysNobody() {
        var rig = new Rig();
        rig.model.wg.remove(WORLD + "/" + PLOT);
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
    }

    // Lease dates and clocks

    @Test void aLeaseIsRereadAgainstTheClockOnEveryRead() {
        var rig = new Rig();
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofMinutes(5)).runAll();
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        rig.model.advance(Duration.ofMinutes(3));
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        rig.model.advance(Duration.ofSeconds(100));
        assertTrue(rig.read(UNIT, TENANT).isEmpty(), "inside the margin of the end date");
    }

    @Test void aLeaseRentedWithLessThanTheMarginLeftReadsUnowned() {
        var rig = new Rig();
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofSeconds(50)).runAll();
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
    }

    @Test void aScheduledTerminationEndsTheLeaseEarlyAndACancelledOneDoesNot() {
        var rig = new Rig().rented();
        rig.model.scheduleTermination(WORLD, UNIT, rig.model.jvmNow().plusSeconds(20)).runAll();
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
        rig.model.region(WORLD, UNIT).termination = null;
        rig.model.fire("LeaseTerminationCancelledEvent", WORLD, UNIT);
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isPresent());
    }

    @Test void aDatabaseClockAheadOfTheServerClockIsMeasuredByTheFirstRent() {
        var rig = new Rig();
        rig.model.dbOffset = Duration.ofMinutes(5);
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofMinutes(10)).runAll();
        rig.view.pump();
        assertTrue(rig.backend.describe().getLast().contains("clock skew bound 300s"), rig.backend.describe().toString());
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        // The database ends the lease when its own clock reaches the end date: ten minutes after the rent.
        rig.model.advance(Duration.ofMinutes(9));
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        rig.model.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertTrue(rig.read(UNIT, TENANT).isEmpty(), "the margin grew to the measured gap, so the lease reads ended in time");
    }

    @Test void aClockSkewNobodyHasMeasuredIsAKnownWindowForAMissingWorldSweep() {
        // This window stays open: the database clock is five minutes ahead and no rent was seen since start.
        var rig = new Rig();
        rig.model.dbOffset = Duration.ofMinutes(5);
        rig.model.addLease(WORLD, "far", TENANT, rig.model.dbNow().plusMinutes(8), null, 30, 30, 35, 35);
        rig.model.fire("RegionCreatedEvent", WORLD, "far");
        rig.view.pump();
        rig.model.advance(Duration.ofSeconds(210));
        rig.model.sweepWithoutWorld(WORLD, "far").runAll();
        assertTrue(rig.read("far", TENANT).isPresent(), "the sweep cleared the tenant, and nothing here saw it yet");
        rig.model.advance(Duration.ofMillis(RegionCache.Tuning.HOT_EVERY + 1000));
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read("far", TENANT).isEmpty(), "the next hot load sees it");
    }

    @Test void aDatabaseClockBehindTheServerClockOnlyHoldsPayouts() {
        var rig = new Rig();
        rig.model.dbOffset = Duration.ofMinutes(-5);
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofMinutes(3)).runAll();
        rig.view.pump();
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
    }

    @Test void aSmallClockSkewInsideTheMarginChangesNothingBadForALongLease() {
        for (long seconds : List.of(30L, -30L)) {
            var rig = new Rig();
            rig.model.dbOffset = Duration.ofSeconds(seconds);
            rig.model.rent(WORLD, UNIT, TENANT, MONTH).runAll();
            rig.view.pump();
            assertTrue(rig.read(UNIT, TENANT).isPresent(), "skew " + seconds);
        }
    }

    // Nested homes

    @Test void aFreeholdIsNotPaidWhileAUnitInsideItIsRented() {
        var rig = new Rig();
        assertTrue(rig.read(PLOT, OWNER).isPresent(), "the unit is vacant");
        rig.model.rent(WORLD, UNIT, TENANT, MONTH).runAll();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.read(UNIT, TENANT).isPresent());
        rig.model.unrent(WORLD, UNIT).runAll();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isPresent());
        assertTrue(rig.read(UNIT, TENANT).isEmpty());
    }

    @Test void aFreeholdStopsPayingAtOnceWhenAUnitInsideItIsRentedAndTheReloadHasNotRun() {
        var rig = new Rig();
        Plan plan = rig.plan(PLOT, OWNER);
        rig.model.rent(WORLD, UNIT, TENANT, MONTH).runAll();
        assertFalse(rig.debit(plan), "the event moved the fence of the freehold around the unit");
        assertTrue(rig.read(PLOT, OWNER).isEmpty(), "the unit may be rented, so the freehold is not paid");
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty(), "a state that cannot be read writes nothing");
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.read(UNIT, TENANT).isPresent());
    }

    @Test void aUnitMadeWithoutAnEventBlocksTheFreeholdAsSoonAsItIsRented() {
        var rig = new Rig();
        rig.model.addLease(WORLD, "annex", null, null, PLOT, 10, 10, 12, 12);
        rig.model.rent(WORLD, "annex", TENANT, MONTH).runAll();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.read("annex", TENANT).isPresent());
    }

    @Test void aFreeholdIsNotPaidWhileARentedUnitCannotBeRead() {
        var rig = new Rig();
        rig.model.sourceFails = true;
        rig.model.rent(WORLD, UNIT, TENANT, MONTH).runAll();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        rig.model.sourceFails = false;
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty(), "the unit is rented");
        assertTrue(rig.read(UNIT, TENANT).isPresent());
    }

    @Test void aUnitThatIsRentedTwoLevelsDownStillBlocksTheFreehold() {
        var rig = new Rig();
        rig.model.addLease(WORLD, "room", TENANT, rig.model.dbNow().plus(MONTH), UNIT, 6, 6, 7, 7);
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
    }

    @Test void aUnitWhoseLeaseHasEndedDoesNotBlockTheFreehold() {
        var rig = new Rig();
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofMinutes(10)).runAll();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        rig.model.advance(Duration.ofMinutes(11));
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isPresent());
    }

    @Test void aFreeholdIsNotPaidWhenItsUnitsCannotBeListed() {
        var rig = new Rig();
        rig.view.childIndexFails = true;
        rig.backend.reset();
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        rig.view.childIndexFails = false;
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isPresent());
    }

    @Test void aTenantSetOnAVacantUnitBlocksTheFreeholdWhateverTheEndDate() {
        var rig = new Rig();
        assertTrue(rig.read(PLOT, OWNER).isPresent());
        rig.model.setTenant(WORLD, UNIT, TENANT).runAll();
        assertNull(rig.model.region(WORLD, UNIT).end, "set tenant leaves the dates empty");
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        rig.model.unsetTenant(WORLD, UNIT).runAll();
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isPresent());
    }

    @Test void aFreeholdStaysUnpaidThroughTheMarginAtTheEndOfAUnitsLease() {
        var rig = new Rig();
        rig.model.rent(WORLD, UNIT, TENANT, Duration.ofMinutes(10)).runAll();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        for (int i = 0; i < 9; i++) {
            rig.model.advance(Duration.ofMillis(RegionCache.Tuning.HOT_EVERY + 1000));
            rig.backend.tick();
            rig.view.pump();
        }
        assertTrue(rig.read(UNIT, TENANT).isEmpty(), "the tenant is not paid inside the margin");
        assertTrue(rig.read(PLOT, OWNER).isEmpty(), "the unit still has its tenant");
        rig.model.advance(Duration.ofMinutes(2));
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isPresent(), "the lease ended before the margin began");
    }

    @Test void aUnitRentedWithoutAnEventStopsTheFreeholdWithinOneHotBatch() {
        var rig = new Rig();
        Plan plan = rig.plan(PLOT, OWNER);
        rig.model.databaseOnlyRent(WORLD, UNIT, TENANT, MONTH).runAll();
        assertTrue(rig.debit(plan), "nothing has told the backend yet");
        rig.model.advance(Duration.ofMillis(RegionCache.Tuning.HOT_EVERY + 1000));
        rig.backend.tick();
        rig.view.pump();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
    }

    @Test void aUnitWithAnIdTooLongToCacheBlocksTheFreehold() {
        var rig = new Rig();
        String longId = "u".repeat(RegionCache.Tuning.MAX_ID + 1);
        rig.model.addLease(WORLD, longId, TENANT, rig.model.dbNow().plus(MONTH), PLOT, 12, 12, 14, 14);
        rig.subject.settle();
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty());
    }

    // Choosing a region

    @Test void aPointInARentedUnitBelongsToTheUnitAndItsTenant() {
        var rig = new Rig().rented();
        var cell = rig.subject.at(6, 6);
        assertEquals(UNIT, rig.backend.regionAt(cell).orElseThrow().regionId());
        assertTrue(rig.backend.ownerReadAt(cell, TENANT).isPresent());
        assertTrue(rig.backend.ownerReadAt(cell, OWNER).isEmpty());
    }

    @Test void aPointInAVacantUnitBelongsToNobodyAndTheRestOfThePlotStillPays() {
        var rig = new Rig();
        var cell = rig.subject.at(6, 6);
        assertEquals(UNIT, rig.backend.regionAt(cell).orElseThrow().regionId());
        assertTrue(rig.backend.ownerReadAt(cell, OWNER).isEmpty());
        assertTrue(rig.backend.ownerReadAt(rig.subject.at(1, 1), OWNER).isPresent());
    }

    @Test void aWorldGuardRegionRealtyDoesNotKnowIsIgnored() {
        var rig = new Rig();
        rig.model.defineGuard(WORLD, "foreign", null, 0, 0, 20, 20);
        assertEquals(PLOT, rig.backend.regionAt(rig.subject.at(1, 1)).orElseThrow().regionId());
        rig.model.defineGuard(WORLD, "foreign", PLOT, 0, 0, 3, 3);
        assertEquals(PLOT, rig.backend.regionAt(rig.subject.at(1, 1)).orElseThrow().regionId());
    }

    @Test void twoUnrelatedRegisteredRegionsThatOverlapGiveNothing() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "left", OWNER, null, 30, 30, 40, 40);
        rig.model.addFreehold(WORLD, "right", OWNER, null, 35, 35, 45, 45);
        rig.subject.settle();
        assertEquals("left", rig.backend.regionAt(rig.subject.at(31, 31)).orElseThrow().regionId());
        assertTrue(rig.backend.regionAt(rig.subject.at(36, 36)).isEmpty());
    }

    @Test void theInnermostOfThreeLevelsWins() {
        var rig = new Rig();
        rig.model.addLease(WORLD, "room", null, null, UNIT, 6, 6, 7, 7);
        rig.subject.settle();
        assertEquals("room", rig.backend.regionAt(rig.subject.at(6, 6)).orElseThrow().regionId());
        assertEquals(UNIT, rig.backend.regionAt(rig.subject.at(8, 8)).orElseThrow().regionId());
        assertEquals(PLOT, rig.backend.regionAt(rig.subject.at(15, 15)).orElseThrow().regionId());
    }

    @Test void theCanonicalSpellingIsUsedWhenWorldGuardSpellsTheIdAnotherWay() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "MyPlot", OWNER, null, 30, 30, 35, 35);
        rig.model.defineGuard(WORLD, "myplot", null, 30, 30, 35, 35).owners.add(OWNER);
        rig.subject.settle();
        var ref = rig.backend.regionAt(rig.subject.at(31, 31)).orElseThrow();
        assertEquals("MyPlot", ref.regionId());
        var token = rig.read("myplot", OWNER).orElseThrow();
        assertEquals("MyPlot", token.regionId());
        assertTrue(token.sameIncarnation(rig.read("MYPLOT", OWNER).orElseThrow()));
    }

    @Test void twoWorldsWithTheSameRegionIdStayApart() {
        var rig = new Rig();
        UUID second = new UUID(0, 68100), other = new UUID(0, 68101);
        rig.model.addWorld("second", second);
        rig.model.addFreehold(second, PLOT, other, null, 0, 0, 20, 20);
        rig.subject.settle();
        assertTrue(rig.backend.ownerReadRegion(second, PLOT, other).isPresent());
        assertTrue(rig.backend.ownerReadRegion(second, PLOT, OWNER).isEmpty());
        assertTrue(rig.backend.ownerReadRegion(WORLD, PLOT, other).isEmpty());
        assertEquals(second, rig.backend.findRegion("second", PLOT).orElseThrow().worldId());
        assertEquals(WORLD, rig.backend.findRegion(WORLD_NAME, PLOT).orElseThrow().worldId());
    }

    @Test void aWorldThatIsNotLoadedReadsNothing() {
        var rig = new Rig();
        rig.model.unloaded.add(WORLD);
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        assertTrue(rig.backend.regionAt(rig.subject.at(1, 1)).isEmpty());
        assertTrue(rig.read(PLOT, OWNER).isEmpty());
        assertEquals(Presence.UNREADABLE, rig.backend.observe(WORLD, PLOT));
    }

    @Test void renamingAWorldKeepsItsPlots() {
        var rig = new Rig();
        rig.model.worlds.remove(WORLD_NAME);
        rig.model.worlds.put("renamed", WORLD);
        assertTrue(rig.backend.findRegion("renamed", PLOT).isPresent());
        assertTrue(rig.backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        assertTrue(rig.read(PLOT, OWNER).isPresent());
    }

    // Regions added, removed and moved

    @Test void aRegionCreatedByAnEventIsKnownAtOnceAndReadableAfterItsReload() {
        var rig = new Rig();
        rig.model.addFreehold(WORLD, "fresh", BUYER, null, 30, 30, 35, 35);
        rig.model.fire("RegionCreatedEvent", WORLD, "fresh");
        assertEquals("fresh", rig.backend.regionAt(rig.subject.at(31, 31)).orElseThrow().regionId());
        assertTrue(rig.read("fresh", BUYER).isEmpty());
        rig.view.pump();
        assertTrue(rig.read("fresh", BUYER).isPresent());
    }

    @Test void aRegionRemovedFromRealtyReadsMissingAfterTheReload() {
        var rig = new Rig();
        rig.model.deleteRegion(WORLD, PLOT, true);
        rig.model.fire("RegionDeletedEvent", WORLD, PLOT);
        rig.view.pump();
        assertEquals(Presence.MISSING, rig.backend.observe(WORLD, PLOT));
        assertTrue(rig.backend.regionAt(rig.subject.at(1, 1)).isEmpty());
    }

    @Test void aMovedRegionEndsEarlierTokensAndReadsNormallyAgain() {
        var rig = new Rig();
        var before = rig.read(PLOT, OWNER).orElseThrow();
        var shape = rig.model.guard(WORLD, PLOT);
        shape.identity = new Object();
        shape.x2 = 25;
        rig.view.pump();
        rig.read(PLOT, OWNER);
        rig.view.pump();
        var after = rig.read(PLOT, OWNER).orElseThrow();
        assertFalse(before.sameIncarnation(after));
        assertEquals(PLOT, rig.backend.regionAt(rig.subject.at(24, 10)).orElseThrow().regionId());
    }

    @Test void aConsoleDeleteAndRegisterGivesANewTokenOnlyAfterTheNextLoad() {
        // This window stays open: no event fires and WorldGuard keeps its region, so nothing shows the change until a load.
        var rig = new Rig();
        var before = rig.read(PLOT, OWNER).orElseThrow();
        rig.model.deleteRegion(WORLD, PLOT, false);
        rig.model.reregister(WORLD, PLOT, OWNER);
        assertTrue(before.sameIncarnation(rig.read(PLOT, OWNER).orElseThrow()));
        rig.subject.settle();
        assertFalse(before.sameIncarnation(rig.read(PLOT, OWNER).orElseThrow()));
    }
}
