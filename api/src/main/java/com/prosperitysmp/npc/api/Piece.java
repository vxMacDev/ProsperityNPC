package com.prosperitysmp.npc.api;

/**
 * One swappable part of ProsperityNPC: a money source or a property backend. A third-party piece is registered
 * with Bukkit's ServicesManager under its interface type and chosen by id in the ProsperityNPC config.
 */
public interface Piece {
    /** Lowercase id, [a-z][a-z0-9-]{1,31}. Built-in ids are reserved and refused for third-party pieces. */
    String id();

    /** Adapters write: {@code return ProsperityNpcApi.MAJOR;} (the constant is inlined when the adapter compiles). */
    int apiMajor();

    /** Adapters write: {@code return ProsperityNpcApi.MINOR;} */
    int apiMinor();

    Capabilities capabilities();

    /** A short label shown by /npcadmin diagnose, for example "unverified". Empty by default. */
    default String label() { return ""; }
}
