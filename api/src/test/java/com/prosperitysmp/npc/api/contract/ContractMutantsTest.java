package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.api.contract.broken.MoneySourceMutants;
import com.prosperitysmp.npc.api.contract.broken.PropertyBackendMutants;
import com.prosperitysmp.npc.api.ApiTestSupport;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Each deliberately broken subject breaks exactly the one thing it claims to, and forwards everything else. */
class ContractMutantsTest {
    private static final UUID WORLD = new UUID(10, 10);
    private static final UUID OWNER_A = new UUID(11, 11);
    private static final UUID OWNER_B = new UUID(12, 12);

    // ---- money ----------------------------------------------------------------------------------------------

    private static class FakeMoney implements MoneySourceContract.Subject {
        final List<String> money = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final AtomicInteger replaced = new AtomicInteger(), pluginOff = new AtomicInteger(), providerOff = new AtomicInteger();
        final OfflinePlayer account = (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> {
                    throw new AssertionError("the account is only handed around: " + m.getName());
                });
        double balance = 0.2899995;
        boolean current = false, enabled = false, legSucceeds = true, hasPin = true;
        RuntimeException legThrows;

        @Override public MoneySource source() {
            return new MoneySource() {
                @Override public String id() { return "fake-money"; }
                @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
                @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
                @Override public Capabilities capabilities() {
                    return Capabilities.builder().value("scale", "2").flag("artifact-hashable", true).build();
                }
                @Override public Optional<Pin> pin() { return hasPin ? Optional.of(new FakePin()) : Optional.empty(); }
            };
        }

        private final class FakePin implements MoneySource.Pin {
            @Override public boolean stillCurrent() { return current; }
            @Override public boolean enabled() { return enabled; }
            @Override public Path artifact() { return providerArtifact(); }
            @Override public double balanceDollars(OfflinePlayer who) { calls.add("balance"); return balance; }
            @Override public LegResponse leg(OfflinePlayer who, long cents, MoneySource.Leg leg) {
                calls.add("leg");
                money.add((leg == MoneySource.Leg.DEBIT ? "withdraw " : "deposit ") + (cents / 100.0));
                if (legThrows != null) throw legThrows;
                return LegResponse.of(legSucceeds, cents / 100.0);
            }
        }

        @Override public OfflinePlayer account() { return account; }
        @Override public List<String> moneyCalls() { return money; }
        @Override public List<String> allCalls() { return calls; }
        @Override public void replaceRegistration() { replaced.incrementAndGet(); }
        @Override public void disableOwnerPlugin() { pluginOff.incrementAndGet(); }
        @Override public void disableProvider() { providerOff.incrementAndGet(); }
        @Override public Path providerArtifact() { return Path.of("provider.jar"); }
        @Override public Path apiArtifact() { return Path.of("vault-api.jar"); }
    }

    private static MoneySourceContract.Subject broken(MoneySourceMutants mutant, FakeMoney good) {
        return (MoneySourceContract.Subject) mutant.mutate(good);
    }

    private static MoneySource.Pin pinOf(MoneySourceContract.Subject subject) {
        return subject.source().pin().orElseThrow();
    }

    @Test void theScaleMutantReportsScaleThreeAndStillClaimsAHashableArtifact() {
        var caps = broken(MoneySourceMutants.SCALE_IGNORED, new FakeMoney()).source().capabilities();
        assertEquals("3", caps.value("scale"));
        assertTrue(caps.flag("artifact-hashable"));
    }

    @Test void theRoundingMutantRoundsTheBalanceToCents() {
        var good = new FakeMoney();
        var subject = broken(MoneySourceMutants.ROUNDS_BALANCE, good);
        assertEquals(0.29, pinOf(subject).balanceDollars(subject.account()));
        assertEquals(List.of(), good.money, "a read still moves no money");
    }

    @Test void theDepositBackMutantDepositsAfterAFailedWithdraw() {
        var good = new FakeMoney();
        good.legSucceeds = false;
        var subject = broken(MoneySourceMutants.DEPOSITS_BACK, good);
        pinOf(subject).leg(subject.account(), 500, MoneySource.Leg.DEBIT);
        assertEquals(List.of("withdraw 5.0", "deposit 5.0"), good.money);
    }

