package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The offline read of one delivery batch: exact counts from a well-formed file, and nothing from any other file. */
class ItemSaveProofDeliveryReadTest {
    private static final UUID BOT = UUID.fromString("226dba37-26b2-3478-8aad-ada66a4e36a7");
    // The same inventory-only test-server player file the other proof tests use: 24 bread in slot 2, DataVersion 4790.
    private static final String FIXTURE = "H4sIAAAAAAAC/32Su07DMBSGT2h6S1UhlVvHioGRCYHUEcrQBSGhlrFynVNi4tiV7bSBR+BBYOrCwMDT4SaUpjeyZPm+38f/sQdQgFqHGNJHpZkUAI2vGri9XrcDAO5p9H119nmRvM1m7893lx9VqHbFBIWR6sWzQMUB94FLAxXYYz40IyaQKjIy7amUPorBmNGQJFiAIpWxMFZx4NdxMudww9nB72X8/pIfKiR+Dm0u0MIGSgPUJp/qgUdlNJbCXkYX8pNHJBloQ2g40OwVM/YolxRrI6OBIBE6UGKGcEbtuV4sfFTcYv58iqHkPlShiIlRZN6UswLL4SjWlJgUXlXr2igWogmUjJ+Cv6wKuMaGQf0RuR0cWzfK6htZa/bCAth2B9/u3YPGfTy0Y13HYchMn/AYdQWamFDk3JZD5+fodvo7t6WWp9kAkO4mrdvN6j5ZHmDjBf63/GKmHKwrO/BShh+v4zqQE+TbjPLGE7BvlgY5tGX3493Oq+8ajDSk3w9/BrW1EQMAAA==";
    // Eight bread at DataVersion 4790.
    private static final String PAYLOAD = "H4sIAAAAAAAC/+NiYOBgYMpMYeDPzcxLTS5KTCuxSipKTUxhZmBNzi/NK2EAKmBm4HZJLEkMSy0qzszPY2AQ2sYAAG0Bwwk4AAAA";
    private static URLClassLoader loader;
    private static Class<?> compound, accounter, tag;
    private static Method read, write, get, copy, putInt, putByte, putString, putIntArray, put, remove;
    private static ItemSaveProof proof;
    @TempDir Path temporary;

