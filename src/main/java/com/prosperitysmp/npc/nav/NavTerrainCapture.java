package com.prosperitysmp.npc.nav;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded terrain snapshot. The reader is used only during owner-thread capture. */
public final class NavTerrainCapture implements NavScanLogic.Terrain {
    public static final byte SUPPORT = 1;
    public static final byte CLEAR = 2;
    /** Bits 2-4 hold the support's {@link NavSurface} code. */
    public static final int SURFACE_SHIFT = 2;
    public static final int SURFACE_MASK = 0b11100;
    @FunctionalInterface public interface Reader { byte read(int x, int y, int z); }
    private final NavScanLogic.Bounds bounds;
    private final int height, depth, top;
    private final byte[] cells;
    private int next;

    public NavTerrainCapture(NavScanLogic.Bounds bounds, int headroom, int maxStep) {
        NavScanLogic.validate(bounds, headroom, maxStep);
        this.bounds = bounds;
        top = Math.addExact(bounds.maxY(), headroom);
        height = Math.toIntExact((long) top - bounds.minY() + 1);
        depth = Math.toIntExact((long) bounds.maxZ() - bounds.minZ() + 1);
        cells = new byte[Math.toIntExact(Math.multiplyExact(bounds.columns(), height))];
    }

    /** A slice is bounded by both time and reads; an in-progress read is never interrupted. */
    public boolean advance(Reader reader, int readBudget, long nanosBudget, LongSupplier clock) {
        Objects.requireNonNull(reader);
        Objects.requireNonNull(clock);
        if (readBudget < 1 || readBudget > 4096 || nanosBudget < 1) {
            throw new IllegalArgumentException("invalid terrain capture slice budget");
        }
        long start = clock.getAsLong();
        for (int reads = 0; next < cells.length && reads < readBudget; reads++) {
            if (clock.getAsLong() - start >= nanosBudget) break;
            int column = next / height;
            int x = bounds.minX() + column / depth;
            int z = bounds.minZ() + column % depth;
            int y = bounds.minY() + next % height;
            byte value = reader.read(x, y, z);
            if ((value & ~(SUPPORT | CLEAR | SURFACE_MASK)) != 0) throw new IllegalArgumentException("unknown terrain flags");
            int surface = (value & SURFACE_MASK) >> SURFACE_SHIFT;
            if (surface > NavSurface.CROSSING.code()) throw new IllegalArgumentException("unknown surface code");
            if (surface != 0 && (value & SUPPORT) == 0) throw new IllegalArgumentException("surface without support");
            cells[next++] = value;
        }
        return complete();
    }

    public boolean complete() { return next == cells.length; }
    public int captured() { return next; }
    public int total() { return cells.length; }

    private byte at(int x, int y, int z) {
        if (!complete()) throw new IllegalStateException("terrain capture is incomplete");
        if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()
                || y < bounds.minY() || y > top) throw new IllegalArgumentException("outside terrain capture");
        int index = Math.toIntExact((((long) x - bounds.minX()) * depth + z - bounds.minZ())
                * height + y - bounds.minY());
        return cells[index];
    }

    @Override public boolean isSupport(int x, int y, int z) { return (at(x, y, z) & SUPPORT) != 0; }
    @Override public boolean isClear(int x, int y, int z) { return (at(x, y, z) & CLEAR) != 0; }
    @Override public NavSurface surface(int x, int y, int z) {
        return NavSurface.fromCode((at(x, y, z) & SURFACE_MASK) >> SURFACE_SHIFT);
    }
}
