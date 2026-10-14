package com.prosperitysmp.npc.economy;

/**
 * Lifecycle of an economy event:
 * <pre>
 *   PENDING -&gt; CLAIMED -&gt; PAID
 *                       -&gt; FAILED
 *                       -&gt; AMBIGUOUS   (crash after money moved, before ack; never auto-retried)
 * </pre>
 */
public enum EventState {
    PENDING,
    CLAIMED,
    PAID,
    FAILED,
    AMBIGUOUS
}
