package com.prosperitysmp.npc.commerce;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded, byte-exact immutable serialization; never a material-name reconstruction. */
public final class CustodyBytes {
    public static final int MAX_BYTES = 65_536;
    private final byte[] bytes;
    private final String sha256;

    public CustodyBytes(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Custody serialization exceeds bounds");
        this.bytes = bytes.clone();
        try { sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public byte[] bytes() { return bytes.clone(); }
    public String sha256() { return sha256; }
    public int size() { return bytes.length; }
    @Override public boolean equals(Object other) {
        return other instanceof CustodyBytes payload && Arrays.equals(bytes, payload.bytes);
    }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return "CustodyBytes[size=" + bytes.length + ", sha256=" + sha256 + "]"; }
}
