package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.PartnerOffer;
import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure view of the "Contracts and partners" topic, and every player line for it. At most 3 actions, in priority
 * order: the open contract (Hand over, Find, Cancel), then the partnership (Partner, Renew, End), then new contracts.
 * Every action is bound to the resident in the conversation (recipientId); Hand over only shows at the contract's
 * recipient, so there it is also the contract's recipient.
 * Times and the daily reset are written in the server's configured zone and reset hour.
 */
public final class ContractPresentation {
    private ContractPresentation() { }

    public static final String TOPIC = "Contracts and partners";
    public static final String NO_OFFERS = "I have no contract for you right now.";
    public static final String LOCKED_HINT = "Trusted friends get bigger jobs.";
    public static final String SURVIVAL_ONLY = "You can only hand over items in survival mode.";
    public static final String WRONG_WORLD = "You can't hand over items in this world.";
    public static final String COMPLETED = "Thank you. I will remember this.";
    public static final String ALREADY_COMPLETED = "That contract is already done. It does not count twice.";
    public static final String CANCEL_BUTTON = "Cancel contract (today's slot stays used)";
    public static final String CANCELLED = "The contract is cancelled. No trust is lost, but today's slot stays used.";
    public static final String EXPIRED = "That contract ran out of time. No trust is lost.";
    public static final String ACTIVE_CONFLICT = "Finish or cancel your current contract first.";
    public static final String TOO_LOW = "That contract needs more trust first.";
    public static final String UNAVAILABLE = "This contract is unavailable. Nothing was taken.";
    public static final String CHECKING = "One moment. I'm checking the items.";
    public static final String BUSY = "Your last handover is still being checked. Wait a moment.";
    public static final String ITEMS_BUSY = "Your items are busy with a delivery or repair. Try again in a moment.";
    public static final String CLOSE_INVENTORY = "Close any open inventory and try again.";
    public static final String ITEMS_CHANGED = "Your items changed before the handover started. Nothing was taken.";
    public static final String LOCKED_HOME = "You need a home in the housing market to partner with me.";
    public static final String NOT_RENEWABLE = "This partnership is not ready to renew yet.";
    public static final String ENDED = "The partnership has ended.";
    public static final String END_BUTTON = "End partnership";
    public static final String CONFIRM = "Confirm";
    public static final String BACK = "Back";
    public static final String ADVERTISING_TOP = "Your advertising is already at the top.";
    public static final String ALREADY_ACTIVE = "You already have this contract. Your deadline is unchanged.";

    /** What the worker read for one player at one resident. */
    public record Facts(ContractCatalogue catalogue, boolean contractsOn, boolean partnershipsOn, boolean rankLive,
                        Optional<TownContract> current, int slotsUsedToday, int keptForResident,
                        Optional<Partnership> partnership, boolean partnerDayUsed, boolean ownsHome, int seatsUsed,
                        boolean advertisingTop, Set<UUID> reachable, Instant now, LocalDate today) {
        public Facts {
            current = current == null ? Optional.empty() : current;
            partnership = partnership == null ? Optional.empty() : partnership;
            reachable = Set.copyOf(reachable);
        }
    }