    @BeforeAll static void installedNbt() throws Exception {
        String jar = System.getenv("NPC_ITEM_PROOF_PAPER_JAR"), libraries = System.getenv("NPC_ITEM_PROOF_LIBRARIES");
        assumeTrue(jar != null && libraries != null, "Set paths to the existing installed Paper jar/libraries for NBT linkage tests");
        List<URL> urls = new ArrayList<>();
        urls.add(Path.of(jar).toUri().toURL());
        try (var files = Files.walk(Path.of(libraries))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted(Comparator.reverseOrder()).toList())
                urls.add(path.toUri().toURL());
        }
        loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        proof = new ItemSaveProof(loader);
        assertTrue(proof.available());
        compound = loader.loadClass("net.minecraft.nbt.CompoundTag");
        accounter = loader.loadClass("net.minecraft.nbt.NbtAccounter");
        tag = loader.loadClass("net.minecraft.nbt.Tag");
        Class<?> io = loader.loadClass("net.minecraft.nbt.NbtIo");
        read = io.getMethod("readCompressed", java.io.InputStream.class, accounter);
        write = io.getMethod("writeCompressed", compound, java.io.OutputStream.class);
        get = compound.getMethod("get", String.class);
        copy = compound.getMethod("copy");
        putInt = compound.getMethod("putInt", String.class, int.class);
        putByte = compound.getMethod("putByte", String.class, byte.class);
        putString = compound.getMethod("putString", String.class, String.class);
        putIntArray = compound.getMethod("putIntArray", String.class, int[].class);
        put = compound.getMethod("put", String.class, tag);
        remove = compound.getMethod("remove", String.class);
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    @Test void aDeliveryBatchReadsItsUnitsBothVersionsAndNoMarkers() {
        var read = proof.readSavedDelivery(saved(), batch(BOT, 8)).orElseThrow();
        assertEquals(List.of(24), read.units());
        assertEquals(List.of(24), read.wholeFile());
        assertEquals(4790, read.fileDataVersion());
        assertEquals(List.of(4790), read.recordedDataVersions());
        assertNull(read.handInMarker());
        assertNull(read.returnMarker());
        assertNull(read.deliveryMarker());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 9, 24})
    void aBatchWhoseQuantityIsNotItsPayloadCountReadsNothing(int quantity) {
        assertEquals(Optional.empty(), proof.readSavedDelivery(saved(), batch(BOT, quantity)));
    }

    @Test void anotherPlayersBatchReadsNothing() {
        assertEquals(Optional.empty(), proof.readSavedDelivery(saved(), batch(new UUID(BOT.getMostSignificantBits(),
                BOT.getLeastSignificantBits() + 1), 8)));
    }

    @Test void emptyMissingOversizedOrForeignFilesReadNothing() {
        assertEquals(Optional.empty(), proof.readSavedDelivery(null, batch(BOT, 8)));
        assertEquals(Optional.empty(), proof.readSavedDelivery(new byte[0], batch(BOT, 8)));
        assertEquals(Optional.empty(), proof.readSavedDelivery(saved(), null));
        assertEquals(Optional.empty(), proof.readSavedDelivery(new byte[ItemSaveProof.MAX_FILE_BYTES + 1], batch(BOT, 8)));
        assertEquals(Optional.empty(), proof.readSavedDelivery(new byte[]{1, 2, 3}, batch(BOT, 8)));
        assertEquals(Optional.empty(), proof.readSavedDelivery(Arrays.copyOf(saved(), saved().length / 2), batch(BOT, 8)));
        assertEquals(Optional.empty(), proof.readSavedDelivery(Base64.getDecoder().decode(PAYLOAD), batch(BOT, 8)),
                "an item is not a player file");
    }

    @Test void aProofWithoutTheServerReaderReadsNothing() {
        var unavailable = new ItemSaveProof(ClassLoader.getPlatformClassLoader());
        assertFalse(unavailable.available());
        assertEquals(Optional.empty(), unavailable.readSavedDelivery(saved(), batch(BOT, 8)));
        assertEquals(Optional.empty(), unavailable.readSaved(saved(), BOT, List.of(new CustodyBytes(Base64.getDecoder().decode(PAYLOAD)))));
        assertEquals(Optional.empty(), unavailable.playerFile(temporary, BOT));
    }

    @Test void theEnderChestCountsInStorageAndEquipmentOnlyInTheWholeFile() throws Exception {
        Object root = fixture();
        add(root, "EnderItems", bread(root, 4, (byte) 0));
        add(root, "Inventory", bread(root, 20, (byte) -106));
        add(root, "Inventory", bread(root, 3, (byte) 100));
        var read = proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow();
        assertEquals(List.of(28), read.units(), "24 in storage and 4 in the ender chest");
        assertEquals(List.of(51), read.wholeFile(), "the offhand and boots stacks too");
    }

    @SuppressWarnings("unchecked")
    @Test void breadNestedInAContainerCountsOnlyInTheWholeFile() throws Exception {
        Object root = fixture();
        Object chest = copy.invoke(inventory(root).get(3));
        Object components = compound.getConstructor().newInstance();
        Object container = loader.loadClass("net.minecraft.nbt.ListTag").getConstructor().newInstance();
        Object entry = compound.getConstructor().newInstance();
        Object inner = copy.invoke(inventory(root).get(2));
        remove.invoke(inner, "Slot");
        putInt.invoke(inner, "count", 16);
        put.invoke(entry, "item", inner);
        putInt.invoke(entry, "slot", 0);
        ((List<Object>) container).add(entry);
        put.invoke(components, "minecraft:container", container);
        put.invoke(chest, "components", components);
        putByte.invoke(chest, "Slot", (byte) 8);
        add(root, "Inventory", chest);
        var read = proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow();
        assertEquals(List.of(24), read.units());
        assertEquals(List.of(40), read.wholeFile());
    }

    @Test void aDuplicatedSlotNeverCountsTwice() throws Exception {
        Object root = fixture();
        add(root, "Inventory", bread(root, 24, (byte) 2));
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)));
    }

    @ParameterizedTest
    @ValueSource(bytes = {36, 99, 104, -1, -105})
    void anInventorySlotOutsideStorageAndEquipmentReadsNothing(byte slot) throws Exception {
        Object root = fixture();
        add(root, "Inventory", bread(root, 1, slot));
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)));
    }

    @ParameterizedTest
    @ValueSource(bytes = {27, -106, 100, -1})
    void anEnderChestSlotOutsideItsTwentySevenReadsNothing(byte slot) throws Exception {
        Object root = fixture();
        add(root, "EnderItems", bread(root, 1, slot));
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)));
    }

    @Test void theLastEnderChestSlotStillCounts() throws Exception {
        Object root = fixture();
        add(root, "EnderItems", bread(root, 5, (byte) 26));
        assertEquals(List.of(29), proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow().units());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 257})
    void aStoredCountOutsideTheBoundsReadsNothing(int count) throws Exception {
        Object root = fixture();
        putInt.invoke(inventory(root).get(2), "count", count);
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)));
    }

    @Test void bothCountSpellingsOnOneItemReadNothingButTheLegacyOneAloneReads() throws Exception {
        Object root = fixture();
        putByte.invoke(inventory(root).get(2), "Count", (byte) 24);
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)), "ambiguous count");
        remove.invoke(inventory(root).get(2), "count");
        assertEquals(List.of(24), proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow().units());
    }

    @Test void aMissingOrMistypedPlayerIdReadsNothing() throws Exception {
        Object root = fixture();
        remove.invoke(root, "UUID");
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)), "no id");
        root = fixture();
        putString.invoke(root, "UUID", BOT.toString());
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)), "the id as text");
        root = fixture();
        putIntArray.invoke(root, "UUID", new int[]{(int) (BOT.getMostSignificantBits() >>> 32), (int) BOT.getMostSignificantBits(),
                (int) (BOT.getLeastSignificantBits() >>> 32)});
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)), "three of four parts");
    }

    @Test void aMistypedFileDataVersionReadsNothingAndAMissingOneReadsAsNone() throws Exception {
        Object root = fixture();
        remove.invoke(root, "DataVersion");
        assertNull(proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow().fileDataVersion());
        putString.invoke(root, "DataVersion", "4790");
        assertEquals(Optional.empty(), proof.readSavedDelivery(encode(root), batch(BOT, 8)));
    }

    @Test void eachMarkerIsReadFromItsOwnKeyAndOnlyAsText() throws Exception {
        UUID nonce = new UUID(31, 1);
        Object root = fixture();
        Object values = compound.getConstructor().newInstance();
        putString.invoke(values, ItemSaveProof.DELIVERY_MARKER_KEY, ItemSaveProof.marker(nonce, true));
        putString.invoke(values, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(nonce, false));
        putInt.invoke(values, ItemSaveProof.MARKER_KEY, 7);
        put.invoke(root, "BukkitValues", values);
        var read = proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow();
        assertEquals(nonce + "/after", read.deliveryMarker());
        assertEquals(nonce + "/before", read.returnMarker());
        assertNull(read.handInMarker(), "a number is not a marker");
        assertEquals(List.of(24), read.units());
    }

    @Test void markersOutsideACompoundAreNotMarkers() throws Exception {
        Object root = fixture();
        putString.invoke(root, "BukkitValues", ItemSaveProof.marker(new UUID(31, 2), true));
        var read = proof.readSavedDelivery(encode(root), batch(BOT, 8)).orElseThrow();
        assertNull(read.deliveryMarker());
        assertNull(read.returnMarker());
        assertNull(read.handInMarker());
    }

    @Test void thePlayerFileIsTheDataFileUnderTheNormalizedWorld() {
        Path world = temporary.resolve("world");
        assertEquals(Optional.of(world.toAbsolutePath().normalize().resolve("players").resolve("data").resolve(BOT + ".dat")),
                proof.playerFile(world, BOT));
        assertEquals(proof.playerFile(world, BOT), proof.playerFile(temporary.resolve("other").resolve("..").resolve("world"), BOT));
        assertEquals(Optional.empty(), proof.playerFile(null, BOT));
        assertEquals(Optional.empty(), proof.playerFile(world, null));
    }

    private static byte[] saved() { return Base64.getDecoder().decode(FIXTURE); }

    private static ItemCustody batch(UUID player, int quantity) {
        Instant at = Instant.parse("2026-10-18T12:00:00Z");
        return new ItemCustody(new UUID(31, quantity), player, new UUID(32, quantity), ItemCustody.Source.MERCHANT_GOODS,
                quantity, new CustodyBytes(Base64.getDecoder().decode(PAYLOAD)), at, at.plusSeconds(60));
    }

    private static Object fixture() throws Exception {
        return read.invoke(null, new ByteArrayInputStream(saved()),
                accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
    }

    private static byte[] encode(Object value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write.invoke(null, value, output);
        return output.toByteArray();
    }

    @SuppressWarnings("unchecked") private static List<Object> inventory(Object root) throws Exception {
        return (List<Object>) get.invoke(root, "Inventory");
    }

    @SuppressWarnings("unchecked") private static void add(Object root, String key, Object item) throws Exception {
        ((List<Object>) get.invoke(root, key)).add(item);
    }

    private static Object bread(Object root, int count, byte slot) throws Exception {
        Object item = copy.invoke(inventory(root).get(2));
        putInt.invoke(item, "count", count);
        putByte.invoke(item, "Slot", slot);
        return item;
    }
}
