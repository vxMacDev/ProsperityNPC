package com.prosperitysmp.npc.api;

/**
 * The adapter API version. Within one MAJOR only default methods are added, and MINOR goes up. Removing or
 * changing anything moves MAJOR. The core refuses a piece whose apiMajor differs from MAJOR or whose apiMinor
 * is greater than MINOR.
 */
public final class ProsperityNpcApi {
    public static final int MAJOR = 1;
    public static final int MINOR = 2;

    private ProsperityNpcApi() { }
}