    public static EncounterView view(EncounterView base, ResidentProfile person, RelationshipMemory.State relationship,
                                     Facts f, ResidentCast cast, EconomyClock clock) {
        var actions = new ArrayList<ResidentAction>();
        var lines = new ArrayList<String>();
        UUID me = person.id();
        Optional<TownContract> open = f.current().filter(t -> t.definition().kind() != ContractDefinition.Kind.PARTNERSHIP
                || t.status() != TownContract.Status.ACTIVE);
        boolean contractOpen = open.isPresent();
        if (open.isPresent()) {
            TownContract t = open.get();
            ContractDefinition d = t.definition();
            switch (t.status()) {
                case AMBIGUOUS -> lines.add(blockedByHeld(t.contractId()));
                case HANDOVER_INTENT -> lines.add(BUSY);
                default -> {
                    String recipient = name(d.recipientId(), cast);
                    if (me.equals(d.recipientId())) {
                        lines.add("You have " + d.title() + " for me. Bring " + items(d) + " by " + deadline(t.expiresAt(), clock) + ".");
                        actions.add(new ResidentAction(ResidentAction.Kind.HANDOVER, null, t.contractId(), d.recipientId(),
                                "Hand over " + items(d)));
                    } else {
                        lines.add(wrongResident(recipient, d));
                        actions.add(new ResidentAction(ResidentAction.Kind.CONTRACT_DIRECTIONS, null, t.contractId(),
                                me, "Find " + recipient));
                    }
                    actions.add(new ResidentAction(ResidentAction.Kind.CONTRACT_CANCEL, null, t.contractId(), me, CANCEL_BUTTON));
                }
            }
        } else if (f.contractsOn()) {
            var catalogue = f.catalogue();
            if (f.slotsUsedToday() >= catalogue.perPlayerPerDay()) lines.add(dailyLimit(clock, f.now()));
            else {
                var mine = catalogue.offers(me);
                var available = mine.stream().filter(d -> relationship.stage().ordinal() >= d.minimumStage().ordinal()
                        && (d.recipientId().equals(me) || f.reachable().contains(d.recipientId()))).limit(2).toList();
                if (available.isEmpty()) lines.add(NO_OFFERS);
                else lines.add(available.getFirst().description() + " " + footer(catalogue.perPlayerPerDay()));
                if (mine.stream().anyMatch(d -> relationship.stage().ordinal() < d.minimumStage().ordinal())) lines.add(LOCKED_HINT);
                // Offers come last in priority; they are added after the partnership actions below.
                partnership(f, person, relationship, cast, clock, actions, lines, contractOpen);
                for (var d : available) if (actions.size() < 3)
                    actions.add(new ResidentAction(ResidentAction.Kind.CONTRACT_ACCEPT, d.id(), null, me,
                            fit("Take: " + d.title(), " (" + items(d) + ")")));
                if (f.keptForResident() > 0) lines.add(kept(f.keptForResident()));
                return build(base, lines, actions);
            }
        }
        partnership(f, person, relationship, cast, clock, actions, lines, contractOpen);
        if (f.keptForResident() > 0) lines.add(kept(f.keptForResident()));
        if (lines.isEmpty()) lines.add(NO_OFFERS);
        return build(base, lines, actions);
    }

    private static void partnership(Facts f, ResidentProfile person, RelationshipMemory.State relationship, ResidentCast cast,
                                    EconomyClock clock, List<ResidentAction> actions, List<String> lines, boolean contractOpen) {
        if (!f.partnershipsOn()) return;
        Optional<PartnerOffer> offer = f.catalogue().partnerOffer(person.id());
        Optional<Partnership> mine = f.partnership();
        if (mine.isPresent() && mine.get().residentId().equals(person.id())) {
            Partnership p = mine.get();
            lines.add("We're partners until " + p.lastActiveDay() + ".");
            if (offerLive(offer, f.rankLive()) && !contractOpen && !f.partnerDayUsed()
                    && p.renewableOn(f.today(), f.catalogue().renewWindowDays()) && actions.size() < 3)
                actions.add(new ResidentAction(ResidentAction.Kind.PARTNER, ContractDefinition.PARTNER_RENEW, null, person.id(),
                        "Renew partnership (" + items(offer.get()) + ")"));
            if (actions.size() < 3) actions.add(new ResidentAction(ResidentAction.Kind.PARTNER_END, null, p.partnershipId(), person.id(), END_BUTTON));
            return;
        }
        if (!offerLive(offer, f.rankLive())) return;
        PartnerOffer o = offer.get();
        if (mine.isPresent()) { lines.add(hasOne(name(mine.get().residentId(), cast), mine.get().lastActiveDay())); return; }
        var catalogue = f.catalogue();
        String locked = relationship.stage().ordinal() < catalogue.partnerMinimumStage().ordinal()
                ? "Partnerships open at " + EncounterView.label(catalogue.partnerMinimumStage().name()) + "."
                : f.keptForResident() < catalogue.minContracts() ? moreContracts(catalogue.minContracts() - f.keptForResident())
                : !f.ownsHome() ? LOCKED_HOME
                : f.seatsUsed() >= catalogue.maxPartners() ? seatsFull(catalogue.maxPartners())
                : contractOpen ? ACTIVE_CONFLICT
                : f.partnerDayUsed() ? partnerDailyLimit(clock, f.now()) : null;
        if (locked != null) { lines.add(locked); return; }
        lines.add(pitch(o, f.rankLive(), f.advertisingTop()));
        if (actions.size() < 3)
            actions.add(new ResidentAction(ResidentAction.Kind.PARTNER, ContractDefinition.PARTNER_START, null, person.id(),
                    fit("Partner with " + firstName(person), " (" + items(o) + ")")));
    }

