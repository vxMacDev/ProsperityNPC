package com.prosperitysmp.npc.training;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic Bukkit events with the inspected Jobs signatures, not native Jobs runtime verification. */
class TrainingEventsTest {
    static final Instant AT = Instant.parse("2026-09-29T12:00:00Z");
    static final UUID PLAYER = UUID.randomUUID();

    @Test void policyNormalizesJobsAndBoundsAllBenefits() {
        assertEquals("woodcutter", grant(PLAYER, "Woodcutter").jobName());
        for (String invalid : new String[] { "", "bad job", "miner;op", "x".repeat(49) }) {
            assertThrows(IllegalArgumentException.class, () -> grant(PLAYER, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new TrainingGrant(UUID.randomUUID(), PLAYER, UUID.randomUUID(),
                "miner", UUID.randomUUID(), AT, LocalDate.of(2026, 9, 29), AT.plusSeconds(1801), 500, 1000));
        assertThrows(IllegalArgumentException.class, () -> new TrainingGrant(UUID.randomUUID(), PLAYER, UUID.randomUUID(),
                "miner", UUID.randomUUID(), AT, LocalDate.of(2026, 9, 29), AT.plusSeconds(1800), 501, 1000));
        assertFalse(grant(PLAYER, "miner").activeAt(AT.minusMillis(1)));
        assertFalse(grant(PLAYER, "miner").activeAt(AT.plusSeconds(1800)));
    }

    @Test void separateMoneyAndXpSeamsStackWithoutDoubleXp() throws Exception {
        var grant = grant(PLAYER, "miner");
        Payment money = new Payment(PLAYER, "Miner", 100 * 1.25 * 1.15, 50);
        assertTrue(binding(false).apply(money, AT, id -> Optional.of(grant), (id, job) -> true));
        assertEquals(100 * 1.25 * 1.15 * 1.05, money.amount, .000001);
        assertEquals(50, money.exp, "PrePayment must leave XP alone");
        money.amount *= .96; // Existing HIGH-priority tax, after NORMAL training.
        assertEquals(100 * 1.25 * 1.15 * 1.05 * .96, money.amount, .000001);
        Experience xp = new Experience(PLAYER, "Miner", 50 * 1.5 * 1.1);
        assertTrue(binding(true).apply(xp, AT, id -> Optional.of(grant), (id, job) -> true));
        assertEquals(50 * 1.5 * 1.1 * 1.1, xp.exp, .000001);
    }

    @Test void cancelledAsyncForeignJobAccountAndLostEligibilityNeverChangeValues() throws Exception {
        var link = binding(false);
        var grant = grant(PLAYER, "miner");
        Payment cancelled = new Payment(PLAYER, "miner", 10, 20); cancelled.setCancelled(true);
        assertFalse(link.apply(cancelled, AT, id -> Optional.of(grant), (id, job) -> true));
        Payment async = new Payment(PLAYER, "miner", 10, 20, true);
        assertFalse(link.apply(async, AT, id -> Optional.of(grant), (id, job) -> true));
        for (Payment event : new Payment[] { new Payment(UUID.randomUUID(), "miner", 10, 20),
                new Payment(PLAYER, "farmer", 10, 20) }) {
            assertFalse(link.apply(event, AT, id -> Optional.of(grant), (id, job) -> true));
            assertEquals(10, event.amount);
        }
        Payment denied = new Payment(PLAYER, "miner", 10, 20);
        assertFalse(link.apply(denied, AT, id -> Optional.of(grant), (id, job) -> false));
        assertFalse(link.apply(denied, AT, id -> Optional.empty(), (id, job) -> true));
        assertEquals(10, cancelled.amount); assertEquals(10, async.amount); assertEquals(10, denied.amount);
    }

    @Test void expiryAndTimeRegressionNeverAward() throws Exception {
        for (Instant now : new Instant[] { AT.minusMillis(1), AT.plusSeconds(1800), AT.plusSeconds(86400) }) {
            Payment event = new Payment(PLAYER, "miner", 10, 20);
            assertFalse(binding(false).apply(event, now, id -> Optional.of(grant(PLAYER, "miner")), (id, job) -> true));
            assertEquals(10, event.amount);
        }
    }

    @Test void zeroNegativeNonfiniteAndOverflowAreNotInstantAwards() throws Exception {
        for (double value : new double[] { 0, -5, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE }) {
            Payment event = new Payment(PLAYER, "miner", value, 20);
            assertFalse(binding(false).apply(event, AT, id -> Optional.of(grant(PLAYER, "miner")), (id, job) -> true));
            assertEquals(Double.doubleToLongBits(value), Double.doubleToLongBits(event.amount));
        }
    }

    @Test void incompatibleEventSignatureFailsClosed() {
        assertThrows(NoSuchMethodException.class,
                () -> TrainingJobsAdapter.EventBinding.link(WrongPayment.class, Job.class, false));
    }

    static TrainingJobsAdapter.EventBinding binding(boolean xp) throws Exception {
        return TrainingJobsAdapter.EventBinding.link(xp ? Experience.class : Payment.class, Job.class, xp);
    }

    static TrainingGrant grant(UUID player, String job) {
        return new TrainingGrant(UUID.randomUUID(), player, UUID.randomUUID(), job, UUID.randomUUID(), AT,
                LocalDate.of(2026, 9, 29), AT.plusSeconds(1800), 500, 1000);
    }

    public static final class Job {
        private final String name;
        Job(String name) { this.name = name; }
        public String getName() { return name; }
    }

    static OfflinePlayer offline(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[] { OfflinePlayer.class },
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
    }

    public static class Payment extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final OfflinePlayer player;
        private final Job job;
        double amount;
        double exp;
        private boolean cancelled;
        Payment(UUID id, String job, double amount, double exp) { this(id, job, amount, exp, false); }
        Payment(UUID id, String job, double amount, double exp, boolean async) {
            super(async); this.player = offline(id); this.job = new Job(job); this.amount = amount; this.exp = exp;
        }
        public OfflinePlayer getPlayer() { return player; }
        public Job getJob() { return job; }
        public double getAmount() { return amount; }
        public void setAmount(double value) { amount = value; }
        public double getExp() { return exp; }
        public void setExp(double value) { exp = value; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class Experience extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final OfflinePlayer player;
        private final Job job;
        double exp;
        private boolean cancelled;
        Experience(UUID id, String job, double exp) { player = offline(id); this.job = new Job(job); this.exp = exp; }
        public OfflinePlayer getPlayer() { return player; }
        public Job getJob() { return job; }
        public double getExp() { return exp; }
        public void setExp(double value) { exp = value; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean value) { cancelled = value; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class WrongPayment extends Event implements Cancellable {
        public OfflinePlayer getPlayer() { return offline(PLAYER); }
        public Job getJob() { return new Job("miner"); }
        public Double getAmount() { return 10.0; }
        public void setAmount(double value) { }
        @Override public boolean isCancelled() { return false; }
        @Override public void setCancelled(boolean value) { }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }
}
