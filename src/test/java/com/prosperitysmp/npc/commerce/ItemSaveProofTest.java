package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses the existing installed jar at runtime, never an NMS compile dependency. */
class ItemSaveProofTest {
    private static final UUID BOT = UUID.fromString("226dba37-26b2-3478-8aad-ada66a4e36a7");
    // Fixture: an inventory-only player file captured on a test server. It holds no real player data.
    private static final String FIXTURE = "H4sIAAAAAAAC/32Su07DMBSGT2h6S1UhlVvHioGRCYHUEcrQBSGhlrFynVNi4tiV7bSBR+BBYOrCwMDT4SaUpjeyZPm+38f/sQdQgFqHGNJHpZkUAI2vGri9XrcDAO5p9H119nmRvM1m7893lx9VqHbFBIWR6sWzQMUB94FLAxXYYz40IyaQKjIy7amUPorBmNGQJFiAIpWxMFZx4NdxMudww9nB72X8/pIfKiR+Dm0u0MIGSgPUJp/qgUdlNJbCXkYX8pNHJBloQ2g40OwVM/YolxRrI6OBIBE6UGKGcEbtuV4sfFTcYv58iqHkPlShiIlRZN6UswLL4SjWlJgUXlXr2igWogmUjJ+Cv6wKuMaGQf0RuR0cWzfK6htZa/bCAth2B9/u3YPGfTy0Y13HYchMn/AYdQWamFDk3JZD5+fodvo7t6WWp9kAkO4mrdvN6j5ZHmDjBf63/GKmHKwrO/BShh+v4zqQE+TbjPLGE7BvlgY5tGX3493Oq+8ajDSk3w9/BrW1EQMAAA==";
    private static final String PROTOTYPE = "H4sIAAAAAAAC/+NiYOBgYMpMYeDPzcxLTS5KTCuxSipKTUxhZmBNzi/NK2EAKmBm4HZJLEkMSy0qzszPY2AQ2sYAAG0Bwwk4AAAA";
    private static URLClassLoader loader;
    private static Class<?> compound, accounter;
    private static Method read, write, get, copy, matches;
    private static ItemSaveProof proof;
    private static Path relativeData;
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
        Class<?> io = loader.loadClass("net.minecraft.nbt.NbtIo");
        read = io.getMethod("readCompressed", java.io.InputStream.class, accounter);
        write = io.getMethod("writeCompressed", compound, java.io.OutputStream.class);
        get = compound.getMethod("get", String.class);
        copy = compound.getMethod("copy");
        matches = ItemSaveProof.class.getDeclaredMethod("matches", byte[].class, ItemSaveProof.Snapshot.class);
        matches.setAccessible(true);
        Class<?> resource = loader.loadClass("net.minecraft.world.level.storage.LevelResource");
        relativeData = Path.of((String) resource.getMethod("id").invoke(resource.getField("PLAYER_DATA_DIR").get(null)));
        assertEquals(Path.of("players/data"), relativeData);
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    @Test void actualBotNbtMatchesExactPrototypeAcrossStorageAndEnder() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        Path world = world(saved);
        ItemCustody batch = batch(Base64.getDecoder().decode(PROTOTYPE), 8);
        var snapshot = proof.capture(world, batch, evidence(batch, 16, 24)).orElseThrow();
        assertTrue(nbtMatches(saved, snapshot));
        // Windows lacks fileKey(); it must refuse, not invent durability. POSIX runs the full force path.
        if (Files.readAttributes(file(world), java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null)
            assertFalse(proof.verify(snapshot));
        else assertTrue(proof.verify(snapshot));
        assertEquals(proof.verify(snapshot), proof.storageAvailable(world));

        Object root = fixture();
        Object bread = list(root, "Inventory").get(2);
        compound.getMethod("putInt", String.class, int.class).invoke(bread, "count", 20);
        Object ender = copy.invoke(bread);
        compound.getMethod("putInt", String.class, int.class).invoke(ender, "count", 4);
        compound.getMethod("putByte", String.class, byte.class).invoke(ender, "Slot", (byte) 0);
        list(root, "EnderItems").add(ender);
        Object offhand = copy.invoke(bread);
        compound.getMethod("putByte", String.class, byte.class).invoke(offhand, "Slot", (byte) -106);
        list(root, "Inventory").add(offhand);
        assertTrue(nbtMatches(encode(root), snapshot), "Equipment must not count as storage");

        Object crate = copy.invoke(list(fixture(), "Inventory").get(3));
        compound.getMethod("remove", String.class).invoke(crate, "Slot");
        compound.getMethod("putInt", String.class, int.class).invoke(crate, "DataVersion", 4790);
        ItemCustody named = batch(encode(crate), 1);
        assertTrue(nbtMatches(saved, proof.capture(world, named, evidence(named, 0, 1)).orElseThrow()));
        compound.getMethod("remove", String.class).invoke(crate, "components");
        ItemCustody bare = batch(encode(crate), 1);
        assertFalse(nbtMatches(saved, proof.capture(world, bare, evidence(bare, 0, 1)).orElseThrow()));
    }

    @Test void storageCapabilityNeedsARealPlayerDirectoryWithStableIdentities() throws Exception {
        assertFalse(proof.storageAvailable(null));
        assertFalse(proof.storageAvailable(temporary.resolve("missing-world")));
        Path world = world(Base64.getDecoder().decode(FIXTURE));
        boolean identities = Files.readAttributes(file(world), java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null;
        assertEquals(identities, proof.storageAvailable(world));
        Files.delete(file(world));
        assertEquals(identities, proof.storageAvailable(world), "startup does not require a particular player's file");
        Path directory = world.resolve(relativeData);
        Files.delete(directory);
        assertFalse(proof.storageAvailable(world), "the player-data directory is missing");
        Files.writeString(directory, "not a directory");
        assertFalse(proof.storageAvailable(world), "a file cannot stand in for the player-data directory");
    }

    @Test void storageCapabilityRefusesALinkedPlayerDirectory() throws Exception {
        Path real = Files.createDirectories(temporary.resolve("real-data"));
        Path world = temporary.resolve("linked-world");
        Path directory = world.resolve(relativeData);
        Files.createDirectories(directory.getParent());
        try { Files.createSymbolicLink(directory, real); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "symbolic links unavailable here");
        }
        assertFalse(proof.storageAvailable(world));
    }

