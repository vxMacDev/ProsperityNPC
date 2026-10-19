package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.api.charge.UsageMeter;
import com.prosperitysmp.npc.charge.BillingReadBatch.Read;
import com.prosperitysmp.npc.charge.meter.MeterRead;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.economy.BillPayments;
import com.prosperitysmp.npc.economy.ReserveAccount;
import com.prosperitysmp.npc.pieces.PieceSelector;
import com.prosperitysmp.npc.pieces.Pieces;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configured bills and taxes (dev.65): the catalogue, the daily issue step (phase 5 of the daily cycle), automatic
 * collection behind its own switch, the unpaid-invoice cache that {@code on-unpaid: hold-rent} reads, and the staff
 * and owner views.
 *
 * <p>With no {@code bills:} block (the default) nothing here runs: the daily cycle skips phase 5, the rent batch
 * never reads the cache, no SQL is issued and no line is logged. Every money movement goes through the utility
 * payment runtime's journaled transfer, paid by the home owner to the reserve.
 */
public final class BillingRuntime implements AutoCloseable {
    /** A still-ISSUED invoice, as the hold-rent check needs it: the home, the ownership it was issued to and its days. */
    public record Open(UUID propertyId, UUID ownerId, int ownershipEpoch, LocalDate day, LocalDate payableUntil) { }

    private final ProsperityNpcPlugin plugin;
    private final EconomyClock days;
    private final ChargeInvoiceRepository invoices;
    private final ThreadPoolExecutor worker;
    private volatile ChargeCatalogue.Catalogue catalogue = ChargeCatalogue.Catalogue.DISABLED;
    private volatile List<ChargeInvoiceRepository.Period> periods = List.of();
    private volatile String rejected;
    private volatile List<Open> open;
    private volatile String lastRun = "not run yet";
    private boolean busy;
    private volatile long catalogueGeneration;
    // False until the first catalogue load has finished (loaded, refused or absent). Until then hold-rent can not
    // know which homes to hold, so rent waits when the config asks for hold-rent.
    private volatile boolean loaded;
    private BukkitTask readTask;
    private volatile boolean closed;

    public BillingRuntime(ProsperityNpcPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        Clock clock = Clock.systemUTC();
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago"));
        int reset = plugin.getConfig().getInt("clock.daily-reset-hour", 4);
        days = new EconomyClock(clock, zone, reset);
        UUID reserve = ReserveAccount.fromConfig(plugin.getConfig());
        invoices = new ChargeInvoiceRepository(plugin.database(), new BillPayments(plugin.database(), reserve, clock),
                plugin.accountLinks(), plugin::linkedAccountsEnabled, clock);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), r -> {
            Thread t = new Thread(r, "npc-bills");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(worker);
    }

    // ---- Catalogue ----------------------------------------------------------------------------------------------

    /**
     * Reads the {@code bills:} block. Absent: bills stay off, with no SQL and no log line. A bad block at startup
     * leaves bills off and logs the reason; at reload it keeps the previous catalogue and tells the sender. A new or
     * changed policy takes effect on the next economy day.
     */
    public void loadCatalogue(boolean startup, Consumer<String> sender) {
        if (closed) return;
        long generation = ++catalogueGeneration;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("bills");
        Map<String, Object> raw = section == null ? Map.of() : ChargeCatalogue.plain(section);
        if (raw.isEmpty()) {
            boolean wasOn = catalogue.enabled();
            catalogue = ChargeCatalogue.Catalogue.DISABLED;
            periods = List.of();
            rejected = null;
            loaded = true;
            if (wasOn) sender.accept("Bills: no bills block; configured bills are off.");
            return;
        }
        if (Boolean.FALSE.equals(raw.get("enabled"))) {
            catalogue = ChargeCatalogue.Catalogue.DISABLED;
            periods = List.of();
        }
        LocalDate today = days.today();
        final CompletableFuture<Loaded> loading;
        try {
            loading = CompletableFuture.supplyAsync(() -> readCatalogue(raw, today, generation), worker);
        } catch (RuntimeException unavailable) {
            rejectCatalogue(startup, "catalogue worker is unavailable", sender);
            return;
        }
        loading.whenComplete((result, failure) -> post(() -> {
            if (closed || generation != catalogueGeneration) return;
            String reason = failure != null ? "catalogue could not be read" : result.reason();
            if (reason != null) {
                rejectCatalogue(startup, reason, sender);
                return;
            }
            catalogue = result.catalogue();
            periods = List.copyOf(result.periods());
            rejected = null;
            if (result.open() != null) open = List.copyOf(result.open());
            loaded = true;
            if (catalogue.enabled()) {
                plugin.getLogger().info("bills: " + catalogue.policies().size() + " policies loaded; new or changed "
                        + "policies take effect on the next economy day.");
                for (var p : catalogue.policies()) if (!p.bindsAt().isEmpty())
                    plugin.getLogger().info("bills: " + p.id() + " reaches its maximum at " + p.bindsAt());
            }
            sender.accept(catalogue.enabled() ? "Bills: catalogue loaded (" + catalogue.policies().size()
                    + " policies), staged for the next economy day." : "Bills: block present but bills.enabled is off.");
        }));
    }

