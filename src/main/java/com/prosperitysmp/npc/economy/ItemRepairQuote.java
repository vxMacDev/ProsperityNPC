package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One frozen item hand-in: which home and incident, the cash price it stands in for, the items and what they
 * were worth, and the exact stacks to give back if the home changes after the items are taken. The constructor
 * re-checks every rule the SQL CHECKs enforce, so a quote that is cheaper than cash cannot be built.
 */
public record ItemRepairQuote(UUID collectionId, UUID propertyId, UUID playerId, int ownershipEpoch, LocalDate quoteDay,
                              RepairQuote repair, long cashCents, int ratioBps, long requiredValueCents,
                              long recipeValueCents, String priceVersion, List<Line> lines, List<ReturnStack> returnPlan) {
    public static final int MAX_OBSERVED_UNITS = 65_536;

    /** prototype is one plain item (count 1). beforeUnits is what the player held at the start, storage plus ender chest. */
    public record Line(String material, int quantity, long unitFullCents, CustodyBytes prototype, int beforeUnits) {
        public Line {
            Objects.requireNonNull(prototype);
            if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")
                    || quantity < 1 || quantity > ItemRepairRecipes.MAX_LINE_QUANTITY
                    || unitFullCents < 1 || unitFullCents > ItemRepairRecipes.MAX_UNIT_CENTS
                    || beforeUnits < quantity || beforeUnits > MAX_OBSERVED_UNITS)
                throw new IllegalArgumentException("Invalid item repair line");
        }
    }

    /** One stack to give back, already serialized with its exact count. */
    public record ReturnStack(int lineNo, int quantity, CustodyBytes payload) {
        public ReturnStack {
            Objects.requireNonNull(payload);
            if (lineNo < 0 || lineNo >= ItemRepairRecipes.MAX_LINES || quantity < 1 || quantity > ItemRepairRecipes.MAX_LINE_QUANTITY)
                throw new IllegalArgumentException("Invalid return stack");
        }
    }

    public ItemRepairQuote {
        Objects.requireNonNull(collectionId); Objects.requireNonNull(propertyId); Objects.requireNonNull(playerId);
        Objects.requireNonNull(quoteDay); Objects.requireNonNull(repair);
        lines = List.copyOf(lines);
        returnPlan = List.copyOf(returnPlan);
        if (ownershipEpoch < 0 || quoteDay.getYear() < 1 || quoteDay.getYear() > 9999)
            throw new IllegalArgumentException("Invalid item repair scope");
        if (cashCents != repair.cents() || cashCents < 1 || cashCents > ItemRepairRecipes.MAX_CASH_CENTS)
            throw new IllegalArgumentException("Item repair cash price differs from the incident");
        if (ratioBps < ItemRepairRecipes.MIN_RATIO_BPS || ratioBps > ItemRepairRecipes.MAX_RATIO_BPS)
            throw new IllegalArgumentException("Item repair ratio out of bounds");
        if (requiredValueCents != ItemRepairRecipes.requiredCents(cashCents, ratioBps) || requiredValueCents < cashCents)
            throw new IllegalArgumentException("Item repair required value is not ceil(cash x ratio)");
        if (priceVersion == null || priceVersion.isBlank() || priceVersion.length() > 96
                || priceVersion.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid price version");
        if (lines.isEmpty() || lines.size() > ItemRepairRecipes.MAX_LINES)
            throw new IllegalArgumentException("Item repair needs 1 to 4 lines");
        var materials = new HashSet<String>();
        long value = 0;
        int[] planned = new int[lines.size()];
        for (Line line : lines) {
            if (!materials.add(line.material())) throw new IllegalArgumentException("Duplicate item repair material");
            value = Math.addExact(value, Math.multiplyExact((long) line.quantity(), line.unitFullCents()));
        }
        if (value != recipeValueCents || recipeValueCents < requiredValueCents)
            throw new IllegalArgumentException("Item repair is worth less than its required value");
        if (returnPlan.isEmpty() || returnPlan.size() > ItemRepairRecipes.MAX_LINES)
            throw new IllegalArgumentException("Item repair return plan needs 1 to 4 stacks");
        for (ReturnStack stack : returnPlan) {
            if (stack.lineNo() >= lines.size()) throw new IllegalArgumentException("Return stack names a missing line");
            planned[stack.lineNo()] = Math.addExact(planned[stack.lineNo()], stack.quantity());
        }
        for (int i = 0; i < lines.size(); i++)
            if (planned[i] != lines.get(i).quantity()) throw new IllegalArgumentException("Return plan differs from the items taken");
    }

    public List<Integer> quantities() { return lines.stream().map(Line::quantity).toList(); }
    public List<Integer> beforeUnits() { return lines.stream().map(Line::beforeUnits).toList(); }

    /** Length-prefixed SHA-256 over every field, the same style as the incident repair snapshot. */
    public String quoteSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fields(digest, "item-repair-quote-v1", collectionId, propertyId, playerId, ownershipEpoch, quoteDay,
                    repair.incidentId(), repair.type().name(), repair.snapshotSha256(), cashCents, ratioBps,
                    requiredValueCents, recipeValueCents, priceVersion, lines.size());
            for (Line line : lines)
                fields(digest, line.material(), line.quantity(), line.unitFullCents(), line.prototype().sha256(), line.beforeUnits());
            fields(digest, returnPlan.size());
            for (ReturnStack stack : returnPlan) fields(digest, stack.lineNo(), stack.quantity(), stack.payload().sha256());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void fields(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }
}