    @Test void rejectsTruncatedSavedNbt() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        ItemCustody batch = batch(Base64.getDecoder().decode(PROTOTYPE), 8);
        var snapshot = proof.capture(world(saved), batch, evidence(batch, 16, 24)).orElseThrow();
        byte[] truncated = java.util.Arrays.copyOf(saved, saved.length / 2);
        assertFalse(nbtMatches(truncated, snapshot));
        Files.write(file(temporary.resolve("world")), truncated);
        assertFalse(proof.verify(snapshot));
    }

    @Test void rejectsCorruptSavedNbtAndWrongDataVersion() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        ItemCustody batch = batch(Base64.getDecoder().decode(PROTOTYPE), 8);
        var snapshot = proof.capture(world(saved), batch, evidence(batch, 16, 24)).orElseThrow();
        assertFalse(nbtMatches(new byte[] {1, 2, 3, 4}, snapshot));
        Object root = fixture();
        compound.getMethod("putInt", String.class, int.class).invoke(root, "DataVersion", 4789);
        assertFalse(nbtMatches(encode(root), snapshot));
        root = fixture();
        compound.getMethod("putIntArray", String.class, int[].class).invoke(root, "UUID", new int[4]);
        assertFalse(nbtMatches(encode(root), snapshot));
    }

    @Test void rejectsWrongQuantityAndUnboundCapture() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        Path world = world(saved);
        ItemCustody batch = batch(Base64.getDecoder().decode(PROTOTYPE), 8);
        assertFalse(nbtMatches(saved, proof.capture(world, batch, evidence(batch, 15, 23)).orElseThrow()));
        assertTrue(proof.capture(world, batch, evidence(batch, 16, 25)).isEmpty());
        ItemCustody wrongBatch = batch(batch.payload().bytes(), 7);
        assertTrue(proof.capture(world, wrongBatch, evidence(wrongBatch, 17, 24)).isEmpty());
        ItemCustody corrupt = batch(new byte[] {1, 2, 3}, 8);
        assertTrue(proof.capture(world, corrupt, evidence(corrupt, 16, 24)).isEmpty());
        var snapshot = proof.capture(world, batch, evidence(batch, 16, 24)).orElseThrow();
        assertFalse(new ItemSaveProof(loader).verify(snapshot));
        assertFalse(new ItemSaveProof(ClassLoader.getPlatformClassLoader()).available());
    }

    private static boolean nbtMatches(byte[] saved, ItemSaveProof.Snapshot snapshot) throws Exception {
        try { return (boolean) matches.invoke(proof, saved, snapshot); }
        catch (InvocationTargetException malformed) {
            Throwable cause = malformed.getCause();
            if (cause instanceof ReflectiveOperationException || cause instanceof RuntimeException) return false;
            throw malformed;
        }
    }
    private static Object fixture() throws Exception {
        return read.invoke(null, new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE)),
                accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
    }
    private static byte[] encode(Object value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write.invoke(null, value, output);
        return output.toByteArray();
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object root, String key) throws Exception {
        return (List<Object>) get.invoke(root, key);
    }
    private Path file(Path world) { return world.resolve(relativeData).resolve(BOT + ".dat"); }
    private Path world(byte[] bytes) throws Exception {
        Path world = temporary.resolve("world");
        Files.createDirectories(file(world).getParent());
        Files.write(file(world), bytes);
        return world;
    }
    private static ItemCustody batch(byte[] bytes, int quantity) {
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        return new ItemCustody(UUID.randomUUID(), BOT, UUID.randomUUID(), ItemCustody.Source.MERCHANT_GOODS,
                quantity, new CustodyBytes(bytes), at, at.plusSeconds(60));
    }
    private static ItemDeliveryEvidence evidence(ItemCustody batch, int before, int after) {
        return new ItemDeliveryEvidence(BOT, batch.payload().sha256(), "1".repeat(64), "2".repeat(64), before, after, 0, true);
    }
    // ---- item-repairs collection proof (shared hand-in primitive); needs the same installed Paper jar

    private static byte[] onePrototype() throws Exception {
        Object prototype = read.invoke(null, new ByteArrayInputStream(Base64.getDecoder().decode(PROTOTYPE)),
                accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
        compound.getMethod("putInt", String.class, int.class).invoke(prototype, "count", 1);
        return encode(prototype);
    }
    private static ItemSaveProof.Expectation expect(CustodyBytes prototype, int expected) {
        return new ItemSaveProof.Expectation(prototype, expected, 0);
    }
    /** Storage plus ender chest counts only (the first half of the removal proof). */
    private static boolean collectionMatches(byte[] saved, ItemSaveProof.CollectionSnapshot snapshot) throws Exception {
        Method method = ItemSaveProof.class.getDeclaredMethod("storageMatches", byte[].class, ItemSaveProof.CollectionSnapshot.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(proof, saved, snapshot); }
        catch (InvocationTargetException malformed) {
            Throwable cause = malformed.getCause();
            if (cause instanceof ReflectiveOperationException || cause instanceof RuntimeException) return false;
            throw malformed;
        }
    }

    @Test void collectionSnapshotMatchesSavedUnitsAndRejectsOneOffWrongPlayerOrVersion() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        Path world = world(saved);
        var one = new CustodyBytes(onePrototype());
        var exact = proof.captureCollection(world, BOT, List.of(expect(one, 24))).orElseThrow();
        assertTrue(collectionMatches(saved, exact), "the saved bread count");
        assertFalse(collectionMatches(saved, proof.captureCollection(world, BOT, List.of(expect(one, 23))).orElseThrow()));
        assertFalse(collectionMatches(saved, proof.captureCollection(world, BOT, List.of(expect(one, 25))).orElseThrow()));
        Object root = fixture();
        compound.getMethod("putIntArray", String.class, int[].class).invoke(root, "UUID", new int[4]);
        assertFalse(collectionMatches(encode(root), exact), "another player's file");
        root = fixture();
        compound.getMethod("putInt", String.class, int.class).invoke(root, "DataVersion", 4789);
        assertFalse(collectionMatches(encode(root), exact), "another data version");
        // A prototype must be one item; the delivery batch prototype (count 8) is refused.
        assertTrue(proof.captureCollection(world, BOT, List.of(expect(
                new CustodyBytes(Base64.getDecoder().decode(PROTOTYPE)), 24))).isEmpty());
        assertFalse(accepted(saved, exact), "the counts match, but with no before-take save to compare it is never the removal proof");
        assertFalse(proof.verify(exact), "counts alone, with no before-take save to compare, are never durable proof");
    }

    /** A one-item prototype of another fixture item, or of bread with a changed DataVersion (absent when version < 0). */
    private static byte[] itemPrototype(String id, int version) throws Exception {
        Object prototype = read.invoke(null, new ByteArrayInputStream(onePrototype()),
                accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
        compound.getMethod("putString", String.class, String.class).invoke(prototype, "id", id);
        if (version < 0) compound.getMethod("remove", String.class).invoke(prototype, "DataVersion");
        else compound.getMethod("putInt", String.class, int.class).invoke(prototype, "DataVersion", version);
        return encode(prototype);
    }
    /** The fixture with bread split as 20 in storage plus 4 in the ender chest (24 in scope) and 20 more in the offhand. */
    private static byte[] enderAndOffhand() throws Exception {
        Object root = fixture();
        Object bread = list(root, "Inventory").get(2);
        compound.getMethod("putInt", String.class, int.class).invoke(bread, "count", 20);
        Object ender = copy.invoke(bread);
        compound.getMethod("putInt", String.class, int.class).invoke(ender, "count", 4);
        compound.getMethod("putByte", String.class, byte.class).invoke(ender, "Slot", (byte) 0);
        list(root, "EnderItems").add(ender);
        Object offhand = copy.invoke(bread);
        compound.getMethod("putByte", String.class, byte.class).invoke(offhand, "Slot", (byte) -106);
        list(root, "Inventory").add(offhand);
        return encode(root);
    }
    /** The fixture plus a random byte-array tag, so the compressed file has about this many bytes more. */
    private static byte[] padded(int bytes) throws Exception {
        Object root = fixture();
        byte[] noise = new byte[bytes];
        new java.util.Random(7).nextBytes(noise);
        compound.getMethod("putByteArray", String.class, byte[].class).invoke(root, "Padding", noise);
        return encode(root);
    }
    private static boolean collectionMatches(ItemSaveProof owner, byte[] saved, ItemSaveProof.CollectionSnapshot snapshot) throws Exception {
        Method method = ItemSaveProof.class.getDeclaredMethod("storageMatches", byte[].class, ItemSaveProof.CollectionSnapshot.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(owner, saved, snapshot); }
        catch (InvocationTargetException malformed) {
            Throwable cause = malformed.getCause();
            if (cause instanceof ReflectiveOperationException || cause instanceof RuntimeException) return false;
            throw malformed;
        }
    }

    /** The counts alone. */
    private static java.util.Optional<List<Integer>> readUnits(byte[] saved, UUID player, List<CustodyBytes> prototypes) {
        return proof.readSaved(saved, player, prototypes).map(ItemSaveProof.SavedUnits::units);
    }

    @Test void readSavedReportsTheFileAndRecordedDataVersions() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        int fileVersion = (int) ((java.util.Optional<?>) compound.getMethod("getInt", String.class).invoke(fixture(), "DataVersion")).orElseThrow();
        var same = new CustodyBytes(itemPrototype("minecraft:bread", fileVersion));
        var read = proof.readSaved(saved, BOT, List.of(same)).orElseThrow();
        assertEquals(List.of(24), read.units(), "storage and ender chest units");
        assertEquals(fileVersion, read.fileDataVersion());
        assertEquals(List.of(fileVersion), read.recordedDataVersions());
        assertNull(ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()), "same version");
        // An item recorded before a server upgrade: the counts still read, but the versions disagree and say so.
        var older = proof.readSaved(saved, BOT, List.of(new CustodyBytes(itemPrototype("minecraft:bread", fileVersion - 1)))).orElseThrow();
        assertEquals(List.of(fileVersion - 1), older.recordedDataVersions());
        assertNotNull(ItemSaveProof.dataVersionProblem(older.fileDataVersion(), older.recordedDataVersions()));
        var mixed = proof.readSaved(saved, BOT, List.of(same, new CustodyBytes(itemPrototype("minecraft:torch", fileVersion + 1)))).orElseThrow();
        assertEquals(List.of(fileVersion, fileVersion + 1), mixed.recordedDataVersions());
        var twice = proof.readSaved(saved, BOT, List.of(same, new CustodyBytes(itemPrototype("minecraft:torch", fileVersion)))).orElseThrow();
        assertEquals(List.of(fileVersion), twice.recordedDataVersions(), "one version, listed once");
        // None recorded: listed as none, and the file's own version still reads.
        var none = proof.readSaved(saved, BOT, List.of(new CustodyBytes(itemPrototype("minecraft:bread", -1)))).orElseThrow();
        assertEquals(List.of(), none.recordedDataVersions());
        assertEquals(fileVersion, none.fileDataVersion());
        // A file with no DataVersion reads as none; one of another type is refused.
        Object root = fixture();
        compound.getMethod("remove", String.class).invoke(root, "DataVersion");
        assertNull(proof.readSaved(encode(root), BOT, List.of(same)).orElseThrow().fileDataVersion());
        compound.getMethod("putString", String.class, String.class).invoke(root, "DataVersion", "4790");
        assertTrue(proof.readSaved(encode(root), BOT, List.of(same)).isEmpty(), "a DataVersion that is not a number");
    }

    @Test void readUnitsCountsStorageAndEnderOnlyForTheExactItem() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        var bread = new CustodyBytes(onePrototype());
        var torch = new CustodyBytes(itemPrototype("minecraft:torch", 4790));
        assertEquals(java.util.Optional.of(List.of(24, 32)), readUnits(saved, BOT, List.of(bread, torch)), "both lines read in order");
        assertEquals(java.util.Optional.of(List.of(24)), readUnits(enderAndOffhand(), BOT, List.of(bread)),
                "20 in storage plus 4 in the ender chest; the offhand is equipment and does not count");
        Object crate = copy.invoke(list(fixture(), "Inventory").get(3));
        compound.getMethod("remove", String.class).invoke(crate, "Slot");
        assertEquals(java.util.Optional.of(List.of(1)), readUnits(saved, BOT, List.of(new CustodyBytes(encode(crate)))));
        compound.getMethod("remove", String.class).invoke(crate, "components");
        assertEquals(java.util.Optional.of(List.of(0)), readUnits(saved, BOT, List.of(new CustodyBytes(encode(crate)))),
                "a plain chest is not the named crate");
        assertEquals(java.util.Optional.of(List.of(24)), readUnits(saved, BOT, List.of(new CustodyBytes(itemPrototype("minecraft:bread", -1)))),
                "the offline read does not need a DataVersion on the prototype");
    }

    @Test void readUnitsRefusesAnotherPlayerBadPrototypesAndBadBounds() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        var bread = new CustodyBytes(onePrototype());
        assertTrue(readUnits(saved, BOT, List.of(bread)).isPresent(), "the file reads");
        assertTrue(readUnits(saved, UUID.randomUUID(), List.of(bread)).isEmpty(), "another player's file");
        assertTrue(readUnits(saved, BOT, List.of(new CustodyBytes(Base64.getDecoder().decode(PROTOTYPE)))).isEmpty(), "count 8 is not one item");
        Object noId = read.invoke(null, new ByteArrayInputStream(onePrototype()), accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
        compound.getMethod("remove", String.class).invoke(noId, "id");
        assertTrue(readUnits(saved, BOT, List.of(new CustodyBytes(encode(noId)))).isEmpty(), "a prototype needs an item id");
        assertTrue(readUnits(saved, BOT, List.of()).isEmpty());
        assertTrue(readUnits(saved, BOT, java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES + 1, bread)).isEmpty());
        assertTrue(readUnits(saved, BOT, java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES, bread)).isPresent());
        assertTrue(readUnits(new byte[0], BOT, List.of(bread)).isEmpty());
        assertTrue(readUnits(java.util.Arrays.copyOf(saved, saved.length / 2), BOT, List.of(bread)).isEmpty(), "truncated");
        byte[] small = padded(64 * 1024), large = padded(ItemSaveProof.MAX_FILE_BYTES + 64 * 1024);
        assertTrue(large.length > ItemSaveProof.MAX_FILE_BYTES, "the padding really is over the bound");
        assertEquals(java.util.Optional.of(List.of(24)), readUnits(small, BOT, List.of(bread)), "padding alone is readable");
        assertTrue(readUnits(large, BOT, List.of(bread)).isEmpty(), "a file over the size bound is not read");
    }

    @Test void collectionProofCountsEnderNotEquipmentAndBindsItsOwnerAndSize() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        Path world = world(saved);
        var bread = new CustodyBytes(onePrototype());
        var exact = proof.captureCollection(world, BOT, List.of(expect(bread, 24))).orElseThrow();
        assertTrue(collectionMatches(enderAndOffhand(), exact), "20 in storage plus 4 in the ender chest; the offhand does not count");
        var other = new ItemSaveProof(loader);
        var foreign = other.captureCollection(world, BOT, List.of(expect(bread, 24))).orElseThrow();
        assertTrue(collectionMatches(other, saved, foreign), "its own proof accepts it");
        assertFalse(collectionMatches(proof, saved, foreign), "a snapshot from another proof instance is refused");
        assertTrue(collectionMatches(padded(64 * 1024), exact), "padding alone still matches");
        assertFalse(collectionMatches(padded(ItemSaveProof.MAX_FILE_BYTES + 64 * 1024), exact), "a file over the size bound is refused");
        // Two lines, read in order.
        var torch = new CustodyBytes(itemPrototype("minecraft:torch", 4790));
        var two = proof.captureCollection(world, BOT, List.of(expect(bread, 24),
                expect(torch, 32))).orElseThrow();
        assertTrue(collectionMatches(saved, two));
        assertFalse(collectionMatches(saved, proof.captureCollection(world, BOT, List.of(expect(bread, 32),
                expect(torch, 24))).orElseThrow()), "counts are per line, not a total");
        assertFalse(proof.verify((ItemSaveProof.CollectionSnapshot) null));
        var wrong = proof.captureCollection(world, BOT, List.of(expect(bread, 23))).orElseThrow();
        assertFalse(proof.verify(wrong), "never durable proof of a count the file does not hold");
    }

    @Test void captureCollectionRefusesMixedVersionsMissingIdsAndTooManyLines() throws Exception {
        Path world = world(Base64.getDecoder().decode(FIXTURE));
        var bread = expect(new CustodyBytes(onePrototype()), 24);
        var torch = expect(new CustodyBytes(itemPrototype("minecraft:torch", 4790)), 32);
        assertTrue(proof.captureCollection(world, BOT, List.of(bread, torch)).isPresent(), "a valid capture is accepted");
        var older = expect(new CustodyBytes(itemPrototype("minecraft:torch", 4789)), 32);
        assertTrue(proof.captureCollection(world, BOT, List.of(bread, older)).isEmpty(), "every prototype from one data version");
        var unversioned = expect(new CustodyBytes(itemPrototype("minecraft:bread", -1)), 24);
        assertTrue(proof.captureCollection(world, BOT, List.of(unversioned)).isEmpty(), "a prototype must carry its data version");
        var zero = expect(new CustodyBytes(itemPrototype("minecraft:bread", 0)), 24);
        assertTrue(proof.captureCollection(world, BOT, List.of(zero)).isEmpty(), "a data version of 0 is not a real one");
        Object noId = read.invoke(null, new ByteArrayInputStream(onePrototype()), accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
        compound.getMethod("remove", String.class).invoke(noId, "id");
        assertTrue(proof.captureCollection(world, BOT, List.of(expect(new CustodyBytes(encode(noId)), 24))).isEmpty());
        assertTrue(proof.captureCollection(world, BOT, java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES, bread)).isPresent());
        assertTrue(proof.captureCollection(world, BOT, java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES + 1, bread)).isEmpty());
        assertTrue(proof.captureCollection(world, BOT, List.of()).isEmpty());
        assertTrue(proof.captureCollection(null, BOT, List.of(bread)).isEmpty());
        assertTrue(proof.captureCollection(world, null, List.of(bread)).isEmpty());
    }

    // ---- A stale file whose recipe sits where the storage count never looks

    private static Object tag() throws Exception { return compound.getConstructor().newInstance(); }
    @SuppressWarnings("unchecked") private static List<Object> listTag() throws Exception {
        return (List<Object>) loader.loadClass("net.minecraft.nbt.ListTag").getConstructor().newInstance();
    }
    private static void put(Object parent, String key, Object child) throws Exception {
        compound.getMethod("put", String.class, loader.loadClass("net.minecraft.nbt.Tag")).invoke(parent, key, child);
    }
    private static Object item(String id, int count) throws Exception {
        Object item = tag();
        compound.getMethod("putString", String.class, String.class).invoke(item, "id", id);
        compound.getMethod("putInt", String.class, int.class).invoke(item, "count", count);
        return item;
    }
    /** The fixture with its loose bread removed: the last good save, before the player emptied anything. */
    private static Object withoutLooseBread() throws Exception {
        Object root = fixture();
        list(root, "Inventory").remove(2);
        return root;
    }
    /** The last good save: no loose bread, and this many bread inside a shulker box in storage slot 9. */
    private static byte[] breadInShulker(int bread) throws Exception {
        Object root = withoutLooseBread();
        Object box = item("minecraft:shulker_box", 1);
        compound.getMethod("putByte", String.class, byte.class).invoke(box, "Slot", (byte) 9);
        Object entry = tag();
        compound.getMethod("putInt", String.class, int.class).invoke(entry, "slot", 0);
        put(entry, "item", item("minecraft:bread", bread));
        List<Object> contents = listTag();
        contents.add(entry);
        Object components = tag();
        put(components, "minecraft:container", contents);
        put(box, "components", components);
        list(root, "Inventory").add(box);
        return encode(root);
    }
    /** The last good save: no loose bread, and this many bread in the offhand (the 26.1 equipment compound). */
    private static byte[] breadInOffhand(int bread) throws Exception {
        Object root = withoutLooseBread();
        Object equipment = tag();
        put(equipment, "offhand", item("minecraft:bread", bread));
        put(root, "equipment", equipment);
        return encode(root);
    }

    /** The whole removal proof: what verify() accepts once the file read is stable. */
    private static boolean accepted(byte[] saved, ItemSaveProof.CollectionSnapshot snapshot) throws Exception {
        Method method = ItemSaveProof.class.getDeclaredMethod("matchesCollection", byte[].class, ItemSaveProof.CollectionSnapshot.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(proof, saved, snapshot); }
        catch (InvocationTargetException malformed) {
            Throwable cause = malformed.getCause();
            if (cause instanceof ReflectiveOperationException || cause instanceof RuntimeException) return false;
            throw malformed;
        }
    }
    private static final UUID HAND_IN = UUID.fromString("00000000-0000-4000-8000-00000000a11c");
    private static Object parse(byte[] saved) throws Exception {
        return read.invoke(null, new ByteArrayInputStream(saved), accounter.getConstructor(long.class, int.class).newInstance(8 * 1024 * 1024L, 64));
    }
    /** The file with the hand-in marker the plugin writes before a save (null leaves the file unmarked). */
    private static byte[] marked(byte[] saved, String marker) throws Exception {
        Object root = parse(saved);
        if (marker != null) {
            Object values = tag();
            compound.getMethod("putString", String.class, String.class).invoke(values, ItemSaveProof.MARKER_KEY, marker);
            put(root, "BukkitValues", values);
        }
        return encode(root);
    }
    private static byte[] before(byte[] saved) throws Exception { return marked(saved, ItemSaveProof.marker(HAND_IN, false)); }
    private static byte[] after(byte[] saved) throws Exception { return marked(saved, ItemSaveProof.marker(HAND_IN, true)); }
    private static void slot(Object item, int slot) throws Exception {
        compound.getMethod("putByte", String.class, byte.class).invoke(item, "Slot", (byte) slot);
    }
    /** The fixture with this much loose bread in storage slot 2 (0 leaves none). */
    private static byte[] looseBread(int bread) throws Exception {
        Object root = withoutLooseBread();
        if (bread > 0) {
            Object stack = item("minecraft:bread", bread);
            slot(stack, 2);
            list(root, "Inventory").add(stack);
        }
        return encode(root);
    }
    /** The take's snapshot: storage plus ender expected to hold left, taken units removed, against this before-take save. */
    private ItemSaveProof.CollectionSnapshot took(byte[] beforeTake, int left, int taken) throws Exception {
        Path world = world(beforeTake);
        var bread = new CustodyBytes(onePrototype());
        var baseline = proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(bread)).orElseThrow();
        return proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(bread, left, taken)), baseline).orElseThrow();
    }

    @Test void aStaleSaveWithTheRecipeInAShulkerOrTheOffhandIsNotProofOfTheTake() throws Exception {
        // The player saved with 16 bread in a shulker box (or the offhand), emptied it into storage and handed in 16.
        // The before-take save worked (16 loose bread, before marker). The take's save failed silently, and the file on
        // disk is the old one again, or any file with the bread still somewhere the storage count does not look.
        byte[] beforeTake = before(looseBread(16));
        var snapshot = took(beforeTake, 0, 16);
        assertTrue(accepted(after(looseBread(0)), snapshot), "the take's own save with the bread gone");
        for (byte[] stale : List.of(breadInShulker(16), breadInOffhand(16))) {
            assertTrue(collectionMatches(stale, snapshot), "no loose bread: the storage count alone would pass this file");
            assertFalse(accepted(stale, snapshot), "the old file, unmarked, still holds the 16 bread");
            assertFalse(accepted(after(stale), snapshot), "even marked as after the take, the 16 bread are still in the file");
        }
        // The old file can never stand in for the before-take save either: it lacks this hand-in's before marker.
        var bread = List.of(new CustodyBytes(onePrototype()));
        Path world = world(breadInShulker(16));
        assertTrue(proof.baselineOf(file(world), before(breadInShulker(16)), BOT, HAND_IN, bread).isPresent(), "the before-take save gives a baseline");
        assertTrue(proof.baselineOf(file(world), breadInShulker(16), BOT, HAND_IN, bread).isEmpty(), "unmarked");
        assertTrue(proof.baselineOf(file(world), after(breadInShulker(16)), BOT, HAND_IN, bread).isEmpty(), "the after marker");
    }

    @Test void theRemovalProofCountsEveryPlaceInTheFile() throws Exception {
        // Before the take: 16 loose bread plus 16 in a shulker box. The take removes the 16 loose ones.
        Object root = parse(breadInShulker(16));
        Object loose = item("minecraft:bread", 16);
        slot(loose, 2);
        list(root, "Inventory").add(loose);
        byte[] beforeTake = before(encode(root));
        var bread = new CustodyBytes(onePrototype());
        assertEquals(List.of(32), proof.baselineOf(file(world(beforeTake)), beforeTake, BOT, HAND_IN, List.of(bread)).orElseThrow().units(),
                "the before-take count includes the shulker's contents");
        var snapshot = took(beforeTake, 0, 16);
        assertTrue(accepted(after(breadInShulker(16)), snapshot), "the box keeps its own 16, the loose 16 are gone");
        assertFalse(accepted(after(breadInShulker(32)), snapshot), "the loose 16 moved into the box");
        assertFalse(accepted(beforeTake, snapshot), "the before-take save itself: the take's save never happened");
        assertFalse(accepted(breadInShulker(16), snapshot), "no marker: not the take's save");
        assertFalse(accepted(marked(breadInShulker(16), ItemSaveProof.marker(UUID.randomUUID(), true)), snapshot), "another hand-in's marker");
        assertFalse(accepted(marked(breadInShulker(16), ItemSaveProof.marker(HAND_IN, false)), snapshot), "still the before marker");
        assertFalse(accepted(after(looseBread(0)), snapshot), "the box's 16 vanished too: more than the recipe left the file");
        // Every other place an item can be in the file counts the same way: 16 there means the box's 16 stayed.
        Object ender = parse(breadInShulker(16));
        List<Object> inventory = list(ender, "Inventory");
        Object box = inventory.remove(inventory.size() - 1);
        slot(box, 0);
        list(ender, "EnderItems").add(box);
        assertTrue(accepted(after(encode(ender)), snapshot), "a shulker box in the ender chest counts");
        assertTrue(accepted(after(breadInOffhand(16)), snapshot), "the offhand counts");
        Object armor = withoutLooseBread();
        Object equipment = tag();
        put(equipment, "head", item("minecraft:bread", 16));
        put(armor, "equipment", equipment);
        assertTrue(accepted(after(encode(armor)), snapshot), "any equipment slot counts");
        Object bundled = withoutLooseBread();
        Object sack = item("minecraft:bundle", 1);
        slot(sack, 9);
        List<Object> contents = listTag();
        contents.add(item("minecraft:bread", 16));
        Object components = tag();
        put(components, "minecraft:bundle_contents", contents);
        put(sack, "components", components);
        list(bundled, "Inventory").add(sack);
        assertTrue(accepted(after(encode(bundled)), snapshot), "a bundle's contents count");
        Object riding = withoutLooseBread();
        Object vehicle = tag(), donkey = tag();
        compound.getMethod("putString", String.class, String.class).invoke(donkey, "id", "minecraft:donkey");
        List<Object> chest = listTag();
        Object stored = item("minecraft:bread", 16);
        slot(stored, 0);
        chest.add(stored);
        put(donkey, "Items", chest);
        put(vehicle, "Entity", donkey);
        put(riding, "RootVehicle", vehicle);
        assertTrue(accepted(after(encode(riding)), snapshot), "a ridden donkey's chest is in the player file and counts");
    }

    @Test void aSnapshotMustBindTheBeforeTakeSaveOfThisPlayerAndItems() throws Exception {
        byte[] beforeTake = before(looseBread(16));
        Path world = world(beforeTake);
        var bread = new CustodyBytes(onePrototype());
        var baseline = proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(bread)).orElseThrow();
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(bread, 0, 16)), baseline).isPresent(), "a capture with its baseline is accepted");
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(bread, 0, 17)), baseline).isEmpty(), "more than the save held");
        assertTrue(proof.captureCollection(world, UUID.randomUUID(), List.of(new ItemSaveProof.Expectation(bread, 0, 16)), baseline).isEmpty());
        var torch = new CustodyBytes(itemPrototype("minecraft:torch", 4790));
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(torch, 0, 16)), baseline).isEmpty(), "other items");
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(bread, 0, 16)), null).isEmpty(), "no before-take save");
        var foreign = new ItemSaveProof(loader).baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(bread)).orElseThrow();
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(bread, 0, 16)), foreign).isEmpty(), "another proof's read");
        assertTrue(proof.baselineOf(file(world), beforeTake, UUID.randomUUID(), HAND_IN, List.of(bread)).isEmpty(), "another player's file");
        assertTrue(proof.baselineOf(file(world), beforeTake, BOT, UUID.randomUUID(), List.of(bread)).isEmpty(), "another hand-in's save");
        var olderBread = new CustodyBytes(itemPrototype("minecraft:bread", 4789));
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(olderBread, 0, 16)), baseline).isEmpty(),
                "the same item recorded at another DataVersion");
        assertTrue(proof.captureCollection(temporary.resolve("other-world"), BOT, List.of(new ItemSaveProof.Expectation(bread, 0, 16)), baseline)
                .isEmpty(), "another world's player file");
        assertTrue(proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(olderBread)).isEmpty(), "a file at another DataVersion");
        assertTrue(proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(new CustodyBytes(Base64.getDecoder().decode(PROTOTYPE))))
                .isEmpty(), "a prototype of 8 is not one item");
        assertTrue(proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, List.of(bread, olderBread)).isEmpty(), "mixed DataVersions");
        assertTrue(proof.baselineOf(file(world), beforeTake, BOT, HAND_IN, java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES + 1, bread))
                .isEmpty(), "too many lines");
        assertEquals(List.of(16, 16, 16, 16), proof.baselineOf(file(world), beforeTake, BOT, HAND_IN,
                java.util.Collections.nCopies(ItemCollectionEvidence.MAX_LINES, bread)).orElseThrow().units(), "the most lines");
        // With file identities the read goes through the stable, forced path; without them (Windows) it refuses.
        if (Files.readAttributes(file(world), java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null)
            assertTrue(proof.baseline(world, BOT, HAND_IN, List.of(bread)).isEmpty());
        else assertEquals(List.of(16), proof.baseline(world, BOT, HAND_IN, List.of(bread)).orElseThrow().units());
    }

    // ---- REPAIR_RETURN deliveries: the hand-in's method, applied to giving items back

    private static ItemCustody returnBatch(int quantity) throws Exception {
        Object stack = parse(onePrototype());
        compound.getMethod("putInt", String.class, int.class).invoke(stack, "count", quantity);
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        return new ItemCustody(RETURN, BOT, UUID.randomUUID(), ItemCustody.Source.REPAIR_RETURN,
                quantity, new CustodyBytes(encode(stack)), at, at.plusSeconds(60));
    }
    private static final UUID RETURN = UUID.fromString("00000000-0000-4000-8000-0000000be7a1");

    /**
     * A return of 8 bread. The player had 16 loose bread and 8 in a shulker box; the delivery added 8 loose. Its save
     * failed silently and the file on disk is an older one with 24 loose bread. Storage plus ender chest shows the 24 the
     * delivery recorded, but the whole file holds 24, not the 32 the player now owns: that file is never proof.
     */
    @Test void aReturnIsNeverProvenByTheStorageCountAlone() throws Exception {
        ItemCustody batch = returnBatch(8);
        Path world = world(looseBread(24));
        assertTrue(proof.capture(world, batch, evidence(batch, 16, 24)).isEmpty(),
                "a return must use the proof bound to its own before-delivery save");
        ItemCustody commerce = batch(batch.payload().bytes(), 8);
        var unmarked = proof.capture(world, commerce, evidence(commerce, 16, 24)).orElseThrow();
        assertTrue(nbtMatches(looseBread(24), unmarked), "unmarked commerce deliveries are checked by the count alone");
        // No public path builds a return snapshot without a baseline, so the proof's own refusal is reached by building
        // one directly: the same snapshot with the return as its batch. Defence in depth behind capture's refusal.
        assertFalse(nbtMatches(looseBread(24), rebuilt(unmarked, batch)), "an unmarked return is refused by the proof too");
        assertTrue(nbtMatches(looseBread(24), rebuilt(unmarked, commerce)), "the rebuild itself matches");
    }

    /** The same unmarked snapshot (no baseline) bound to another batch, through the private constructor. */
    private static ItemSaveProof.Snapshot rebuilt(ItemSaveProof.Snapshot snapshot, ItemCustody batch) throws Exception {
        var type = ItemSaveProof.Snapshot.class;
        var constructor = type.getDeclaredConstructor(ItemSaveProof.class, Path.class, ItemCustody.class,
                ItemDeliveryEvidence.class, int.class, Object.class, ItemSaveProof.Baseline.class);
        constructor.setAccessible(true);
        java.util.function.Function<String, Object> field = name -> {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(snapshot); }
            catch (ReflectiveOperationException missing) { throw new AssertionError(name, missing); }
        };
        assertNull(field.apply("baseline"));
        return constructor.newInstance(field.apply("owner"), field.apply("file"), batch, evidence(batch, 16, 24),
                field.apply("version"), field.apply("prototype"), null);
    }

    /** The file with a marker under this key (the hand-in and return keys are separate). */
    private static byte[] markedAs(byte[] saved, String key, String marker) throws Exception {
        Object root = parse(saved);
        Object values = tag();
        compound.getMethod("putString", String.class, String.class).invoke(values, key, marker);
        put(root, "BukkitValues", values);
        return encode(root);
    }
    private static byte[] returnBefore(byte[] saved) throws Exception {
        return markedAs(saved, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(RETURN, false));
    }
    private static byte[] returnAfter(byte[] saved) throws Exception {
        return markedAs(saved, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(RETURN, true));
    }
    /** This much loose bread in storage slot 2 plus this much in a shulker box in storage slot 9. */
    private static byte[] looseAndBoxed(int loose, int boxed) throws Exception {
        Object root = parse(breadInShulker(boxed));
        if (loose > 0) {
            Object stack = item("minecraft:bread", loose);
            slot(stack, 2);
            list(root, "Inventory").add(stack);
        }
        return encode(root);
    }
    /** The return's whole proof (what verify accepts once the file read is stable), against a before-delivery save. */
    private ItemSaveProof.Snapshot delivered(byte[] beforeDelivery, ItemCustody batch, int before, int after) throws Exception {
        Path world = world(beforeDelivery);
        var baseline = proof.deliveryBaselineOf(file(world), beforeDelivery, batch).orElseThrow();
        return proof.captureDelivery(world, batch, evidence(batch, before, after), baseline).orElseThrow();
    }

    /**
     * Before the delivery: 16 loose bread and 8 in a shulker box (24 in the whole file, 16 in storage), saved with the
     * return's before marker. The delivery of 8 is proven only by its own save: the after marker, 24 in storage and 32
     * in the whole file. A failed save leaves an older file, and none of those is proof.
     */
    @Test void aReturnIsProvenOnlyByItsOwnSaveWithTheWholeFileUpByTheBatch() throws Exception {
        ItemCustody batch = returnBatch(8);
        var snapshot = delivered(returnBefore(looseAndBoxed(16, 8)), batch, 16, 24);
        assertTrue(nbtMatches(returnAfter(looseAndBoxed(24, 8)), snapshot), "the delivery's own save");
        assertFalse(nbtMatches(looseBread(24), snapshot), "the old file with 24 loose bread: storage matches, no marker");
        assertFalse(nbtMatches(returnAfter(looseBread(24)), snapshot), "even marked, the box's 8 are gone: 24 in the file, not 32");
        assertFalse(nbtMatches(returnBefore(looseAndBoxed(24, 8)), snapshot), "the before marker: the delivery's save never happened");
        assertFalse(nbtMatches(returnBefore(looseAndBoxed(16, 8)), snapshot), "the before-delivery save itself");
        assertFalse(nbtMatches(markedAs(looseAndBoxed(24, 8), ItemSaveProof.RETURN_MARKER_KEY,
                ItemSaveProof.marker(UUID.randomUUID(), true)), snapshot), "another return's marker");
        assertFalse(nbtMatches(markedAs(looseAndBoxed(24, 8), ItemSaveProof.MARKER_KEY, ItemSaveProof.marker(RETURN, true)), snapshot),
                "the same id under the hand-in key is not this return's marker");
        assertFalse(nbtMatches(returnAfter(looseAndBoxed(24, 16)), snapshot), "8 more in the file than the batch added");
        assertFalse(nbtMatches(returnAfter(looseAndBoxed(23, 8)), snapshot), "one short in storage");
        // The rest of the file counts wherever the bread sits: here the box's 8 moved to the offhand.
        Object offhand = parse(looseBread(24));
        Object equipment = tag();
        put(equipment, "offhand", item("minecraft:bread", 8));
        put(offhand, "equipment", equipment);
        assertTrue(nbtMatches(returnAfter(encode(offhand)), snapshot), "the offhand counts in the whole file");
    }

    @Test void aReturnBaselineIsThisReturnsBeforeSaveForThisPlayerAndItem() throws Exception {
        ItemCustody batch = returnBatch(8);
        byte[] beforeDelivery = returnBefore(looseAndBoxed(16, 8));
        Path world = world(beforeDelivery);
        var baseline = proof.deliveryBaselineOf(file(world), beforeDelivery, batch);
        assertEquals(List.of(24), baseline.orElseThrow().units(), "the whole file, the box included");
        assertTrue(proof.deliveryBaselineOf(file(world), looseAndBoxed(16, 8), batch).isEmpty(), "unmarked: that save never happened");
        assertTrue(proof.deliveryBaselineOf(file(world), returnAfter(looseAndBoxed(16, 8)), batch).isEmpty(), "the after marker");
        assertTrue(proof.deliveryBaselineOf(file(world), markedAs(looseAndBoxed(16, 8), ItemSaveProof.MARKER_KEY,
                ItemSaveProof.marker(RETURN, false)), batch).isEmpty(), "the hand-in key");
        assertTrue(proof.deliveryBaselineOf(file(world), markedAs(looseAndBoxed(16, 8), ItemSaveProof.RETURN_MARKER_KEY,
                ItemSaveProof.marker(UUID.randomUUID(), false)), batch).isEmpty(), "another return's save");
        Object other = parse(beforeDelivery);
        compound.getMethod("putIntArray", String.class, int[].class).invoke(other, "UUID", new int[4]);
        assertTrue(proof.deliveryBaselineOf(file(world), encode(other), batch).isEmpty(), "another player's file");
        Object older = parse(beforeDelivery);
        compound.getMethod("putInt", String.class, int.class).invoke(older, "DataVersion", 4789);
        assertTrue(proof.deliveryBaselineOf(file(world), encode(older), batch).isEmpty(), "a file at another DataVersion");
        ItemCustody commerce = batch(batch.payload().bytes(), 8);
        assertTrue(proof.deliveryBaselineOf(file(world), beforeDelivery, commerce).isEmpty(), 
                "a return's before-save is not a commerce delivery's: it has another key and nonce");
        // captureDelivery binds the baseline to this proof, return, player and item, and refuses anything else.
        var evidence = evidence(batch, 16, 24);
        assertTrue(proof.captureDelivery(world, batch, evidence, baseline.get()).isPresent(), "a capture with its baseline is accepted");
        assertTrue(proof.captureDelivery(world, batch, evidence, null).isEmpty(), "no before-delivery save");
        assertTrue(proof.captureDelivery(world, commerce, evidence(commerce, 16, 24), baseline.get()).isEmpty(), "a commerce batch");
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        ItemCustody otherReturn = new ItemCustody(UUID.randomUUID(), BOT, UUID.randomUUID(), ItemCustody.Source.REPAIR_RETURN,
                8, batch.payload(), at, at.plusSeconds(60));
        assertTrue(proof.captureDelivery(world, otherReturn, evidence(otherReturn, 16, 24), baseline.get()).isEmpty(), "another return");
        var foreign = new ItemSaveProof(loader).deliveryBaselineOf(file(world), beforeDelivery, batch).orElseThrow();
        assertTrue(proof.captureDelivery(world, batch, evidence, foreign).isEmpty(), "another proof's read");
        assertTrue(proof.captureDelivery(temporary.resolve("other-world"), batch, evidence, baseline.get()).isEmpty(), "another world's file");
        byte[] beforeTake = marked(looseBread(16), ItemSaveProof.marker(RETURN, false));
        var handIn = proof.baselineOf(file(world), beforeTake, BOT, RETURN, List.of(new CustodyBytes(onePrototype()))).orElseThrow();
        assertTrue(proof.captureDelivery(world, batch, evidence, handIn).isEmpty(), "a hand-in's before-take save is not a return's");
        assertTrue(proof.captureCollection(world, BOT, List.of(new ItemSaveProof.Expectation(new CustodyBytes(onePrototype()), 0, 8)),
                baseline.get()).isEmpty(), "and a return's save is not a hand-in's");
        // With file identities the read goes through the stable, forced path; without them (Windows) it refuses.
        if (Files.readAttributes(file(world), java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null)
            assertTrue(proof.deliveryBaseline(world, batch).isEmpty());
        else assertEquals(List.of(24), proof.deliveryBaseline(world, batch).orElseThrow().units());
    }

    /**
     * The offline report is staff's evidence: per line the storage plus ender chest count and the whole-file count, and
     * the hand-in and return markers as found. 16 loose bread and 16 in a shulker box read as 16 and 32.
     */
    @Test void readSavedReportsTheWholeFileAndTheMarkers() throws Exception {
        Object root = parse(breadInShulker(16));
        Object loose = item("minecraft:bread", 16);
        slot(loose, 2);
        list(root, "Inventory").add(loose);
        byte[] boxed = encode(root);
        var bread = new CustodyBytes(onePrototype());
        var torch = new CustodyBytes(itemPrototype("minecraft:torch", 4790));
        var plain = proof.readSaved(boxed, BOT, List.of(bread, torch)).orElseThrow();
        assertEquals(List.of(16, 32), plain.units(), "storage and ender chest only");
        assertEquals(List.of(32, 32), plain.wholeFile(), "the box's 16 count in the whole file");
        assertNull(plain.handInMarker());
        assertNull(plain.returnMarker());
        var afterTake = proof.readSaved(after(boxed), BOT, List.of(bread)).orElseThrow();
        assertEquals(ItemSaveProof.marker(HAND_IN, true), afterTake.handInMarker());
        assertNull(afterTake.returnMarker());
        Object both = parse(after(boxed));
        compound.getMethod("putString", String.class, String.class).invoke(get.invoke(both, "BukkitValues"),
                ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(RETURN, false));
        var marked = proof.readSaved(encode(both), BOT, List.of(bread)).orElseThrow();
        assertEquals(ItemSaveProof.marker(HAND_IN, true), marked.handInMarker(), "each key is read on its own");
        assertEquals(ItemSaveProof.marker(RETURN, false), marked.returnMarker());
        assertEquals(List.of(32), proof.readSaved(after(breadInOffhand(32)), BOT, List.of(bread)).orElseThrow().wholeFile(), "the offhand counts");
    }

    // ---- marked commerce deliveries (town.commerce.delivery-save-marker): the return's method, applied to paid orders

    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-0000000de11e");
    private static ItemCustody orderBatch(ItemCustody.Source source, UUID nonce, int quantity) throws Exception {
        Object stack = parse(onePrototype());
        compound.getMethod("putInt", String.class, int.class).invoke(stack, "count", quantity);
        Instant at = Instant.parse("2026-09-29T12:00:00Z");
        return new ItemCustody(nonce, BOT, UUID.randomUUID(), source, quantity, new CustodyBytes(encode(stack)), at, at.plusSeconds(60));
    }
    private static byte[] orderBefore(byte[] saved) throws Exception {
        return markedAs(saved, ItemSaveProof.DELIVERY_MARKER_KEY, ItemSaveProof.marker(ORDER, false));
    }
    private static byte[] orderAfter(byte[] saved) throws Exception {
        return markedAs(saved, ItemSaveProof.DELIVERY_MARKER_KEY, ItemSaveProof.marker(ORDER, true));
    }
    /** This much loose bread in storage slot 2 and this much in the offhand. */
    private static byte[] looseAndOffhand(int loose, int offhand) throws Exception {
        Object root = parse(looseBread(loose));
        Object equipment = tag();
        put(equipment, "offhand", item("minecraft:bread", offhand));
        put(root, "equipment", equipment);
        return encode(root);
    }

    /**
     * An order of 8 bread. Before it: 16 loose and 8 in a shulker box, saved with this order's before marker. The order is
     * proven only by its own save: this order's after marker under the delivery key, 24 in storage and 32 in the whole
     * file. Same rule as a return, for both commerce sources.
     */
    @Test void aCommerceDeliveryIsProvenOnlyByItsOwnSaveWithTheWholeFileUpByTheBatch() throws Exception {
        for (var source : new ItemCustody.Source[] {ItemCustody.Source.MERCHANT_GOODS, ItemCustody.Source.WORKER_OUTPUT}) {
            ItemCustody batch = orderBatch(source, ORDER, 8);
            var snapshot = delivered(orderBefore(looseAndBoxed(16, 8)), batch, 16, 24);
            assertTrue(nbtMatches(orderAfter(looseAndBoxed(24, 8)), snapshot), "the delivery's own save, " + source);
            assertFalse(nbtMatches(looseAndBoxed(24, 8), snapshot), "an unmarked old file with the same counts");
            assertFalse(nbtMatches(looseBread(24), snapshot), "an unmarked old file: storage matches, no marker");
            assertFalse(nbtMatches(orderBefore(looseAndBoxed(24, 8)), snapshot), "this delivery's before marker");
            assertFalse(nbtMatches(markedAs(looseAndBoxed(24, 8), ItemSaveProof.DELIVERY_MARKER_KEY,
                    ItemSaveProof.marker(UUID.randomUUID(), true)), snapshot), "another delivery's after marker");
            assertFalse(nbtMatches(markedAs(looseAndBoxed(24, 8), ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(ORDER, true)),
                    snapshot), "this nonce's after marker under the return key");
            assertFalse(nbtMatches(markedAs(looseAndBoxed(24, 8), ItemSaveProof.MARKER_KEY, ItemSaveProof.marker(ORDER, true)),
                    snapshot), "this nonce's after marker under the hand-in key");
            assertFalse(nbtMatches(orderAfter(looseAndBoxed(24, 16)), snapshot), "the whole file 8 over: storage alone would pass it");
            assertFalse(nbtMatches(orderAfter(looseAndBoxed(24, 7)), snapshot), "the whole file 1 short");
            assertFalse(nbtMatches(orderAfter(looseAndBoxed(23, 9)), snapshot), "the whole file is right but storage is not the after count");
            assertTrue(nbtMatches(orderAfter(looseAndOffhand(24, 8)), snapshot), "the box's 8 moved to the offhand: still 32 in the file");
        }
    }

    /**
     * A stale, unmarked file F0 happens to hold the 24 bread the delivery recorded. With the marker off, capture()
     * accepts it, because only the count is checked. With the marker on, the same file is refused, and it could never
     * have served as this delivery's before-save either.
     */
    @Test void theStaleFileCoincidenceIsAcceptedByTheDev58ProofAndRefusedWithTheMarker() throws Exception {
        ItemCustody batch = orderBatch(ItemCustody.Source.MERCHANT_GOODS, ORDER, 8);
        byte[] stale = looseBread(24);
        Path world = world(stale);
        var dev58 = proof.capture(world, batch, evidence(batch, 16, 24)).orElseThrow();
        assertTrue(nbtMatches(stale, dev58), "the unmarked proof accepts the stale file");
        byte[] beforeDelivery = orderBefore(looseBread(16));
        var baseline = proof.deliveryBaselineOf(file(world), beforeDelivery, batch).orElseThrow();
        var marked = proof.captureDelivery(world, batch, evidence(batch, 16, 24), baseline).orElseThrow();
        assertFalse(nbtMatches(stale, marked), "the marked proof refuses the stale file");
        assertTrue(nbtMatches(orderAfter(looseBread(24)), marked), "its own save passes");
        assertTrue(proof.deliveryBaselineOf(file(world), stale, batch).isEmpty(), "a swallowed before-save is refused before any give");
        // The stable read: with file identities verify sees F0 on disk; without them (Windows) it refuses everything.
        if (Files.readAttributes(file(world), java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null) {
            assertFalse(proof.verify(dev58));
            assertFalse(proof.verify(marked));
        } else {
            assertTrue(proof.verify(dev58));
            assertFalse(proof.verify(marked));
        }
    }

    @Test void aDeliveryBaselineIsThisDeliverysBeforeSaveForThisPlayerAndKey() throws Exception {
        ItemCustody batch = orderBatch(ItemCustody.Source.MERCHANT_GOODS, ORDER, 8);
        byte[] beforeDelivery = orderBefore(looseAndBoxed(16, 8));
        Path world = world(beforeDelivery);
        var baseline = proof.deliveryBaselineOf(file(world), beforeDelivery, batch);
        assertEquals(List.of(24), baseline.orElseThrow().units(), "the whole file, the box included");
        assertTrue(proof.deliveryBaselineOf(file(world), looseAndBoxed(16, 8), batch).isEmpty(), "no marker");
        assertTrue(proof.deliveryBaselineOf(file(world), orderAfter(looseAndBoxed(16, 8)), batch).isEmpty(), "the after marker");
        assertTrue(proof.deliveryBaselineOf(file(world), markedAs(looseAndBoxed(16, 8), ItemSaveProof.RETURN_MARKER_KEY,
                ItemSaveProof.marker(ORDER, false)), batch).isEmpty(), "the before marker under the return key");
        assertTrue(proof.deliveryBaselineOf(file(world), markedAs(looseAndBoxed(16, 8), ItemSaveProof.MARKER_KEY,
                ItemSaveProof.marker(ORDER, false)), batch).isEmpty(), "the before marker under the hand-in key");
        assertTrue(proof.deliveryBaselineOf(file(world), markedAs(looseAndBoxed(16, 8), ItemSaveProof.DELIVERY_MARKER_KEY,
                ItemSaveProof.marker(UUID.randomUUID(), false)), batch).isEmpty(), "another delivery's save");
        Object other = parse(beforeDelivery);
        compound.getMethod("putIntArray", String.class, int[].class).invoke(other, "UUID", new int[4]);
        assertTrue(proof.deliveryBaselineOf(file(world), encode(other), batch).isEmpty(), "another player's file");
        Object older = parse(beforeDelivery);
        compound.getMethod("putInt", String.class, int.class).invoke(older, "DataVersion", 4789);
        assertTrue(proof.deliveryBaselineOf(file(world), encode(older), batch).isEmpty(), "a file at another DataVersion");
        // captureDelivery binds the baseline to this proof, delivery, player, item and key.
        var evidence = evidence(batch, 16, 24);
        assertTrue(proof.captureDelivery(world, batch, evidence, baseline.get()).isPresent(), "a capture with its baseline is accepted");
        assertTrue(proof.captureDelivery(world, batch, evidence, null).isEmpty(), "no before-delivery save");
        var foreign = new ItemSaveProof(loader).deliveryBaselineOf(file(world), beforeDelivery, batch).orElseThrow();
        assertTrue(proof.captureDelivery(world, batch, evidence, foreign).isEmpty(), "another proof's read");
        ItemCustody otherOrder = orderBatch(ItemCustody.Source.MERCHANT_GOODS, UUID.randomUUID(), 8);
        assertTrue(proof.captureDelivery(world, otherOrder, evidence(otherOrder, 16, 24), baseline.get()).isEmpty(), "another delivery");
        // Same nonce, other source: only the key tells them apart.
        ItemCustody returnSameNonce = new ItemCustody(ORDER, BOT, UUID.randomUUID(), ItemCustody.Source.REPAIR_RETURN, 8,
                batch.payload(), batch.reservedAt(), batch.expiresAt());
        assertTrue(proof.captureDelivery(world, returnSameNonce, evidence(returnSameNonce, 16, 24), baseline.get()).isEmpty(),
                "a commerce baseline for a return");
        ItemCustody returned = returnBatch(8);
        var returnBaseline = proof.deliveryBaselineOf(file(world), returnBefore(looseAndBoxed(16, 8)), returned).orElseThrow();
        assertTrue(proof.captureDelivery(world, returned, evidence(returned, 16, 24), returnBaseline).isPresent(), "a return is accepted");
        ItemCustody orderSameNonce = orderBatch(ItemCustody.Source.WORKER_OUTPUT, RETURN, 8);
        assertTrue(proof.captureDelivery(world, orderSameNonce, evidence(orderSameNonce, 16, 24), returnBaseline).isEmpty(),
                "a return baseline for a commerce batch");
    }

    @Test void readSavedReportsTheDeliveryMarker() throws Exception {
        var bread = new CustodyBytes(onePrototype());
        var plain = proof.readSaved(looseBread(16), BOT, List.of(bread)).orElseThrow();
        assertNull(plain.deliveryMarker());
        var order = proof.readSaved(orderAfter(looseBread(16)), BOT, List.of(bread)).orElseThrow();
        assertEquals(ItemSaveProof.marker(ORDER, true), order.deliveryMarker(), "the order's after marker is found");
        assertNull(order.returnMarker());
        assertNull(order.handInMarker());
        // All three keys at once: each is read on its own.
        Object all = parse(orderBefore(looseBread(16)));
        Object values = get.invoke(all, "BukkitValues");
        compound.getMethod("putString", String.class, String.class).invoke(values, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(RETURN, true));
        compound.getMethod("putString", String.class, String.class).invoke(values, ItemSaveProof.MARKER_KEY, ItemSaveProof.marker(HAND_IN, false));
        var three = proof.readSaved(encode(all), BOT, List.of(bread)).orElseThrow();
        assertEquals(ItemSaveProof.marker(ORDER, false), three.deliveryMarker());
        assertEquals(ItemSaveProof.marker(RETURN, true), three.returnMarker());
        assertEquals(ItemSaveProof.marker(HAND_IN, false), three.handInMarker());
        var onlyReturn = proof.readSaved(returnAfter(looseBread(16)), BOT, List.of(bread)).orElseThrow();
        assertNull(onlyReturn.deliveryMarker(), "the return key is not the delivery key");
        assertEquals(ItemSaveProof.marker(RETURN, true), onlyReturn.returnMarker());
    }

    @Test void readSavedDeliveryCountsTheBatchShape() throws Exception {
        ItemCustody batch = orderBatch(ItemCustody.Source.MERCHANT_GOODS, ORDER, 8);
        int fileVersion = (int) ((java.util.Optional<?>) compound.getMethod("getInt", String.class).invoke(fixture(), "DataVersion")).orElseThrow();
        var read = proof.readSavedDelivery(orderAfter(looseAndBoxed(24, 8)), batch).orElseThrow();
        assertEquals(List.of(24), read.units(), "storage and ender chest");
        assertEquals(List.of(32), read.wholeFile(), "the box's 8 count in the whole file");
        assertEquals(ItemSaveProof.marker(ORDER, true), read.deliveryMarker());
        assertEquals(fileVersion, read.fileDataVersion());
        assertEquals(1, read.recordedDataVersions().size(), "the payload's DataVersion");
        assertNull(ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()));
        assertEquals(List.of(32), proof.readSavedDelivery(looseAndOffhand(24, 8), batch).orElseThrow().wholeFile(), "the offhand counts");
        assertEquals(List.of(0), proof.readSavedDelivery(markedAs(fixtureWithout(), ItemSaveProof.DELIVERY_MARKER_KEY, "x"), batch)
                .orElseThrow().units(), "no bread at all");
        // The payload must be the batch: its count is the quantity, not one item.
        ItemCustody wrong = new ItemCustody(ORDER, BOT, UUID.randomUUID(), ItemCustody.Source.MERCHANT_GOODS, 7, batch.payload(),
                batch.reservedAt(), batch.expiresAt());
        assertTrue(proof.readSavedDelivery(looseBread(24), wrong).isEmpty(), "a payload of 8 is not a batch of 7");
        Object other = parse(looseBread(24));
        compound.getMethod("putIntArray", String.class, int[].class).invoke(other, "UUID", new int[4]);
        assertTrue(proof.readSavedDelivery(encode(other), batch).isEmpty(), "another player's file");
        assertTrue(proof.readSavedDelivery(new byte[0], batch).isEmpty());
    }
    /** The fixture with every bread removed (loose bread only exists in slot 2). */
    private static byte[] fixtureWithout() throws Exception { return encode(withoutLooseBread()); }

    @Test void collectionProofRefusesALinkedPlayerFile() throws Exception {
        byte[] saved = Base64.getDecoder().decode(FIXTURE);
        Path world = temporary.resolve("linked-world");
        Path real = temporary.resolve("elsewhere.dat");
        Files.write(real, saved);
        Path link = world.resolve(relativeData).resolve(BOT + ".dat");
        Files.createDirectories(link.getParent());
        try { Files.createSymbolicLink(link, real); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException noLinks) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable here");
        }
        var snapshot = proof.captureCollection(world, BOT, List.of(expect(new CustodyBytes(onePrototype()), 24))).orElseThrow();
        assertFalse(proof.verify(snapshot));
    }
}