    private Loaded readCatalogue(Map<String, Object> raw, LocalDate today, long generation) {
        try {
            var loaded = ChargeCatalogue.load(raw, invoices.recordedPolicies());
            if (loaded.enabled()) invoices.record(loaded, today, today.plusDays(1),
                    () -> !closed && generation == catalogueGeneration);
            var from = loaded.enabled() ? invoices.policyPeriods(today) : List.<ChargeInvoiceRepository.Period>of();
            List<Open> rows = loaded.enabled() && loaded.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT
                    ? invoices.openInvoices() : null;
            return new Loaded(loaded, from, rows, null);
        } catch (ChargeCatalogue.Rejected bad) {
            return new Loaded(null, null, null, bad.getMessage());
        } catch (Exception failure) {
            return new Loaded(null, null, null, "catalogue could not be recorded (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private void rejectCatalogue(boolean startup, String reason, Consumer<String> sender) {
        rejected = reason;
        if (startup) {
            catalogue = ChargeCatalogue.Catalogue.DISABLED;
            periods = List.of();
        }
        loaded = true;
        plugin.getLogger().warning("bills: rejected: " + reason + (startup ? "; configured bills are off."
                : "; the previous catalogue stays in use."));
        plugin.alerts().raise(StaffAlerts.Kind.BILLS_CONFIG_REJECTED, "bills-config", "bills: rejected: " + reason);
        sender.accept("bills: rejected: " + reason);
    }

    private void post(Runnable action) {
        if (closed) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> { if (!closed) action.run(); });
        } catch (RuntimeException unavailable) {
            if (!closed) plugin.getLogger().warning("Bills completion could not reach the owner thread; work remains held.");
        }
    }

    private record Loaded(ChargeCatalogue.Catalogue catalogue, List<ChargeInvoiceRepository.Period> periods, List<Open> open,
                          String reason) { }

    public ChargeCatalogue.Catalogue catalogue() { return catalogue; }

    /** Phase 5 runs only when bills are switched on and a catalogue loaded. Off: the cycle goes straight on. */
    public static boolean billsPhaseRuns(boolean enabled, boolean loaded) {
        return enabled && loaded;
    }

    public boolean phaseRuns() {
        return billsPhaseRuns(enabledNow(), catalogue != ChargeCatalogue.Catalogue.DISABLED);
    }

    public boolean enabledNow() {
        return !closed && catalogue.enabled() && Boolean.TRUE.equals(plugin.getConfig().get("bills.enabled"));
    }

    /** A retiring version still applies today; its replacement starts tomorrow. */
    static List<ChargeCatalogue.Policy> inForce(List<ChargeInvoiceRepository.Period> periods, LocalDate day) {
        Map<String, ChargeCatalogue.Policy> chosen = new java.util.TreeMap<>();
        for (var period : periods) if (period.includes(day)) {
            if (chosen.putIfAbsent(period.policy().id(), period.policy()) != null)
                throw new IllegalStateException("Overlapping bill policy versions");
        }
        return List.copyOf(chosen.values());
    }

