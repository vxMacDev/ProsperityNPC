package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.commerce.CommerceOrder;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A server-authored action attached to one encounter ticket, not a client-supplied entitlement. */
public record ResidentAction(Kind kind, String definitionId, UUID taskId, UUID recipientId, String label,
                             CommerceOrder order) {
    public enum Kind {
        ACCEPT, COMPLETE, CANCEL, DIRECTIONS, VISIT, TRAIN, ORDER, COLLECT,
        /** Contracts and partners (town.contracts / town.partnerships). definitionId is the contract ID. */
        CONTRACT_ACCEPT,
        /** taskId is the contract ID for these three. */
        HANDOVER, CONTRACT_CANCEL, CONTRACT_DIRECTIONS,
        /**
         * definitionId is partner-start or partner-renew for these two. PARTNER has no taskId; PARTNER_CONFIRM's taskId
         * is the key of the offer its confirmation showed (ContractPresentation.offerKey).
         */
        PARTNER, PARTNER_CONFIRM,
        /** taskId is the partnership ID for these two. */
        PARTNER_END, PARTNER_END_CONFIRM,
        /** Back to the contracts view from a confirmation. No IDs. */
        CONTRACT_BACK
    }
    /** The longest label a button or chat line takes. */
    public static final int MAX_LABEL = 100;

    public ResidentAction(Kind kind, String definitionId, UUID taskId, UUID recipientId, String label) {
        this(kind, definitionId, taskId, recipientId, label, null);
    }
    public ResidentAction {
        Objects.requireNonNull(kind);
        if (label == null || label.isBlank() || label.length() > MAX_LABEL || label.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid resident action label");
        if (kind == Kind.ORDER) {
            if (order == null || !order.orderId().equals(taskId) || !order.catalogueId().equals(definitionId)
                    || !order.residentId().equals(recipientId)) throw new IllegalArgumentException("Unbound commerce quote");
        } else if (order != null) throw new IllegalArgumentException("Unexpected commerce quote");
        else if (kind == Kind.VISIT) {
            if (!Set.of("market", "leisure").contains(definitionId) || taskId != null)
                throw new IllegalArgumentException("Invalid public-stop action");
        } else if (kind == Kind.ACCEPT || kind == Kind.TRAIN || kind == Kind.CONTRACT_ACCEPT) {
            if (definitionId == null || !definitionId.matches("[a-z0-9_-]{1,48}") || taskId != null)
                throw new IllegalArgumentException("Invalid offer action");
        } else if (kind == Kind.PARTNER || kind == Kind.PARTNER_CONFIRM) {
            if (!Set.of("partner-start", "partner-renew").contains(definitionId) || (kind == Kind.PARTNER) != (taskId == null))
                throw new IllegalArgumentException("Invalid partnership action");
        } else if (kind == Kind.CONTRACT_BACK) {
            if (definitionId != null || taskId != null) throw new IllegalArgumentException("Invalid back action");
        } else if (taskId == null || definitionId != null) throw new IllegalArgumentException("Invalid task action");
        Objects.requireNonNull(recipientId);
    }
}