    @Test void theDepositBackMutantLeavesASuccessfulWithdrawAlone() {
        var good = new FakeMoney();
        var subject = broken(MoneySourceMutants.DEPOSITS_BACK, good);
        pinOf(subject).leg(subject.account(), 500, MoneySource.Leg.DEBIT);
        assertEquals(List.of("withdraw 5.0"), good.money);
    }

    @Test void theDepositBackMutantLeavesAFailedDepositAlone() {
        var good = new FakeMoney();
        good.legSucceeds = false;
        var subject = broken(MoneySourceMutants.DEPOSITS_BACK, good);
        pinOf(subject).leg(subject.account(), 500, MoneySource.Leg.CREDIT);
        assertEquals(List.of("deposit 5.0"), good.money);
    }

    @Test void theDepositBackMutantAlsoPutsMoneyBackWhenTheProviderAnswersNothing() {
        var good = new FakeMoney() {
            @Override public MoneySource source() {
                var inner = super.source();
                return new MoneySource() {
                    @Override public String id() { return inner.id(); }
                    @Override public int apiMajor() { return inner.apiMajor(); }
                    @Override public int apiMinor() { return inner.apiMinor(); }
                    @Override public Capabilities capabilities() { return inner.capabilities(); }
                    @Override public Optional<Pin> pin() {
                        return Optional.of(new Pin() {
                            @Override public boolean stillCurrent() { return true; }
                            @Override public boolean enabled() { return true; }
                            @Override public Path artifact() { return providerArtifact(); }
                            @Override public double balanceDollars(OfflinePlayer who) { return 0; }
                            @Override public LegResponse leg(OfflinePlayer who, long cents, MoneySource.Leg leg) {
                                money.add((leg == MoneySource.Leg.DEBIT ? "withdraw " : "deposit ") + (cents / 100.0));
                                return null;
                            }
                        });
                    }
                };
            }
        };
        var subject = broken(MoneySourceMutants.DEPOSITS_BACK, good);
        assertNull(pinOf(subject).leg(subject.account(), 250, MoneySource.Leg.DEBIT));
        assertEquals(List.of("withdraw 2.5", "deposit 2.5"), good.money);
    }

    @Test void theSwallowingMutantTurnsAProviderThrowIntoAFailedLeg() {
        var good = new FakeMoney();
        good.legThrows = new IllegalStateException("provider diagnostic");
        var subject = broken(MoneySourceMutants.SWALLOWS_THROW, good);
        LegResponse response = pinOf(subject).leg(subject.account(), 500, MoneySource.Leg.DEBIT);
        assertFalse(response.success());
        assertEquals(0.0, response.amountDollars());
        assertSame(good.legThrows, assertThrows(IllegalStateException.class,
                () -> pinOf(good).leg(good.account(), 500, MoneySource.Leg.DEBIT)), "the working pin still throws");
    }

    @Test void theIdentityMutantAlwaysSaysTheRegistrationIsCurrentAndEnabled() {
        var good = new FakeMoney();
        var subject = broken(MoneySourceMutants.IDENTITY_ONLY, good);
        assertTrue(pinOf(subject).stillCurrent());
        assertTrue(pinOf(subject).enabled());
        assertFalse(pinOf(good).stillCurrent());
        assertFalse(pinOf(good).enabled());
    }

    @Test void theApiJarMutantHashesTheMoneyApiNotTheProvider() {
        var good = new FakeMoney();
        var subject = broken(MoneySourceMutants.HASHES_API_JAR, good);
        assertEquals(Path.of("vault-api.jar"), pinOf(subject).artifact());
        assertEquals(Path.of("provider.jar"), pinOf(good).artifact());
    }