    // ---- Hold-rent ----------------------------------------------------------------------------------------------

    /**
     * The homes rent skips today for an unpaid invoice from an earlier day. With {@code on-unpaid: none}, or with
     * bills off, this is an empty set and the cache is never read. Empty Optional: hold-rent is on but the cache is
     * not loaded, so rent waits (fails closed).
     */
    public static Optional<Set<UUID>> rentHolds(ChargeCatalogue.Catalogue catalogue, LocalDate today,
                                                Supplier<List<Open>> cache) {
        if (!catalogue.enabled() || catalogue.onUnpaid() != ChargeCatalogue.OnUnpaid.HOLD_RENT) return Optional.of(Set.of());
        List<Open> rows = cache.get();
        if (rows == null) return Optional.empty();
        Set<UUID> held = new HashSet<>();
        for (Open o : rows) if (o.day().isBefore(today) && !o.payableUntil().isBefore(today)) held.add(o.propertyId());
        return Optional.of(Set.copyOf(held));
    }

    /**
     * The whole hold-rent decision for today's rent batch.
     *
     * <ul>
     *   <li>{@code bills.enabled} not true in the live config: nothing is held and the cache is never read.</li>
     *   <li>The first catalogue load has not finished and the live config asks for {@code on-unpaid: hold-rent}:
     *       empty Optional, so rent waits instead of running unheld.</li>
     *   <li>Otherwise the loaded catalogue decides, as in {@link #rentHolds(ChargeCatalogue.Catalogue, LocalDate,
     *       Supplier)}. An invoice holds a home only while that home, as cached now, is enabled and has the owner and
     *       ownership epoch the invoice was issued to, so a sale since the cache was read never holds the new
     *       owner.</li>
     * </ul>
     */
    public static Optional<Set<UUID>> rentHolds(boolean loaded, Object enabledSetting, Object onUnpaidSetting,
                                                ChargeCatalogue.Catalogue catalogue, LocalDate today,
                                                Supplier<List<Open>> cache, Collection<Property> homes) {
        if (!Boolean.TRUE.equals(enabledSetting)) return Optional.of(Set.of());
        if (!loaded && "hold-rent".equals(onUnpaidSetting)) return Optional.empty();
        return rentHolds(catalogue, today, () -> {
            List<Open> rows = cache.get();
            return rows == null ? null : currentOwnership(rows, homes);
        });
    }

    /** The open invoices whose home, as cached, is enabled and still has the invoice's owner and epoch. */
    static List<Open> currentOwnership(List<Open> rows, Collection<Property> homes) {
        Map<UUID, Property> byId = new java.util.HashMap<>();
        for (Property home : homes) byId.put(home.propertyId(), home);
        List<Open> out = new ArrayList<>();
        for (Open o : rows) {
            Property home = byId.get(o.propertyId());
            if (home != null && home.enabled() && o.ownerId().equals(home.ownerUuid())
                    && o.ownershipEpoch() == home.ownershipEpoch()) out.add(o);
        }
        return out;
    }

    /** Today's holds for these cached homes (the rent batch's own list). See the static form for each case. */
    public Optional<Set<UUID>> rentHolds(Collection<Property> homes) {
        return rentHolds(loaded, plugin.getConfig().get("bills.enabled"), plugin.getConfig().get("bills.on-unpaid"),
                catalogue, days.today(), () -> closed ? null : open, homes);
    }

    /** Whether rent is held for unpaid bills right now: bills on, with {@code on-unpaid: hold-rent} loaded. */
    public boolean holdsRentNow() {
        return enabledNow() && catalogue.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT;
    }

    /** After an invoice payment: refresh the hold-rent cache on the worker. */
    public void invoiceSettled() {
        if (!catalogue.enabled() || catalogue.onUnpaid() != ChargeCatalogue.OnUnpaid.HOLD_RENT) return;
        refreshOpen();
    }

