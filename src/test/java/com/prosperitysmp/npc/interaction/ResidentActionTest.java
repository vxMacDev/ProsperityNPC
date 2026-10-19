package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.interaction.ResidentAction.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A server-authored action is only constructible with the identifiers its kind needs and none it must not carry. */
class ResidentActionTest {
    private static final UUID TASK = new UUID(0, 1);
    private static final UUID RESIDENT = new UUID(0, 2);
    private static final Set<Kind> TASK_KINDS = EnumSet.of(Kind.COMPLETE, Kind.CANCEL, Kind.DIRECTIONS, Kind.COLLECT,
            Kind.HANDOVER, Kind.CONTRACT_CANCEL, Kind.CONTRACT_DIRECTIONS, Kind.PARTNER_END, Kind.PARTNER_END_CONFIRM);
    private static final Set<Kind> OFFER_KINDS = EnumSet.of(Kind.ACCEPT, Kind.TRAIN, Kind.CONTRACT_ACCEPT);

    @Test void theLabelLimitIsOneHundred() {
        assertEquals(100, ResidentAction.MAX_LABEL);
    }

    @Test void everyKindIsCoveredByOneOfTheTestedGroups() {
        Set<Kind> covered = EnumSet.of(Kind.ORDER, Kind.VISIT, Kind.PARTNER, Kind.PARTNER_CONFIRM, Kind.CONTRACT_BACK);
        covered.addAll(TASK_KINDS);
        covered.addAll(OFFER_KINDS);
        assertEquals(EnumSet.allOf(Kind.class), covered);
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"COMPLETE", "CANCEL", "DIRECTIONS", "COLLECT", "HANDOVER",
            "CONTRACT_CANCEL", "CONTRACT_DIRECTIONS", "PARTNER_END", "PARTNER_END_CONFIRM"})
    void aTaskActionNeedsATaskIdAndNoDefinitionId(Kind kind) {
        ResidentAction ok = new ResidentAction(kind, null, TASK, RESIDENT, "Label");
        assertEquals(kind, ok.kind());
        assertEquals(TASK, ok.taskId());
        assertNull(ok.definitionId());
        assertEquals(RESIDENT, ok.recipientId());
        assertEquals("Label", ok.label());
        assertNull(ok.order());
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, null, null, RESIDENT, "Label"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, "some-id", TASK, RESIDENT, "Label"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, "some-id", null, RESIDENT, "Label"));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"ACCEPT", "TRAIN", "CONTRACT_ACCEPT"})
    void anOfferActionNeedsACanonicalDefinitionIdAndNoTaskId(Kind kind) {
        assertEquals("mara-wall-stone", new ResidentAction(kind, "mara-wall-stone", null, RESIDENT, "Take it").definitionId());
        assertDoesNotThrow(() -> new ResidentAction(kind, "a".repeat(48), null, RESIDENT, "Take it"));
        assertDoesNotThrow(() -> new ResidentAction(kind, "_-0", null, RESIDENT, "Take it"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, "a".repeat(49), null, RESIDENT, "Take it"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, "mara-wall-stone", TASK, RESIDENT, "Take it"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, null, null, RESIDENT, "Take it"));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"ACCEPT", "TRAIN", "CONTRACT_ACCEPT"})
    void anOfferActionRefusesAnUncanonicalDefinitionId(Kind kind) {
        for (String bad : new String[] {"", "Upper", "has space", "semi;colon", "slash/x", "dot.x", "new\nline", "colon:x"})
            assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, bad, null, RESIDENT, "Take it"), bad);
    }

    @ParameterizedTest @ValueSource(strings = {"market", "leisure"})
    void aPublicStopVisitNamesAMarketOrLeisureStop(String stop) {
        ResidentAction visit = new ResidentAction(Kind.VISIT, stop, null, RESIDENT, "Visit");
        assertEquals(stop, visit.definitionId());
    }

    @ParameterizedTest @ValueSource(strings = {"home", "work", "Market", "MARKET", "", "market ", "other"})
    void aPublicStopVisitRefusesAnyOtherStop(String stop) {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.VISIT, stop, null, RESIDENT, "Visit"));
    }

    @Test void aPublicStopVisitCarriesNoTaskId() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.VISIT, "market", TASK, RESIDENT, "Visit"));
    }

    @Test void aPublicStopVisitWithoutAStopIsNotConstructible() {
        assertThrows(RuntimeException.class, () -> new ResidentAction(Kind.VISIT, null, null, RESIDENT, "Visit"));
    }

    @Test void aMissingDefinitionIdOnAVisitOrPartnerActionIsRefusedAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.VISIT, null, null, RESIDENT, "Visit"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.PARTNER, null, null, RESIDENT, "Partner"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.PARTNER_CONFIRM, null, TASK, RESIDENT, "Confirm"));
    }

    @ParameterizedTest @ValueSource(strings = {"partner-start", "partner-renew"})
    void aPartnerActionHasNoTaskId(String definition) {
        assertDoesNotThrow(() -> new ResidentAction(Kind.PARTNER, definition, null, RESIDENT, "Partner"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.PARTNER, definition, TASK, RESIDENT, "Partner"));
    }

    @ParameterizedTest @ValueSource(strings = {"partner-start", "partner-renew"})
    void aPartnerConfirmCarriesTheOfferKeyAsItsTaskId(String definition) {
        assertEquals(TASK, new ResidentAction(Kind.PARTNER_CONFIRM, definition, TASK, RESIDENT, "Confirm").taskId());
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.PARTNER_CONFIRM, definition, null, RESIDENT, "Confirm"));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"PARTNER", "PARTNER_CONFIRM"})
    void aPartnerActionRefusesAnyOtherDefinition(Kind kind) {
        UUID task = kind == Kind.PARTNER ? null : TASK;
        for (String bad : new String[] {"partner", "partner-start ", "Partner-Start", "partner-end", "", "mara-wall-stone"})
            assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, bad, task, RESIDENT, "Partner"), bad);
        assertThrows(RuntimeException.class, () -> new ResidentAction(kind, null, task, RESIDENT, "Partner"));
    }

    @Test void aBackActionCarriesNoIdentifiersButTheResident() {
        ResidentAction back = new ResidentAction(Kind.CONTRACT_BACK, null, null, RESIDENT, "Back");
        assertNull(back.definitionId());
        assertNull(back.taskId());
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CONTRACT_BACK, "x", null, RESIDENT, "Back"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CONTRACT_BACK, null, TASK, RESIDENT, "Back"));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"ORDER"}, mode = EnumSource.Mode.EXCLUDE)
    void everyOtherKindNeedsARecipient(Kind kind) {
        UUID task = TASK_KINDS.contains(kind) || kind == Kind.PARTNER_CONFIRM ? TASK : null;
        String definition = switch (kind) {
            case ACCEPT, TRAIN, CONTRACT_ACCEPT -> "some-id";
            case VISIT -> "market";
            case PARTNER, PARTNER_CONFIRM -> "partner-start";
            default -> null;
        };
        assertDoesNotThrow(() -> new ResidentAction(kind, definition, task, RESIDENT, "Label"));
        assertThrows(NullPointerException.class, () -> new ResidentAction(kind, definition, task, null, "Label"));
    }

    @Test void aMissingKindIsRefused() {
        assertThrows(NullPointerException.class, () -> new ResidentAction(null, null, TASK, RESIDENT, "Label"));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void aBlankLabelIsRefused(String label) {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, label));
    }

    @Test void aNullLabelIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, null));
    }

    @Test void aLabelOfExactlyOneHundredCharactersIsKeptAndOneMoreIsNot() {
        assertEquals(100, new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, "x".repeat(100)).label().length());
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, "x".repeat(101)));
    }

    @ParameterizedTest @ValueSource(strings = {"two\nlines", "tab\there", "return\rhere", "nul\u0000here", "esc\u001bhere", "del\u007fhere"})
    void aLabelWithAControlCharacterIsRefused(String label) {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, label));
    }

    @Test void aLabelWithOrdinarySymbolsIsKept() {
        String label = "Cancel errand (today's slot stays used) - 5% off & more: yes?";
        assertEquals(label, new ResidentAction(Kind.CANCEL, null, TASK, RESIDENT, label).label());
    }

    private static CommerceOrder order(UUID resident, String catalogue) {
        UUID nonce = new UUID(5, 5);
        UUID player = new UUID(6, 6);
        LocalDate day = LocalDate.of(2026, 10, 14);
        return new CommerceOrder(CommerceOrder.idFor(nonce, player, ItemCustody.Source.MERCHANT_GOODS, day), nonce, player,
                resident, ItemCustody.Source.MERCHANT_GOODS, catalogue, "STONE", 32, 1444, day,
                Instant.parse("2026-10-14T22:00:00Z"), 0);
    }

    @Test void anOrderActionBindsTheQuoteIdCatalogueAndResident() {
        CommerceOrder quote = order(RESIDENT, "supplies-stone");
        ResidentAction action = new ResidentAction(Kind.ORDER, "supplies-stone", quote.orderId(), RESIDENT, "Buy", quote);
        assertSame(quote, action.order());
        assertEquals(quote.orderId(), action.taskId());
    }

    @Test void anOrderActionRefusesAQuoteThatDoesNotMatchItsFields() {
        CommerceOrder quote = order(RESIDENT, "supplies-stone");
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.ORDER, "supplies-stone", TASK, RESIDENT, "Buy", quote));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.ORDER, "supplies-coal", quote.orderId(), RESIDENT, "Buy", quote));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.ORDER, "supplies-stone", quote.orderId(), new UUID(9, 9), "Buy", quote));
    }

    @Test void anOrderActionWithoutAQuoteIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(Kind.ORDER, "supplies-stone", TASK, RESIDENT, "Buy"));
    }

    @ParameterizedTest @EnumSource(value = Kind.class, names = {"ORDER"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAnOrderActionMayCarryAQuote(Kind kind) {
        CommerceOrder quote = order(RESIDENT, "supplies-stone");
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, "supplies-stone", quote.orderId(), RESIDENT, "Buy", quote));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(kind, null, null, RESIDENT, "Buy", quote));
    }

    @Test void theFiveArgumentConstructorLeavesTheQuoteEmpty() {
        assertNull(new ResidentAction(Kind.DIRECTIONS, null, TASK, RESIDENT, "Find Nico").order());
    }
}
