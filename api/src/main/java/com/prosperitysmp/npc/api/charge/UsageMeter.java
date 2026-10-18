package com.prosperitysmp.npc.api.charge;

import com.prosperitysmp.npc.api.Piece;

import java.util.Optional;

/**
 * A meter reports how much a home used in a period (for example power units) or whether it was powered, so a
 * configured bill can charge for it. Registered with Bukkit's ServicesManager under this interface and chosen by id
 * in a bill policy's {@code meter:} key. Added in API 1.1.
 *
 * <p>Capability flags: {@code provides} is {@code units}, {@code powered} or {@code units,powered};
 * {@code unit-label} names the unit for owner text; {@code kind} is {@code period} (use in the period),
 * {@code level} (a current value) or {@code cumulative} (a running total; the core bills the increase).
 */
public interface UsageMeter extends Piece {
    /**
     * Called on the main thread while invoices are issued. It must return at once and never block. Empty means
     * "no reading": that bill line is not charged. A throw is treated the same way.
     */
    Optional<MeterReading> read(MeterQuery query);
}
