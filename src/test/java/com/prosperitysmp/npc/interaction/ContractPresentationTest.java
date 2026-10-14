package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.command.NpcTownCopySamples;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.PartnerOffer;
import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ContractPresentationTest {
    private static final UUID PLAYER = new UUID(0, 70);
    private static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    private static final ResidentCast CAST = ResidentCast.builtIn();
    private static final ResidentProfile MARA = CAST.byFirstName("mara").orElseThrow();
    private static final ResidentProfile ELLIS = CAST.byFirstName("ellis").orElseThrow();
    private static final ResidentProfile NICO = CAST.byFirstName("nico").orElseThrow();
    private static final EconomyClock CHICAGO = new EconomyClock(Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/Chicago"), 4);
    private static final ContractCatalogue ON = ContractCatalogue.load(Map.of("town.contracts.enabled", true,
            "town.partnerships.enabled", true)::get, CAST, name -> true);
    private static final LocalDate DAY = CHICAGO.dayAt(NOW);

    /** positiveDays below 0 is a stranger (no memory at all); 0 is an acquaintance (introduced only). */
    static RelationshipMemory.State state(ResidentProfile profile, int positiveDays) {
        var events = new ArrayList<RelationshipMemory.Event>();
        if (positiveDays < 0) return RelationshipMemory.replay(profile.id(), PLAYER, events);
        events.add(new RelationshipMemory.Event(profile.id(), PLAYER, "introduction", RelationshipMemory.Kind.INTRODUCTION,
                NOW.minusSeconds(86_400L * 20), DAY.minusDays(20)));
        for (int i = positiveDays; i >= 1; i--)
            events.add(new RelationshipMemory.Event(profile.id(), PLAYER, "repair:" + i, RelationshipMemory.Kind.REPAIR_COMPLETED,
                    NOW.minusSeconds(86_400L * i), DAY.minusDays(i)));
        return RelationshipMemory.replay(profile.id(), PLAYER, events);
    }

    static EncounterView base(ResidentProfile profile, RelationshipMemory.State state) {
        return EncounterView.of(profile, state, ResidentDialogue.reply(profile, state, ResidentDialogue.Topic.CONTRACTS, 1000));
    }

    static ContractPresentation.Facts facts(Optional<TownContract> current, int slots, int kept, Optional<Partnership> partnership,
                                            boolean dayUsed, boolean home, int seats, boolean rankLive) {
        return new ContractPresentation.Facts(ON, true, true, rankLive, current, slots, kept, partnership, dayUsed, home, seats,
                false, Set.of(MARA.id(), ELLIS.id(), NICO.id()), NOW, DAY);
    }

    static TownContract active(ContractDefinition d) {
        return new TownContract(UUID.randomUUID(), PLAYER, d, NOW.minusSeconds(60), DAY, 1,
                NOW.minusSeconds(60).plusSeconds(d.durationMinutes() * 60L), TownContract.Status.ACTIVE, null, null, null);
    }

    static Partnership partnership(ResidentProfile with, LocalDate last) {
        return new Partnership(UUID.randomUUID(), PLAYER, with.id(), last.minusDays(6), last, new PartnerPerks(40, 0),
                Partnership.Status.ACTIVE, null, null, UUID.randomUUID(), 1);
    }

    @Test void neverMoreThanThreeActionsAndTheOpenContractComesFirst() {
        var wallStone = ON.find("mara-wall-stone").orElseThrow();
        var coalRun = ON.find("nico-coal-run").orElseThrow();
        for (var person : List.of(MARA, ELLIS, NICO))
            for (var contract : List.of(Optional.<TownContract>empty(), Optional.of(active(wallStone)), Optional.of(active(coalRun))))
                for (var partner : List.of(Optional.<Partnership>empty(), Optional.of(partnership(person, DAY)),
                        Optional.of(partnership(person, DAY.plusDays(5))), Optional.of(partnership(NICO, DAY))))
                    for (int days : new int[] {0, 3, 7})
                        for (boolean home : new boolean[] {true, false}) {
                            var state = state(person, days);
                            var view = ContractPresentation.view(base(person, state), person, state,
                                    facts(contract, 0, 2, partner, false, home, 0, true), CAST, CHICAGO);
                            assertTrue(view.actions().size() <= 3, view.actions().toString());
                            // The controller refuses an action not bound to the resident in the conversation.
                            assertTrue(view.actions().stream().allMatch(a -> a.recipientId().equals(person.id())), view.actions().toString());
                            if (contract.isPresent()) {
                                var first = view.actions().getFirst().kind();
                                assertTrue(first == ResidentAction.Kind.HANDOVER || first == ResidentAction.Kind.CONTRACT_DIRECTIONS);
                                assertTrue(view.actions().stream().noneMatch(a -> a.kind() == ResidentAction.Kind.CONTRACT_ACCEPT
                                        || a.kind() == ResidentAction.Kind.PARTNER));
                            }
                        }
    }

    @Test void aRenewablePartnerWithTwoOffersIsCappedAtThreeWithPartnershipFirst() {
        // Mara at Acquaintance or better offers 2 contracts; with a renewable partnership that is 4 candidates.
        var state = state(MARA, 3);
        var view = ContractPresentation.view(base(MARA, state), MARA, state,
                facts(Optional.empty(), 0, 2, Optional.of(partnership(MARA, DAY)), false, true, 0, true), CAST, CHICAGO);
        assertEquals(List.of(ResidentAction.Kind.PARTNER, ResidentAction.Kind.PARTNER_END, ResidentAction.Kind.CONTRACT_ACCEPT),
                view.actions().stream().map(ResidentAction::kind).toList());
        assertEquals("Renew partnership (64 Stone)", view.actions().getFirst().label());
    }

    @Test void theRecipientGetsTheHandOverButtonAndOthersGetDirections() {
        var coalRun = ON.find("nico-coal-run").orElseThrow();
        var contract = active(coalRun);
        var atMara = ContractPresentation.view(base(MARA, state(MARA, 0)), MARA, state(MARA, 0),
                facts(Optional.of(contract), 1, 0, Optional.empty(), false, false, 0, false), CAST, CHICAGO);
        assertEquals("Hand over 16 Coal", atMara.actions().getFirst().label());
        assertEquals(contract.contractId(), atMara.actions().getFirst().taskId());
        var atNico = ContractPresentation.view(base(NICO, state(NICO, 0)), NICO, state(NICO, 0),
                facts(Optional.of(contract), 1, 0, Optional.empty(), false, false, 0, false), CAST, CHICAGO);
        assertEquals("Find Mara Vale", atNico.actions().getFirst().label());
        assertTrue(atNico.line().contains("Your contract is for Mara Vale. Bring them 16 Coal."));
        assertEquals(ContractPresentation.CANCEL_BUTTON, atNico.actions().get(1).label());
    }

    @Test void offersShowTheFooterAndLockedHint() {
        var view = ContractPresentation.view(base(ELLIS, state(ELLIS, -1)), ELLIS, state(ELLIS, -1),
                facts(Optional.empty(), 0, 0, Optional.empty(), false, false, 0, false), CAST, CHICAGO);
        // Ellis has nothing for a stranger, and the trusted job is hinted.
        assertTrue(view.line().startsWith(ContractPresentation.NO_OFFERS));
        assertTrue(view.line().contains(ContractPresentation.LOCKED_HINT));
        var mara = ContractPresentation.view(base(MARA, state(MARA, -1)), MARA, state(MARA, -1),
                facts(Optional.empty(), 0, 0, Optional.empty(), false, false, 0, false), CAST, CHICAGO);
        assertEquals("Take: Wall stone (64 Cobblestone)", mara.actions().getFirst().label());
        assertTrue(mara.line().contains("One contract a day. You get familiarity, not money or items."));
    }

    private static String line(ResidentProfile person, RelationshipMemory.State state, int kept, boolean home, int seats) {
        return ContractPresentation.view(base(person, state), person, state,
                facts(Optional.empty(), 1, kept, Optional.empty(), false, home, seats, false), CAST, CHICAGO).line();
    }

    @Test void partnershipLinesSayWhichConditionFails() {
        var stranger = state(ELLIS, 0);
        var trusted = state(ELLIS, 3);
        assertTrue(line(ELLIS, stranger, 2, true, 0).contains("Partnerships open at Trusted."));
        assertTrue(line(ELLIS, trusted, 0, true, 0).contains("Keep 2 more contracts for me first."));
        assertTrue(line(ELLIS, trusted, 1, true, 0).contains("Keep 1 more contract for me first."));
        assertTrue(line(ELLIS, trusted, 2, false, 0).contains(ContractPresentation.LOCKED_HOME));
        assertTrue(line(ELLIS, trusted, 2, true, 4).contains("I already have 4 partners. Try again next week."));
        var ok = ContractPresentation.view(base(ELLIS, trusted), ELLIS, trusted,
                facts(Optional.empty(), 0, 2, Optional.empty(), false, true, 3, false), CAST, CHICAGO);
        assertTrue(ok.line().contains("I cut my fee by 40% for you."));
        assertTrue(ok.actions().stream().anyMatch(a -> a.label().equals("Partner with Ellis (16 Iron Ingot)")));
        var other = ContractPresentation.view(base(ELLIS, trusted), ELLIS, trusted,
                facts(Optional.empty(), 0, 2, Optional.of(partnership(NICO, DAY.plusDays(3))), false, true, 0, false), CAST, CHICAGO);
        assertTrue(other.line().contains("You already partner with Nico Reed until " + DAY.plusDays(3) + "."));
    }

    @Test void aRankOnlyOfferIsHiddenWhileTheRankPerkIsNotLive() {
        var trusted = state(NICO, 3);
        var hidden = ContractPresentation.view(base(NICO, trusted), NICO, trusted,
                facts(Optional.empty(), 1, 2, Optional.empty(), false, true, 0, false), CAST, CHICAGO);
        assertTrue(hidden.actions().stream().noneMatch(a -> a.kind() == ResidentAction.Kind.PARTNER));
        var live = ContractPresentation.view(base(NICO, trusted), NICO, trusted,
                facts(Optional.empty(), 1, 2, Optional.empty(), false, true, 0, true), CAST, CHICAGO);
        assertTrue(live.actions().stream().anyMatch(a -> a.kind() == ResidentAction.Kind.PARTNER));
        var top = new ContractPresentation.Facts(ON, true, true, true, Optional.empty(), 1, 2, Optional.empty(), false, true, 0,
                true, Set.of(), NOW, DAY);
        assertTrue(ContractPresentation.view(base(NICO, trusted), NICO, trusted, top, CAST, CHICAGO).line()
                .contains(ContractPresentation.ADVERTISING_TOP));
    }

    @Test void aHeldHandOverBlocksAndNamesItsReference() {
        var d = ON.find("mara-wall-stone").orElseThrow();
        var held = new TownContract(UUID.randomUUID(), PLAYER, d, NOW.minusSeconds(60), DAY, 1,
                NOW.minusSeconds(60).plusSeconds(d.durationMinutes() * 60L), TownContract.Status.AMBIGUOUS, UUID.randomUUID(), null, null);
        var view = ContractPresentation.view(base(MARA, state(MARA, 0)), MARA, state(MARA, 0),
                facts(Optional.of(held), 1, 0, Optional.empty(), false, false, 0, false), CAST, CHICAGO);
        assertTrue(view.actions().isEmpty());
        assertTrue(view.line().contains("Your last handover is waiting for staff review. Reference: " + held.contractId()));
    }

    @Test void timesUseTheConfiguredZoneAndResetHour() {
        // A non-default zone and reset hour, so nothing is hard-coded to 4 AM or UTC.
        Instant winter = Instant.parse("2026-01-15T12:00:00Z");
        var berlin = new EconomyClock(Clock.fixed(winter, ZoneOffset.UTC), ZoneId.of("Europe/Berlin"), 6);
        assertEquals("You've taken a contract today. Come back after the daily reset at 6 AM CET.",
                ContractPresentation.dailyLimit(berlin, winter));
        assertEquals("13:30 CET", ContractPresentation.deadline(Instant.parse("2026-01-15T12:30:00Z"), berlin));
        // The shipped default here says Chicago time, in daylight saving in October.
        assertEquals("4 AM CDT", ContractPresentation.resetTime(CHICAGO, NOW));
        var d = ON.find("nico-coal-run").orElseThrow();
        var t = new TownContract(UUID.randomUUID(), PLAYER, d, NOW, DAY, 1, NOW.plusSeconds(10_800), TownContract.Status.ACTIVE, null, null, null);
        assertEquals("The contract is in your journal. Bring 16 Coal by 13:00 CDT.", ContractPresentation.accepted(t, CHICAGO));
    }

    /** A 24-hour contract taken at 06:40 CDT is due at 06:40 the next day. Without the day it read as already passed. */
    @Test void aDeadlineOnALaterDaySaysWhichDay() {
        Instant taken = Instant.parse("2026-10-14T11:40:00Z");
        var chicago = new EconomyClock(Clock.fixed(taken, java.time.ZoneOffset.UTC), java.time.ZoneId.of("America/Chicago"), 4);
        assertEquals("06:40 CDT", ContractPresentation.deadline(taken, chicago), "the same day shows the time only");
        assertEquals("09:40 CDT", ContractPresentation.deadline(taken.plusSeconds(3 * 3600), chicago));
        assertEquals("tomorrow 06:40 CDT", ContractPresentation.deadline(taken.plusSeconds(24 * 3600), chicago));
        assertEquals("Oct 16 06:40 CDT", ContractPresentation.deadline(taken.plusSeconds(48 * 3600), chicago));
        // After local midnight is tomorrow for the player, though the 4 AM economy day has not turned yet.
        assertEquals("tomorrow 00:10 CDT", ContractPresentation.deadline(Instant.parse("2026-10-15T05:10:00Z"), chicago));
        var wall = ON.find("mara-wall-stone").orElseThrow();
        var t = new TownContract(UUID.randomUUID(), PLAYER, wall, taken, java.time.LocalDate.of(2026, 10, 14), 1,
                taken.plusSeconds(wall.durationMinutes() * 60L), TownContract.Status.ACTIVE, null, null, null);
        assertEquals("The contract is in your journal. Bring 64 Cobblestone by tomorrow 06:40 CDT.", ContractPresentation.accepted(t, chicago));
    }

    @Test void theTopicGoesAfterBusinessAndConfirmationsKeepOnlyTheirButtons() {
        var state = state(ELLIS, 3);
        var view = ContractPresentation.withTopic(base(ELLIS, state), true);
        int business = view.topics().indexOf(ResidentDialogue.Topic.BUSINESS);
        assertEquals(ResidentDialogue.Topic.CONTRACTS, view.topics().get(business + 1));
        assertEquals(base(ELLIS, state).topics(), ContractPresentation.withTopic(base(ELLIS, state), false).topics());
        var confirm = ContractPresentation.confirmPartner(ELLIS, ON.partnerOffer(ELLIS.id()).orElseThrow(), true, 7, false, false);
        assertTrue(confirm.confirmation());
        assertEquals(List.of("Confirm", "Back"), confirm.actions().stream().map(ResidentAction::label).toList());
        assertEquals(confirm, ContractPresentation.withTopic(confirm, true));
        assertEquals("Ellis takes 16 Iron Ingot from your inventory. For 7 days, my fee is 40% lower for you. You can have one"
                + " partner at a time. Nothing is refunded if you end it early.", confirm.line());
        assertEquals("Partner with Ellis Ward", confirm.name());
        var end = ContractPresentation.confirmEnd(ELLIS, UUID.randomUUID());
        assertTrue(end.confirmation());
        assertEquals("End your partnership with Ellis Ward now? Nothing is refunded.", end.line());
    }

    @Test void theBusinessLineIsUnchangedWhilePartnershipsAreOff() {
        // -1 is a stranger with no memory at all.
        for (int days : new int[] {-1, 0, 3})
            for (int variant = 0; variant < 2; variant++) {
                var state = state(MARA, days);
                String off = ResidentDialogue.reply(MARA, state, ResidentDialogue.Topic.BUSINESS, 1000, variant, null, false).line();
                String legacy = days >= 3
                        ? (variant == 0 ? "We have a good history. There are no partnership offers available just now."
                                : "I value the history we've built. There are no business offers available.")
                        : (variant == 0 ? "I prefer to know people before discussing business. There are no offers available."
                                : "I'm still getting to know you. There are no business offers available.");
                assertEquals(legacy, off);
                assertEquals(legacy, ResidentDialogue.reply(MARA, state, ResidentDialogue.Topic.BUSINESS, 1000, variant).line());
            }
        assertEquals("We have a good history. Ask me about contracts and partners.",
                ResidentDialogue.reply(MARA, state(MARA, 3), ResidentDialogue.Topic.BUSINESS, 1000, 0, null, true).line());
        assertEquals("I'm still getting to know you. Ask me about contracts.",
                ResidentDialogue.reply(MARA, state(MARA, 0), ResidentDialogue.Topic.BUSINESS, 1000, 1, null, true).line());
    }

    @Test void theLongestTitleAndMaterialStillFitTheButton() {
        // A title at the config maximum and the longest material name a definition allows, at full quantity.
        var entry = new HashMap<String, Object>(Map.of("id", "long-title", "giver", MARA.id().toString(),
                "title", "T".repeat(ContractDefinition.MAX_TITLE), "description", "Bring me these.", "material", "A".repeat(64),
                "quantity", ContractDefinition.MAX_QUANTITY, "minimum-stage", "STRANGER", "duration-minutes", 60));
        var catalogue = ContractCatalogue.load(Map.of("town.contracts.enabled", true,
                "town.contracts.definitions", List.of(entry))::get, CAST, name -> true);
        assertEquals(0, catalogue.skippedCount(), "config accepts it");
        var stranger = state(MARA, -1);
        var view = ContractPresentation.view(base(MARA, stranger), MARA, stranger, new ContractPresentation.Facts(catalogue, true,
                false, false, Optional.empty(), 0, 0, Optional.empty(), false, false, 0, false, Set.of(), NOW, DAY), CAST, CHICAGO);
        String label = view.actions().getFirst().label();
        String tail = " (" + ContractDefinition.MAX_QUANTITY + " A" + "a".repeat(63) + ")";
        assertEquals(ResidentAction.MAX_LABEL, label.length(), label);
        assertTrue(label.startsWith("Take: TTT") && label.endsWith("T..." + tail), label);
        // Short labels are left alone, and a long name in the partner button is cut the same way.
        assertEquals("Take: Wall stone (64 Cobblestone)", ContractPresentation.fit("Take: Wall stone", " (64 Cobblestone)"));
        String partner = ContractPresentation.fit("Partner with " + "N".repeat(64), tail);
        assertEquals(ResidentAction.MAX_LABEL, partner.length());
        assertTrue(partner.startsWith("Partner with NNN") && partner.endsWith("N..." + tail), partner);
    }

    @Test void theLongestNameAndOfferStillFitThePartnerButtonTheViewBuilds() {
        // A file-cast resident with the longest first name a 64-character name allows, and an offer at the config limits.
        String yaml = "version: 1\nresidents:\n  - key: long-name\n    name: " + "N".repeat(58) + " Marsh\n    household: Marsh"
                + "\n    generation: 1\n    occupation: COOK\n    means: STEADY\n    life-stage: ADULT"
                + "\n    temperament: DIRECT\n    preference: CLEAR_TERMS\n";
        var cast = com.prosperitysmp.npc.resident.ResidentCastFile.read(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var person = cast.byKey("long-name").orElseThrow();
        var offer = new HashMap<String, Object>(Map.of("resident", person.id().toString(), "material", "A".repeat(64),
                "quantity", ContractDefinition.MAX_QUANTITY, "fee-discount-percent", 40, "rank-bonus-points", 0,
                "pitch", "Bring me these and I cut my fee for you."));
        var catalogue = ContractCatalogue.load(Map.of("town.contracts.enabled", true, "town.partnerships.enabled", true,
                "town.partnerships.offers", List.of(offer))::get, cast, name -> true);
        // The long offer must load, or the fit check below proves nothing. (The default contracts name Mara, Ellis and Nico, who are not in this cast.)
        assertEquals(ContractDefinition.MAX_QUANTITY, catalogue.partnerOffer(person.id()).orElseThrow().quantity());
        var trusted = state(person, 3);
        var view = ContractPresentation.view(base(person, trusted), person, trusted, new ContractPresentation.Facts(catalogue, true, true,
                false, Optional.empty(), 0, 2, Optional.empty(), false, true, 0, false, Set.of(person.id()), NOW, DAY), cast, CHICAGO);
        var partner = view.actions().stream().filter(a -> a.kind() == ResidentAction.Kind.PARTNER).findFirst().orElseThrow();
        assertEquals(ResidentAction.MAX_LABEL, partner.label().length(), partner.label());
        assertTrue(partner.label().startsWith("Partner with NNN") && partner.label().endsWith(" A" + "a".repeat(63) + ")"), partner.label());
    }

    @Test void aConfirmGoesAheadOnlyForTheOfferItShowedWhileAPerkIsLive() {
        var ellis = ON.partnerOffer(ELLIS.id()).orElseThrow();
        UUID shown = ContractPresentation.confirmPartner(ELLIS, ellis, true, 7, false, false).actions().getFirst().taskId();
        assertEquals(ContractPresentation.offerKey(ellis), shown);
        assertTrue(ContractPresentation.confirmStillValid(Optional.of(ellis), shown, false), "the unchanged offer still confirms");
        // Config reloaded between the screen and the click: other items, another amount or other perks are refused.
        for (var changed : List.of(
                new PartnerOffer(ellis.residentId(), ellis.material(), ellis.quantity() + 16, ellis.feeDiscountPct(), 0, ellis.pitch()),
                new PartnerOffer(ellis.residentId(), "COPPER_INGOT", ellis.quantity(), ellis.feeDiscountPct(), 0, ellis.pitch()),
                new PartnerOffer(ellis.residentId(), ellis.material(), ellis.quantity(), 10, 0, ellis.pitch())))
            assertFalse(ContractPresentation.confirmStillValid(Optional.of(changed), shown, true), changed.toString());
        assertFalse(ContractPresentation.confirmStillValid(Optional.empty(), shown, true));
        // A rank-only offer: the rank switch went off between the screen and the click.
        var nico = ON.partnerOffer(NICO.id()).orElseThrow();
        UUID nicoShown = ContractPresentation.confirmPartner(NICO, nico, true, 7, true, false).actions().getFirst().taskId();
        assertTrue(ContractPresentation.confirmStillValid(Optional.of(nico), nicoShown, true));
        assertFalse(ContractPresentation.confirmStillValid(Optional.of(nico), nicoShown, false));
        // Renew is offered only while the perk does something.
        var trusted = state(NICO, 3);
        var live = ContractPresentation.view(base(NICO, trusted), NICO, trusted,
                facts(Optional.empty(), 1, 2, Optional.of(partnership(NICO, DAY)), false, true, 0, true), CAST, CHICAGO);
        assertTrue(live.actions().stream().anyMatch(a -> a.kind() == ResidentAction.Kind.PARTNER), live.actions().toString());
        var dead = ContractPresentation.view(base(NICO, trusted), NICO, trusted,
                facts(Optional.empty(), 1, 2, Optional.of(partnership(NICO, DAY)), false, true, 0, false), CAST, CHICAGO);
        assertEquals(List.of(ResidentAction.Kind.PARTNER_END), dead.actions().stream().map(ResidentAction::kind).toList());
        // The key is part of the action: a confirm without one, or a partner button with one, is not built.
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.PARTNER_CONFIRM,
                ContractDefinition.PARTNER_START, null, ELLIS.id(), "Confirm"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.PARTNER,
                ContractDefinition.PARTNER_START, shown, ELLIS.id(), "Partner"));
    }

    /** The controller needs Bukkit, so both checks are pinned in its source, ahead of the confirm screen and the take. */
    @Test void theControllerChecksTheOfferBeforeTheScreenAndBeforeTheTake() throws Exception {
        String c = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/interaction/ResidentEncounterController.java"));
        int handover = c.indexOf("private void contractHandover(");
        int check = c.indexOf("&& !ContractPresentation.confirmStillValid(offer, selected.taskId(), plugin.partnerRankLive())) {", handover);
        int take = c.indexOf("runtime.partner(player, offer.orElseThrow()", handover);
        assertTrue(handover > 0 && check > handover && take > check, "confirm re-checked before the take");
        assertTrue(c.substring(check, take).contains("note(player, ContractPresentation.UNAVAILABLE); return;"));
        int partner = c.indexOf("case PARTNER -> {");
        int live = c.indexOf("if (!ContractPresentation.offerLive(offer, flags.rankLive())) result = ContractPresentation.UNAVAILABLE;", partner);
        int screen = c.indexOf("ContractPresentation.confirmPartner(profile, offer.get()", partner);
        assertTrue(partner > 0 && live > partner && screen > live, "the confirm screen needs a live offer");
    }

    @Test void aTypedContractsTopicIsUnknownWhileHidden() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ResidentEncounterController.typedTopic("contracts", false));
        assertEquals(ResidentDialogue.Topic.CONTRACTS, ResidentEncounterController.typedTopic("CONTRACTS", true));
        assertEquals(ResidentDialogue.Topic.BUSINESS, ResidentEncounterController.typedTopic("business", false));
        assertThrows(IllegalArgumentException.class, () -> ResidentEncounterController.typedTopic("nope", true));
        // Both typed paths parse with it, so a hidden topic fails before the session check.
        String c = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/interaction/ResidentEncounterController.java"));
        assertFalse(c.contains("Topic.valueOf(args["));
        assertTrue(c.contains("choose(player, open.get().token(), typedTopic(args[1], contractsTopicShown()));"));
        assertTrue(c.contains("choose(player, strictUuid(args[1]), typedTopic(args[2], contractsTopicShown()));"));
    }

    private static final Pattern NOT_NOT_BUT = Pattern.compile("(?i)\\bnot\\b[^.]*,\\s*not\\b[^.]*,?\\s*but\\b");

    @Test void everyLineIsPlainCopy() {
        var lines = new ArrayList<String>();
        var d = ON.find("nico-coal-run").orElseThrow();
        var contract = Optional.of(active(d));
        var partner = Optional.of(partnership(ELLIS, DAY));
        for (Code code : Code.values()) {
            lines.add(ContractPresentation.result(code, contract, partner, 2, ON, CAST, CHICAGO, NOW));
            lines.add(ContractPresentation.result(code, Optional.empty(), Optional.empty(), 0, ON, CAST, CHICAGO, NOW));
        }
        for (var field : ContractPresentation.class.getFields())
            if (field.getType() == String.class) try { lines.add((String) field.get(null)); } catch (IllegalAccessException e) { fail(e); }
        lines.add(ContractPresentation.notEnough(64, "COBBLESTONE", 20));
        lines.add(ContractPresentation.partnerDailyLimit(CHICAGO, NOW));
        lines.add(ContractPresentation.kept(1));
        lines.add(ContractPresentation.kept(3));
        lines.add(ContractPresentation.footer(2));
        for (var offer : ON.offersByResident().values()) {
            lines.add(offer.pitch());
            for (boolean rank : new boolean[] {true, false})
                lines.add(ContractPresentation.confirmPartner(CAST.byId(offer.residentId()).orElseThrow(), offer, true, 7, rank, true).line());
        }
        for (var definition : ON.definitions()) { lines.add(definition.title()); lines.add(definition.description()); }
        for (var person : List.of(MARA, ELLIS, NICO))
            for (int days : new int[] {0, 3})
                lines.add(ContractPresentation.view(base(person, state(person, days)), person, state(person, days),
                        facts(Optional.empty(), 0, 1, Optional.empty(), false, true, 0, true), CAST, CHICAGO).line());
        lines.add(new OwnerDeskData.Partner("Ellis Ward", DAY, 40, false, false).line());
        lines.add(new OwnerDeskData.Partner("Nico Reed", DAY, 0, true, false).line());
        lines.add(new OwnerDeskData.Partner("Nico Reed", DAY, 0, true, true).line());
        lines.addAll(NpcTownCopySamples.all());
        lines.add(ResidentDialogue.businessWithPartners(state(MARA, 0)));
        lines.add(ResidentDialogue.businessWithPartners(state(MARA, 3)));
        assertTrue(lines.size() > 60);
        for (String line : lines) {
            assertNotNull(line);
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains("–"), line);
            assertTrue(line.codePoints().noneMatch(cp -> cp >= 0x1F000 || cp >= 0x2600 && cp <= 0x27BF), line);
            assertFalse(NOT_NOT_BUT.matcher(line).find(), line);
        }
        // The lint itself can fail.
        assertTrue(NOT_NOT_BUT.matcher("Not cash, not items, but trust.").find());
    }
}