    @ParameterizedTest
    @EnumSource(MoneySourceMutants.class)
    void everyMoneyMutantKeepsTheOtherPinBehaviourOfTheWorkingSubject(MoneySourceMutants mutant) {
        var good = new FakeMoney();
        good.balance = 1234.5;
        var subject = broken(mutant, good);
        assertSame(good.account(), subject.account());
        assertEquals(Path.of("provider.jar"), subject.providerArtifact());
        assertEquals(Path.of("vault-api.jar"), subject.apiArtifact());
        assertEquals("fake-money", subject.source().id());
        assertEquals(1, subject.source().apiMajor());
        assertEquals(2, subject.source().apiMinor());
        assertEquals(1234.5, pinOf(subject).balanceDollars(subject.account()));
    }

    @ParameterizedTest
    @EnumSource(MoneySourceMutants.class)
    void everyMoneyMutantSharesTheCallLogsAndTheRegistrationControls(MoneySourceMutants mutant) {
        var good = new FakeMoney();
        var subject = broken(mutant, good);
        assertSame(good.money, subject.moneyCalls());
        assertSame(good.calls, subject.allCalls());
        subject.replaceRegistration();
        subject.disableOwnerPlugin();
        subject.disableProvider();
        subject.disableProvider();
        assertEquals(1, good.replaced.get());
        assertEquals(1, good.pluginOff.get());
        assertEquals(2, good.providerOff.get());
    }

    @ParameterizedTest
    @EnumSource(MoneySourceMutants.class)
    void aMoneyMutantOverASourceWithoutAPinHasNoPinEither(MoneySourceMutants mutant) {
        var good = new FakeMoney();
        good.hasPin = false;
        assertEquals(Optional.empty(), broken(mutant, good).source().pin());
    }

    @ParameterizedTest
    @EnumSource(MoneySourceMutants.class)
    void aMoneyMutantLeavesAnythingThatIsNotAMoneySubjectAlone(MoneySourceMutants mutant) {
        Object other = "not a subject";
        assertSame(other, mutant.mutate(other));
        assertNull(mutant.mutate(null));
    }

    // ---- property -------------------------------------------------------------------------------------------

    private record FakeToken(UUID worldId, String regionId, UUID ownerId, Object incarnation) implements OwnershipToken {
        @Override public boolean sameIncarnation(OwnershipToken other) {
            return other instanceof FakeToken t && worldId.equals(t.worldId) && regionId.equals(t.regionId)
                    && ownerId.equals(t.ownerId) && incarnation == t.incarnation;
        }
    }

    private static final class FakeBackend implements PropertyBackend {
        UUID owner = OWNER_A;
        Object incarnation = new Object();
        Presence presence = Presence.PRESENT;