    private static EncounterView build(EncounterView base, List<String> lines, List<ResidentAction> actions) {
        return new EncounterView(base.name(), base.occupation(), base.familiarity(), String.join(" ", lines), base.topics(),
                List.copyOf(actions.subList(0, Math.min(3, actions.size()))));
    }

    /** The partnership confirmation: Confirm and Back, nothing else. */
    public static EncounterView confirmPartner(ResidentProfile person, PartnerOffer offer, boolean start, int termDays,
                                               boolean rankLive, boolean advertisingTop) {
        String body = firstName(person) + " takes " + items(offer) + " from your inventory. For " + termDays + " days, "
                + perkSentence(offer.perks(), rankLive) + ". You can have one partner at a time. Nothing is refunded if you end it early.";
        if (advertisingTop && offer.feeDiscountPct() == 0) body += " " + ADVERTISING_TOP;
        return new EncounterView("Partner with " + person.name(), "Partnership", start ? "New" : "Renewal", body, List.of(),
                List.of(new ResidentAction(ResidentAction.Kind.PARTNER_CONFIRM, start ? ContractDefinition.PARTNER_START
                                : ContractDefinition.PARTNER_RENEW, offerKey(offer), person.id(), CONFIRM),
                        new ResidentAction(ResidentAction.Kind.CONTRACT_BACK, null, null, person.id(), BACK)));
    }

    /** True when the resident has an offer and at least one of its perks does something on this server. */
    public static boolean offerLive(Optional<PartnerOffer> offer, boolean rankLive) {
        return offer.isPresent() && offer.get().perks().live(rankLive);
    }