    private void refreshOpen() {
        if (closed) return;
        long generation = catalogueGeneration;
        try {
            CompletableFuture.supplyAsync(() -> {
                try { return invoices.openInvoices(); }
                catch (Exception failure) { throw new IllegalStateException("Open invoices unreadable"); }
            }, worker).whenComplete((rows, failure) -> post(() -> {
                if (generation != catalogueGeneration) return;
                if (failure == null) open = List.copyOf(rows);
                else {
                    open = null;
                    plugin.getLogger().warning("bills: unpaid invoices could not be read; hold-rent homes wait.");
                }
            }));
        } catch (RuntimeException rejectedTask) {
            open = null;
        }
    }

    // ---- Phase 5 ------------------------------------------------------------------------------------------------

    /**
     * Phase 5 of the daily cycle. Expires old invoices, reads meters on main, issues at most one invoice per home on
     * the worker (one transaction each), then, only with auto-collect on, tries each new invoice once. Calls
     * {@code next} exactly once. False means nothing was started.
     */
    public boolean requestDaily(BooleanSupplier authority, Consumer<String> next) {
        if (closed || busy || !phaseRuns() || !authority.getAsBoolean()) return false;
        var catalogueNow = catalogue;
        long generation = catalogueGeneration;
        LocalDate day = days.today();
        List<ChargeCatalogue.Policy> policies = inForce(periods, day);
        if (policies.isEmpty()) {
            busy = true;
            submitInvoices(catalogueNow, policies, day, List.of(), Set.of(), generation, authority, next);
            return true;
        }
        Collection<Property> cached = plugin.propertyService() == null ? List.of() : plugin.propertyService().getEnrolled();
        var awayHeld = plugin.ownerAway().held(cached);
        if (awayHeld.isEmpty()) {
            next.accept("Bills: owner-away decisions for today are not saved; no invoices issued.");
            return true;
        }
        Set<UUID> away = awayHeld.get();
        List<Property> homes = cached.stream().filter(Property::enabled).filter(p -> p.ownerUuid() != null)
                .filter(p -> !away.contains(p.propertyId()))
                .sorted(Comparator.comparing(p -> p.propertyId().toString())).toList();
        // Resolve providers once; readings are captured across ticks before any invoice is written.
        Map<String, UsageMeter> meters = new LinkedHashMap<>();
        Map<String, String> meterProblems = new LinkedHashMap<>();
        Set<String> cumulative = new HashSet<>();
        for (var p : policies) {
            if (!p.meter().external() || meters.containsKey(p.meter().id()) || meterProblems.containsKey(p.meter().id())) continue;
            var choice = selectMeter(p.meter().id());
            if (choice.meter() == null) meterProblems.put(p.meter().id(), choice.reason());
            else {
                meters.put(p.meter().id(), choice.meter());
                if ("cumulative".equals(choice.kind())) cumulative.add(p.meter().id());
            }
        }
        busy = true;
        try {
            Instant now = Instant.now();
            Instant start = days.startOf(day);
            var batch = new BillingReadBatch(homes, policies, meters, meterProblems, start, now.isBefore(start) ? start : now,
                    System::nanoTime, plugin.getLogger()::warning);
            readTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!canContinue(generation, day, authority)) {
                    stopReadCapture(next);
                    return;
                }
                try {
                    refreshMeters(batch, meters);
                    if (!batch.advance()) return;
                } catch (RuntimeException | LinkageError failure) {
                    cancelReads();
                    busy = false;
                    next.accept("Bills: meter capture failed; no invoices issued.");
                    return;
                }
                if (!canContinue(generation, day, authority)) {
                    stopReadCapture(next);
                    return;
                }
                cancelReads();
                submitInvoices(catalogueNow, policies, day, batch.result(), Set.copyOf(cumulative), generation, authority, next);
            }, 1L, 1L);
        } catch (RuntimeException failure) {
            busy = false;
            return false;
        }
        return true;
    }

    private void cancelReads() {
        if (readTask != null) readTask.cancel();
        readTask = null;
    }

    private void refreshMeters(BillingReadBatch batch, Map<String, UsageMeter> selected) {
        if (selected.isEmpty()) return;
        Set<UsageMeter> available = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (var registration : plugin.getServer().getServicesManager().getRegistrations(UsageMeter.class))
                if (registration.getPlugin().isEnabled()) available.add(registration.getProvider());
        } catch (RuntimeException | LinkageError unavailable) {
            available.clear();
        }
        batch.retainMeters(available);
    }

    private boolean canContinue(long generation, LocalDate day, BooleanSupplier authority) {
        return !closed && generation == catalogueGeneration && enabledNow()
                && day.equals(days.today()) && authority.getAsBoolean();
    }

    private void stopReadCapture(Consumer<String> next) {
        cancelReads();
        busy = false;
        if (!closed) next.accept("Bills: settings or authority changed; no invoices issued.");
    }

    private void submitInvoices(ChargeCatalogue.Catalogue catalogueNow, List<ChargeCatalogue.Policy> policies,
                                LocalDate day, List<Read> reads, Set<String> cumulative, long generation,
                                BooleanSupplier authority, Consumer<String> next) {
        final CompletableFuture<String> issued;
        try {
            issued = CompletableFuture.supplyAsync(() -> issueAll(catalogueNow, policies, day, reads, cumulative, generation), worker);
        } catch (RuntimeException rejectedTask) {
            busy = false;
            next.accept("Bills: invoice worker is unavailable; no invoices issued.");
            return;
        }
        issued.whenComplete((summary, failure) -> post(() -> {
            if (!canContinue(generation, day, authority)) {
                busy = false;
                newlyIssued = List.of();
                if (!closed) next.accept("Bills: settings or authority changed; saved invoices remain unpaid.");
                return;
            }
            if (failure != null) {
                busy = false;
                lastRun = day + ": did not finish";
                plugin.alerts().raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-5",
                        "Daily bills step did not finish; invoices issued before the failure stand.");
                next.accept("Bills step did not finish; invoices already issued stand and no money moved.");
                return;
            }
            lastRun = day + ": " + summary;
            List<ChargeInvoiceRepository.Invoice> fresh = newlyIssued;
            newlyIssued = List.of();
            if (!catalogueNow.autoCollect() || fresh.isEmpty()) {
                busy = false;
                next.accept("Bills: " + summary);
                return;
            }
            BooleanSupplier collectionAllowed = () -> canContinue(generation, day, authority)
                    && catalogue.autoCollect() && Boolean.TRUE.equals(plugin.getConfig().get("bills.auto-collect-enabled"));
            collect(fresh, 0, new int[]{0, 0}, collectionAllowed, summary, next);
        }));
    }

    private volatile List<ChargeInvoiceRepository.Invoice> newlyIssued = List.of();

    /** Worker. One transaction per home; a failure on one home is logged and the others go on. */
    private String issueAll(ChargeCatalogue.Catalogue cat, List<ChargeCatalogue.Policy> policies, LocalDate day,
                            List<Read> reads, Set<String> cumulative, long generation) {
        BooleanSupplier current = () -> !closed && generation == catalogueGeneration && day.equals(days.today());
        if (!current.getAsBoolean()) return "settings changed; no invoices issued.";
        int expired;
        try { expired = invoices.expire(day, current); }
        catch (Exception failure) { throw new IllegalStateException("Invoice expiry unconfirmed"); }
        int count = 0, failed = 0;
        long cents = 0;
        List<ChargeInvoiceRepository.Invoice> fresh = new ArrayList<>();
        for (Read read : reads) {
            if (!current.getAsBoolean()) break;
            try {
                var result = invoices.issue(read.home(), day, policies, read.readings(), read.failures(),
                        cat.ownerDailyMaxCents(), cat.serverDailyMaxCents(), cat.payableDays(), cumulative, current);
                report(read.home(), result);
                if (result.invoice().isPresent() && !result.replay()) {
                    count++;
                    cents += result.invoice().get().totalCents();
                    fresh.add(result.invoice().get());
                }
            } catch (Exception failure) {
                failed++;
                plugin.getLogger().warning("bills: invoice for home " + read.home().propertyId() + " not issued ("
                        + failure.getClass().getSimpleName() + ").");
            }
        }
        newlyIssued = List.copyOf(fresh);
        try { if (cat.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT) open = List.copyOf(invoices.openInvoices()); }
        catch (Exception failure) { open = null; }
        return count + " invoice(s) issued, " + dollars(cents) + " total; " + expired + " expired"
                + (failed == 0 ? "." : "; " + failed + " home(s) could not be billed.");
    }

    private void report(ChargeInvoiceRepository.Home home, ChargeInvoiceRepository.Issued result) {
        for (var skipped : result.plan().skipped()) {
            plugin.getLogger().warning("bills: " + skipped.policyId() + " not charged for home " + home.propertyId()
                    + ": " + skipped.reason());
            plugin.alerts().raise(skipped.meterFault() ? StaffAlerts.Kind.BILL_METER_FAILED : StaffAlerts.Kind.BILL_FORMULA_FAILED,
                    "bill-" + skipped.policyId(), "Bill " + skipped.policyId() + " was not charged for at least one home: "
                            + skipped.reason());
        }
        for (String suspect : result.caps().suspect()) {
            plugin.getLogger().warning("bills: " + suspect + " meter reading for home " + home.propertyId()
                    + " is more than " + ChargeLimits.SUSPECT_FACTOR + " times its maximum; not charged.");
            plugin.alerts().raise(StaffAlerts.Kind.BILL_METER_FAILED, "bill-suspect-" + suspect,
                    "Bill " + suspect + " had a suspect meter reading; that line was not charged.");
        }
        if (!result.caps().billTrimmed().isEmpty())
            plugin.alerts().raise(StaffAlerts.Kind.BILL_CAP_REACHED, "bill-total-cap",
                    "The $200 bill ceiling left lines off at least one invoice.");
        if (!result.caps().ownerTrimmed().isEmpty() || !result.caps().serverTrimmed().isEmpty())
            plugin.alerts().raise(StaffAlerts.Kind.BILL_CAP_REACHED, result.caps().serverTrimmed().isEmpty() ? "bill-owner-cap" : "bill-server-cap",
                    "A daily bill limit left lines off at least one invoice.");
        for (String reset : result.baselineResets())
            plugin.alerts().raise(StaffAlerts.Kind.BILL_METER_FAILED, "bill-reset-" + reset,
                    "Bill " + reset + " meter went down (reset); nothing was charged and a new baseline was set.");
    }

    /** One attempt per new invoice, in order, each waiting for the previous one. No retries. */
    private void collect(List<ChargeInvoiceRepository.Invoice> fresh, int index, int[] outcome, BooleanSupplier authority,
                         String summary, Consumer<String> next) {
        if (closed) { busy = false; return; }
        if (index >= fresh.size() || closed || !authority.getAsBoolean()) {
            busy = false;
            next.accept("Bills: " + summary + " Automatic collection: " + outcome[0] + " paid, " + outcome[1]
                    + " not paid (owners can pay from /property bills).");
            if (catalogue.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT) refreshOpen();
            return;
        }
        var invoice = fresh.get(index);
        var payments = plugin.utilityPayments();
        boolean started = payments != null && payments.requestBillCollection(invoice.propertyId(), invoice.ownerId(),
                invoice.invoiceId(), invoice.totalCents(), authority, ok -> {
                    if (Boolean.TRUE.equals(ok)) outcome[0]++;
                    else outcome[1]++;
                    post(() -> collect(fresh, index + 1, outcome, authority, summary, next));
                });
        if (!started) {
            outcome[1]++;
            post(() -> collect(fresh, index + 1, outcome, authority, summary, next));
        }
    }

    // ---- Meters -------------------------------------------------------------------------------------------------

    /** The selected meter, its declared kind, or why there is none. */
    public record MeterChoice(UsageMeter meter, String kind, String reason) { }

    public MeterChoice selectMeter(String id) {
        PieceSelector.Selection<UsageMeter> selection;
        try {
            selection = new PieceSelector<>(UsageMeter.class).select(id, Map.of(),
                    Pieces.registrations(plugin.getServer(), UsageMeter.class));
        } catch (RuntimeException failure) {
            return new MeterChoice(null, "", "meter registry unreadable");
        }
        if (!selection.found()) return new MeterChoice(null, "", "meter " + id + ": " + selection.reason());
        String kind;
        try { kind = String.valueOf(selection.selected().capabilities().value("kind")).toLowerCase(Locale.ROOT); }
        catch (RuntimeException | LinkageError broken) { return new MeterChoice(null, "", "meter " + id + ": capabilities unreadable"); }
        if (!Set.of("period", "level", "cumulative").contains(kind))
            return new MeterChoice(null, "", "meter " + id + ": kind must be period, level or cumulative");
        return new MeterChoice(selection.selected(), kind, "");
    }

    // ---- Views --------------------------------------------------------------------------------------------------

    /** The one /npcadmin health line, shown only while bills are on or a block was refused. */
    public Optional<String> healthLine() {
        if (rejected != null) return Optional.of("Bills: rejected: " + rejected);
        var c = catalogue;
        if (!c.enabled()) return Optional.empty();
        return Optional.of("Bills: on, " + c.policies().size() + " policies, auto-collect " + (c.autoCollect() ? "on" : "off")
                + ", on-unpaid " + (c.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT ? "hold-rent" : "none")
                + ", last run " + lastRun);
    }

    public List<String> statusLines(ChargeInvoiceRepository.Usage usage) {
        var c = catalogue;
        List<String> out = new ArrayList<>();
        out.add("Bills: " + (c.enabled() ? "on" : "off") + (rejected == null ? "" : " (last block rejected: " + rejected + ")"));
        out.add("Limits: per owner " + dollars(c.ownerDailyMaxCents()) + " a day, server " + dollars(c.serverDailyMaxCents())
                + " a day; payable for " + c.payableDays() + " day(s); on-unpaid "
                + (c.onUnpaid() == ChargeCatalogue.OnUnpaid.HOLD_RENT ? "hold-rent" : "none")
                + "; auto-collect " + (c.autoCollect() ? "on" : "off"));
        for (var p : c.policies()) {
            LocalDate from = periods.stream().filter(period -> period.policy().id().equals(p.id())
                    && period.policy().version() == p.version()).map(ChargeInvoiceRepository.Period::from).findFirst().orElse(null);
            out.add("  " + p.id() + " v" + p.version() + " \"" + p.title() + "\" meter " + p.meter().id() + ", max "
                    + dollars(p.maxCents()) + ", " + (from == null ? "not recorded" : "effective " + from)
                    + (p.meter().external() ? meterNote(p.meter().id()) : ""));
        }
        if (usage != null)
            out.add("Today: " + usage.issued() + " issued, " + usage.paid() + " paid, " + usage.expired() + " expired; "
                    + dollars(usage.issuedCents()) + " billed of the server limit " + dollars(c.serverDailyMaxCents()));
        out.add("Last run: " + lastRun);
        return out;
    }

    private String meterNote(String id) {
        var choice = selectMeter(id);
        return choice.meter() == null ? " (" + choice.reason() + ")" : " (meter " + choice.kind() + ")";
    }

    public ChargeInvoiceRepository invoices() { return invoices; }
    public java.util.concurrent.Executor worker() { return worker; }
    public LocalDate today() { return days.today(); }

    /** Policies in force today, for the staff preview. */
    public List<ChargeCatalogue.Policy> inForceToday() { return inForce(periods, days.today()); }

    public static String dollars(long cents) {
        return Money.format(cents);
    }

    @Override public void close() {
        closed = true;
        cancelReads();
        worker.shutdown();
    }
}