        @Override public String id() { return "fake-property"; }
        @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
        @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
        @Override public Capabilities capabilities() { return Capabilities.builder().value("guard", "veto-events").build(); }
        @Override public String displayName() { return "Fake"; }
        @Override public boolean available() { return true; }
        @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.installed(GuardMode.VETO_EVENTS); }
        @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
        @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
        @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID who) {
            return who.equals(owner) ? Optional.of(new FakeToken(WORLD, "r1", who, incarnation)) : Optional.empty();
        }
        @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID who) {
            return who.equals(owner) ? Optional.of(new FakeToken(world, regionId, who, incarnation)) : Optional.empty();
        }
        @Override public Presence observe(UUID world, String regionId) { return presence; }
        @Override public List<String> describe() { return List.of("fake"); }
    }

    /** A fake vendor flow: a held region vetoes an ordinary change; an applied change bumps the region and "child". */
    private static final class FakeControls implements PropertyBackendContract.GuardControls {
        OwnershipHooks hooks;
        int uninstalled, recreated, linkageBroken;

        @Override public GuardResult install(OwnershipHooks given) {
            hooks = given;
            return GuardResult.installed(GuardMode.VETO_EVENTS);
        }
        @Override public void uninstall() { uninstalled++; }
        @Override public boolean changeOwner(String region, UUID owner, String reason) {
            boolean[] applied = {false};
            hooks.guarded("ChangeEvent", () -> {
                if (hooks.isHeld(WORLD, region) && !neverCancelled().contains(reason)) return;
                hooks.externalChange(WORLD, region);
                hooks.externalChange(WORLD, "child");
                applied[0] = true;
            });
            return applied[0];
        }
        @Override public void changeOwnerCancelledElsewhere(String region, UUID owner) { }
        @Override public List<String> neverCancelled() { return List.of("admin"); }
        @Override public boolean eventsOffMain() { return true; }
        @Override public boolean supportsOverride() { return true; }
        @Override public void changeOwnerOverridden(String region, UUID owner) { hooks.externalChange(WORLD, region); }
        @Override public List<String> affectedWith(String region) { return List.of("child"); }
        @Override public void recreate(String region, UUID owner) { recreated++; }
        @Override public void breakLinkage() { linkageBroken++; }
    }

    private static final class FakeProperty implements PropertyBackendContract.Subject {
        final FakeBackend backend = new FakeBackend();
        final FakeControls controls;
        FakeProperty(boolean withGuard) { controls = withGuard ? new FakeControls() : null; }

        @Override public PropertyBackend backend() { return backend; }
        @Override public boolean knowsRegions() { return true; }
        @Override public UUID world() { return WORLD; }
        @Override public String region() { return "r1"; }
        @Override public UUID owner() { return OWNER_A; }
        @Override public List<Location> cellsOf(String region) { return List.of(); }
        @Override public Location outside() { return new Location(null, 0, 64, 0); }
        @Override public void setOwner(String region, UUID owner) { backend.owner = owner; }
        @Override public void addMember(String region, UUID member) { }
        @Override public void unrelatedEdit(String region) { }
        @Override public void removeRegion(String region) { }
        @Override public void makeUnreadable() { }
        @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
        @Override public PropertyBackendContract.GuardControls guard() { return controls; }
    }

    private static PropertyBackendContract.Subject broken(PropertyBackendMutants mutant, FakeProperty good) {
        return (PropertyBackendContract.Subject) mutant.mutate(good);
    }

    private static OwnershipToken read(PropertyBackendContract.Subject subject, UUID owner) {
        return subject.backend().ownerReadRegion(WORLD, "r1", owner).orElseThrow();
    }

    @Test void theWorkingBackendSeesANewOwnerAndANewIncarnationAsNewTokens() {
        var good = new FakeProperty(false);
        var first = read(good, OWNER_A);
        good.setOwner("r1", OWNER_B);
        assertFalse(first.sameIncarnation(read(good, OWNER_B)));
        good.setOwner("r1", OWNER_A);
        assertTrue(first.sameIncarnation(read(good, OWNER_A)));
        good.backend.incarnation = new Object();
        assertFalse(first.sameIncarnation(read(good, OWNER_A)));
    }

    @Test void theOwnerBlindMutantCannotTellTwoOwnersApart() {
        var good = new FakeProperty(false);
        var subject = broken(PropertyBackendMutants.TOKEN_IGNORES_OWNER, good);
        var first = read(subject, OWNER_A);
        good.setOwner("r1", OWNER_B);
        assertTrue(first.sameIncarnation(read(subject, OWNER_B)));
    }

    @Test void theIncarnationBlindMutantStillSeesAnotherOwnerButNotANewRegionObject() {
        var good = new FakeProperty(false);
        var subject = broken(PropertyBackendMutants.TOKEN_DROPS_INCARNATION, good);
        var first = read(subject, OWNER_A);
        good.backend.incarnation = new Object();
        assertTrue(first.sameIncarnation(read(subject, OWNER_A)), "a recreated region looks the same");
        good.setOwner("r1", OWNER_B);
        assertFalse(first.sameIncarnation(read(subject, OWNER_B)), "another owner is still told apart");
    }

    @Test void theLocationMutantBlindsOnlyTheTokenReadAtALocation() {
        var good = new FakeProperty(false);
        var subject = broken(PropertyBackendMutants.LOCATION_TOKEN_IGNORES_OWNER, good);
        Location cell = new Location(null, 1, 64, 1);
        var atA = subject.backend().ownerReadAt(cell, OWNER_A).orElseThrow();
        var regionA = read(subject, OWNER_A);
        good.setOwner("r1", OWNER_B);
        assertTrue(atA.sameIncarnation(subject.backend().ownerReadAt(cell, OWNER_B).orElseThrow()));
        assertFalse(regionA.sameIncarnation(read(subject, OWNER_B)));
    }

    @Test void theMissingMutantReportsMissingAsPresentAndNothingElseDifferently() {
        var good = new FakeProperty(false);
        var subject = broken(PropertyBackendMutants.MISSING_AS_PRESENT, good);
        good.backend.presence = Presence.MISSING;
        assertEquals(Presence.PRESENT, subject.backend().observe(WORLD, "r1"));
        good.backend.presence = Presence.UNREADABLE;
        assertEquals(Presence.UNREADABLE, subject.backend().observe(WORLD, "r1"));
        good.backend.presence = Presence.PRESENT;
        assertEquals(Presence.PRESENT, subject.backend().observe(WORLD, "r1"));
    }

    @ParameterizedTest
    @EnumSource(PropertyBackendMutants.class)
    void aSubjectWithoutAGuardHasNoGuardControlsUnderAnyPropertyMutant(PropertyBackendMutants mutant) {
        assertNull(broken(mutant, new FakeProperty(false)).guard());
    }

    @ParameterizedTest
    @EnumSource(PropertyBackendMutants.class)
    void everyPropertyMutantForwardsTheSubjectAndTheBackendIdentity(PropertyBackendMutants mutant) {
        var good = new FakeProperty(true);
        var subject = broken(mutant, good);
        assertEquals(WORLD, subject.world());
        assertEquals("r1", subject.region());
        assertEquals(OWNER_A, subject.owner());
        assertTrue(subject.knowsRegions());
        assertEquals("fake-property", subject.backend().id());
        assertEquals("Fake", subject.backend().displayName());
        assertEquals(List.of("fake"), subject.backend().describe());
        assertEquals(GuardMode.VETO_EVENTS, subject.backend().installGuard(new RecordingHooks()).mode().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(PropertyBackendMutants.class)
    void everyPropertyMutantForwardsTheGuardControlsItDoesNotBreak(PropertyBackendMutants mutant) {
        var good = new FakeProperty(true);
        var controls = broken(mutant, good).guard();
        assertEquals(List.of("admin"), controls.neverCancelled());
        assertEquals(List.of("child"), controls.affectedWith("r1"));
        assertTrue(controls.eventsOffMain());
        assertTrue(controls.supportsOverride());
        controls.uninstall();
        controls.recreate("r1", OWNER_B);
        controls.breakLinkage();
        assertEquals(1, good.controls.uninstalled);
        assertEquals(1, good.controls.recreated);
        assertEquals(1, good.controls.linkageBroken);
    }

    @ParameterizedTest
    @EnumSource(PropertyBackendMutants.class)
    void aPropertyMutantLeavesAnythingThatIsNotASubjectAlone(PropertyBackendMutants mutant) {
        Object other = new Object();
        assertSame(other, mutant.mutate(other));
        assertNull(mutant.mutate(null));
    }

    private record Guarded(RecordingHooks real, PropertyBackendContract.GuardControls controls) { }

    private static Guarded install(PropertyBackendMutants mutant) {
        var good = new FakeProperty(true);
        var controls = broken(mutant, good).guard();
        var real = new RecordingHooks();
        assertTrue(controls.install(real).isInstalled());
        return new Guarded(real, controls);
    }

    @Test void withNoBrokenHooksAHeldRegionVetoesAndAnAppliedChangeBumpsBothRegions() {
        var guard = install(PropertyBackendMutants.TOKEN_IGNORES_OWNER);
        guard.real().hold(WORLD, "r1");
        assertFalse(guard.controls().changeOwner("r1", OWNER_B, "default"));
        assertEquals(List.of(), guard.real().bumps());
        guard.real().release(WORLD, "r1");
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"));
        assertEquals(List.of(RecordingHooks.key(WORLD, "r1"), RecordingHooks.key(WORLD, "child")), guard.real().bumps());
    }

    @Test void theNoBumpMutantAppliesTheChangeButTellsNobody() {
        var guard = install(PropertyBackendMutants.NO_BUMP);
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"));
        assertEquals(List.of(), guard.real().bumps());
    }

    @Test void theNeverHeldMutantLetsAHeldRegionBeChanged() {
        var guard = install(PropertyBackendMutants.VETO_NEVER_APPLIED);
        guard.real().hold(WORLD, "r1");
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"), "the held region was not vetoed");
    }

    @Test void theFirstKeyMutantBumpsOnlyTheFirstRegionOfOneChange() {
        var guard = install(PropertyBackendMutants.BUMPS_FIRST_KEY_ONLY);
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"));
        assertEquals(List.of(RecordingHooks.key(WORLD, "r1")), guard.real().bumps());
        guard.real().clearBumps();
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"));
        assertEquals(List.of(RecordingHooks.key(WORLD, "r1")), guard.real().bumps(), "each change starts a new first key");
    }

    @Test void theCancelsNeverCancelledMutantVetoesAReasonThatMustGoThrough() {
        var working = install(PropertyBackendMutants.TOKEN_IGNORES_OWNER);
        working.real().hold(WORLD, "r1");
        assertTrue(working.controls().changeOwner("r1", OWNER_B, "admin"), "the working flow lets the reason through");

        var guard = install(PropertyBackendMutants.CANCELS_NEVER_CANCELLED);
        guard.real().hold(WORLD, "r1");
        assertFalse(guard.controls().changeOwner("r1", OWNER_B, "admin"));
        assertEquals(List.of(), guard.real().bumps());
        guard.real().release(WORLD, "r1");
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "admin"), "a region that is not held is changed");
    }

    @Test void theErrorSwallowedMutantHidesAFailureFromTheHooks() {
        var working = new FakeProperty(true);
        var workingHooks = new RecordingHooks();
        broken(PropertyBackendMutants.TOKEN_IGNORES_OWNER, working).guard().install(workingHooks);
        working.controls.hooks.guarded("ClaimChangeEvent", () -> { throw new NoClassDefFoundError("vendor"); });
        assertEquals(List.of("ClaimChangeEvent: NoClassDefFoundError"), workingHooks.failures());

        var swallowing = new FakeProperty(true);
        var hooks = new RecordingHooks();
        broken(PropertyBackendMutants.ERROR_SWALLOWED, swallowing).guard().install(hooks);
        swallowing.controls.hooks.guarded("ClaimChangeEvent", () -> { throw new NoClassDefFoundError("vendor"); });
        assertEquals(List.of(), hooks.failures());
    }

    @Test void theMissingPostHandlerMutantDropsTheBumpOfAnOverriddenChange() {
        var kept = install(PropertyBackendMutants.TOKEN_IGNORES_OWNER);
        kept.controls().changeOwnerOverridden("r1", OWNER_B);
        assertEquals(1, kept.real().bumpsOf(WORLD, "r1"));
        var guard = install(PropertyBackendMutants.POST_HANDLER_MISSING);
        guard.controls().changeOwnerOverridden("r1", OWNER_B);
        assertEquals(0, guard.real().bumpsOf(WORLD, "r1"));
        assertTrue(guard.controls().changeOwner("r1", OWNER_B, "default"), "only the overridden flow is muted");
        assertEquals(1, guard.real().bumpsOf(WORLD, "r1"));
    }

    @Test void theExternalChangeMutantCallsExternalChangeWhereMarkDirtyWasMeant() {
        var kept = new FakeProperty(true);
        var keptReal = new RecordingHooks();
        broken(PropertyBackendMutants.TOKEN_IGNORES_OWNER, kept).guard().install(keptReal);
        ApiTestSupport.onAnotherThread(() -> kept.controls.hooks.markDirty(WORLD, "r1"));
        assertEquals(List.of(), keptReal.offMainMisuse());

        var other = new FakeProperty(true);
        var real = new RecordingHooks();
        broken(PropertyBackendMutants.EXTERNAL_CHANGE_OFF_MAIN, other).guard().install(real);
        ApiTestSupport.onAnotherThread(() -> other.controls.hooks.markDirty(WORLD, "r1"));
        assertEquals(List.of("externalChange"), real.offMainMisuse());
        assertEquals(1, real.bumpsOf(WORLD, "r1"));
    }
}