    /** Names the offer exactly as a confirmation showed it: resident, materials, quantity and perks. */
    public static UUID offerKey(PartnerOffer offer) {
        String shown = offer.residentId() + "|" + offer.material() + "|" + offer.quantity() + "|" + offer.feeDiscountPct()
                + "|" + offer.rankBonusPoints();
        return UUID.nameUUIDFromBytes(shown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Checked on main when Confirm is pressed, before any item is taken: the offer is still live and is the one the
     * confirmation showed. A config reload or a rank switch in between refuses instead of taking other items.
     */
    public static boolean confirmStillValid(Optional<PartnerOffer> offer, UUID shownKey, boolean rankLive) {
        return offerLive(offer, rankLive) && offerKey(offer.get()).equals(shownKey);
    }

    /**
     * head + tail within the action label limit. A long head (a contract title or a name) is cut and ends in "...",
     * so a long title in config never breaks the topic.
     */
    static String fit(String head, String tail) {
        int room = ResidentAction.MAX_LABEL - tail.length();
        if (head.length() <= room) return head + tail;
        int cut = Math.max(0, room - 3);
        if (cut > 0 && Character.isHighSurrogate(head.charAt(cut - 1))) cut--;
        return head.substring(0, cut).stripTrailing() + "..." + tail;
    }

    /** The two-step end: Confirm and Back. */
    public static EncounterView confirmEnd(ResidentProfile person, UUID partnershipId) {
        return new EncounterView(END_BUTTON, "Partnership", "End", "End your partnership with " + person.name()
                + " now? Nothing is refunded.", List.of(),
                List.of(new ResidentAction(ResidentAction.Kind.PARTNER_END_CONFIRM, null, partnershipId, person.id(), CONFIRM),
                        new ResidentAction(ResidentAction.Kind.CONTRACT_BACK, null, null, person.id(), BACK)));
    }

    /** One line for a repository result. contract and partnership name the subject where the line needs them. */
    public static String result(Code code, Optional<TownContract> contract, Optional<Partnership> partnership, int detail,
                                ContractCatalogue catalogue, ResidentCast cast, EconomyClock clock, Instant now) {
        return switch (code) {
            case ACCEPTED -> contract.map(t -> accepted(t, clock)).orElse(UNAVAILABLE);
            case ALREADY_ACTIVE -> ALREADY_ACTIVE;
            case ACTIVE_CONFLICT -> ACTIVE_CONFLICT;
            case DAILY_LIMIT -> dailyLimit(clock, now);
            case RELATIONSHIP_TOO_LOW -> TOO_LOW;
            case CANCELLED, ALREADY_CANCELLED -> CANCELLED;
            case EXPIRED -> EXPIRED;
            case COMPLETED -> COMPLETED;
            case ALREADY_COMPLETED -> ALREADY_COMPLETED;
            case HANDOVER_HELD -> contract.map(t -> held(t.contractId())).orElse(UNAVAILABLE);
            case HELD_FOR_REVIEW -> contract.map(t -> blockedByHeld(t.contractId())).orElse(UNAVAILABLE);
            case BUSY -> BUSY;
            case ITEMS_BUSY -> ITEMS_BUSY;
            case WRONG_RECIPIENT -> contract.map(t -> wrongResident(name(t.definition().recipientId(), cast), t.definition())).orElse(UNAVAILABLE);
            case PARTNER_STARTED -> partnership.map(p -> "We're partners until " + p.lastActiveDay() + ". Thank you.").orElse(COMPLETED);
            case PARTNER_RENEWED -> partnership.map(p -> "Partnership renewed until " + p.lastActiveDay() + ".").orElse(COMPLETED);
            case PARTNER_STAGE -> "Partnerships open at " + EncounterView.label(catalogue.partnerMinimumStage().name()) + ".";
            case PARTNER_CONTRACTS -> moreContracts(Math.max(1, detail));
            case PARTNER_HOME -> LOCKED_HOME;
            case PARTNER_SEATS_FULL -> seatsFull(catalogue.maxPartners());
            case PARTNER_HAS_ONE -> partnership.map(p -> hasOne(name(p.residentId(), cast), p.lastActiveDay())).orElse(UNAVAILABLE);
            case PARTNER_DAILY_LIMIT -> partnerDailyLimit(clock, now);
            case PARTNER_NOT_RENEWABLE -> NOT_RENEWABLE;
            case HANDOVER_STARTED -> CHECKING;
            case UNKNOWN_GIVER, UNKNOWN_RECIPIENT, TIME_REGRESSION, NOT_ACTIVE, NOT_FOUND, RESOLVED_CONSUMED,
                 RESOLVED_NOT_CONSUMED, NOT_HELD -> UNAVAILABLE;
        };
    }

    public static String accepted(TownContract t, EconomyClock clock) {
        return "The contract is in your journal. Bring " + items(t.definition()) + " by " + deadline(t.expiresAt(), clock) + ".";
    }

    public static String notEnough(int quantity, String material, int have) {
        return "You need " + quantity + " " + materialName(material) + " in your inventory. You have " + have + ".";
    }

    public static String held(UUID contractId) {
        return "That handover needs staff review. Do not hand it over again. Reference: " + contractId;
    }

    public static String blockedByHeld(UUID contractId) {
        return "Your last handover is waiting for staff review. Reference: " + contractId;
    }

    public static String wrongResident(String recipient, ContractDefinition d) {
        return "Your contract is for " + recipient + ". Bring them " + items(d) + ".";
    }

    public static String kept(int n) {
        return "You have kept " + n + (n == 1 ? " contract" : " contracts") + " for me this month.";
    }

    public static String footer(int perDay) {
        return (perDay == 1 ? "One contract a day." : perDay + " contracts a day.") + " You get familiarity, not money or items.";
    }

    public static String moreContracts(int n) {
        return "Keep " + n + " more " + (n == 1 ? "contract" : "contracts") + " for me first.";
    }

    public static String seatsFull(int max) { return "I already have " + max + " partners. Try again next week."; }

    public static String hasOne(String name, LocalDate day) { return "You already partner with " + name + " until " + day + "."; }

    /** "my fee is 40% lower for you", "I tell new arrivals about your homes", or both. */
    public static String perkSentence(PartnerPerks perks, boolean rankLive) {
        var parts = new ArrayList<String>();
        if (perks.feeDiscountPct() > 0) parts.add("my fee is " + perks.feeDiscountPct() + "% lower for you");
        if (perks.rankBonusPoints() > 0 && rankLive) parts.add("I tell new arrivals about your homes");
        return parts.isEmpty() ? "nothing changes for now" : String.join(" and ", parts);
    }

    /** The offer's pitch, plus a note when the rank perk can add nothing because advertising is at the top. */
    public static String pitch(PartnerOffer offer, boolean rankLive, boolean advertisingTop) {
        return offer.feeDiscountPct() == 0 && rankLive && advertisingTop ? offer.pitch() + " " + ADVERTISING_TOP : offer.pitch();
    }

    /** "Come back after the daily reset at 4 AM CDT." in the configured zone and hour. */
    public static String dailyLimit(EconomyClock clock, Instant now) {
        return "You've taken a contract today. Come back after the daily reset at " + resetTime(clock, now) + ".";
    }

    public static String partnerDailyLimit(EconomyClock clock, Instant now) {
        return "You can start or renew one partnership a day. Come back after the daily reset at " + resetTime(clock, now) + ".";
    }

    /** The next reset as "4 AM CDT", in the configured zone. */
    public static String resetTime(EconomyClock clock, Instant now) {
        Instant next = clock.startOf(clock.dayAt(now).plusDays(1));
        return DateTimeFormatter.ofPattern("h a z", Locale.US).withZone(clock.zone()).format(next);
    }

    /**
     * A deadline as "14:30 CDT", in the configured zone. A deadline on a later calendar day says so, "tomorrow 14:30 CDT"
     * or "Oct 16 14:30 CDT": a 24-hour contract taken at 06:40 is due at 06:40, which read as already passed.
     */
    public static String deadline(Instant at, EconomyClock clock) {
        String time = DateTimeFormatter.ofPattern("HH:mm z", Locale.US).withZone(clock.zone()).format(at);
        java.time.LocalDate day = at.atZone(clock.zone()).toLocalDate(), today = clock.instant().atZone(clock.zone()).toLocalDate();
        if (!day.isAfter(today)) return time;
        return (day.equals(today.plusDays(1)) ? "tomorrow" : DateTimeFormatter.ofPattern("MMM d", Locale.US).format(day)) + " " + time;
    }

    /** "64 Cobblestone". */
    public static String items(ContractDefinition d) { return d.quantity() + " " + materialName(d.material()); }
    public static String items(PartnerOffer o) { return o.quantity() + " " + materialName(o.material()); }

    /** IRON_INGOT as "Iron Ingot". */
    public static String materialName(String material) {
        var out = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    static String name(UUID id, ResidentCast cast) {
        return cast.byId(id).map(ResidentProfile::name).orElse("the named resident");
    }

    static String firstName(ResidentProfile person) { return person.name().split(" ", 2)[0]; }

    /** Adds the topic after Business while either feature is on, the same way Tips is added. */
    public static EncounterView withTopic(EncounterView view, boolean shown) {
        if (!shown || view.topics().contains(ResidentDialogue.Topic.CONTRACTS) || view.confirmation()) return view;
        var topics = new ArrayList<>(view.topics());
        int business = topics.indexOf(ResidentDialogue.Topic.BUSINESS);
        topics.add(business < 0 ? topics.size() : business + 1, ResidentDialogue.Topic.CONTRACTS);
        return new EncounterView(view.name(), view.occupation(), view.familiarity(), view.line(), topics, view.actions(),
                view.moodSuffix());
    }
}
