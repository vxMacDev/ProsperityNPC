package com.prosperitysmp.npc.commerce;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Readback evidence after saveData(), not authorization or permission to replay an intent. */
public final class ItemSaveProof {
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_NBT_HEAP = 8 * 1024 * 1024;
    private final Nbt nbt;
    private final Path dataDirectory;

    /** Pass the actual server classloader; unsupported installed NBT APIs fail closed. */
    public ItemSaveProof(ClassLoader serverClassLoader) {
        Nbt resolved = null;
        Path directory = null;
        try {
            Objects.requireNonNull(serverClassLoader);
            Nbt candidate = new Nbt(serverClassLoader);
            Class<?> resource = Class.forName("net.minecraft.world.level.storage.LevelResource", true, serverClassLoader);
            String id = (String) resource.getMethod("id").invoke(resource.getField("PLAYER_DATA_DIR").get(null));
            if (!id.matches("[a-z_]+(?:/[a-z_]+){0,2}")) throw new IllegalArgumentException("Unsupported player-data path");
            directory = Path.of(id);
            resolved = candidate;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // No fallback parser, guessed directory, or unlimited-heap NBT read.
        }
        nbt = resolved;
        dataDirectory = directory;
    }

    public boolean available() { return nbt != null; }

    /** Startup preflight only; each take or delivery still needs its own saved-file proof. */
    public boolean storageAvailable(Path mainWorldFolder) {
        if (!available() || mainWorldFolder == null) return false;
        try {
            directories(mainWorldFolder.toAbsolutePath().normalize().resolve(dataDirectory));
            return true;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Main only: obtain the root from the same PlayerDataStorage that CraftPlayer.saveData uses. */
    public Optional<Path> mainWorldFolder(Object craftServer) {
        if (!available() || craftServer == null
                || !craftServer.getClass().getName().equals("org.bukkit.craftbukkit.CraftServer")) return Optional.empty();
        try {
            Object players = craftServer.getClass().getMethod("getHandle").invoke(craftServer);
            Object storage = players.getClass().getField("playerIo").get(players);
            Object value = storage.getClass().getMethod("getPlayerDir").invoke(storage);
            if (!(value instanceof File directory)) return Optional.empty();
            Path actual = directory.toPath().toAbsolutePath().normalize();
            Path world = actual;
            for (int i = 0; i < dataDirectory.getNameCount(); i++) world = world.getParent();
            if (world == null || !world.resolve(dataDirectory).equals(actual)) return Optional.empty();
            return Optional.of(world);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError uncertain) {
            return Optional.empty();
        }
    }

    /**
     * Capture on main, after the adjacent inventory mutation/save; this performs no file I/O. Unmarked commerce
     * deliveries only (town.commerce.delivery-save-marker off), checked by the storage and ender chest count alone. A
     * REPAIR_RETURN batch is refused here and must use {@link #captureDelivery}, which also proves the save is its own.
     */
    public Optional<Snapshot> capture(Path mainWorldFolder, ItemCustody batch, ItemDeliveryEvidence evidence) {
        if (batch == null || batch.source() == ItemCustody.Source.REPAIR_RETURN) return Optional.empty();
        return capture(mainWorldFolder, batch, evidence, null);
    }

    /**
     * Capture on main for a marked delivery (every REPAIR_RETURN, and commerce with the save marker on), after its after
     * marker, the delivery and its save; no file I/O. The baseline is this delivery's before-delivery save, read back by
     * {@link #deliveryBaseline} under {@link #markerKeyFor} the batch's source, for the same player, delivery and item.
     * {@link #verify(Snapshot)} then also needs the delivery's after marker and the whole file to hold exactly the
     * baseline units plus the batch.
     */
    public Optional<Snapshot> captureDelivery(Path mainWorldFolder, ItemCustody batch, ItemDeliveryEvidence evidence,
                                              Baseline baseline) {
        if (batch == null || baseline == null || baseline.owner != this
                || !markerKeyFor(batch.source()).equals(baseline.key) || !baseline.player.equals(batch.playerId())
                || !baseline.collectionId.equals(batch.requestNonce()) || baseline.units.size() != 1) return Optional.empty();
        var snapshot = capture(mainWorldFolder, batch, evidence, baseline);
        if (snapshot.isEmpty()) return snapshot;
        Snapshot value = snapshot.get();
        if (!value.file.equals(baseline.file) || value.version != baseline.version || !List.of(value.prototype).equals(baseline.shapes))
            return Optional.empty();
        return snapshot;
    }

    private Optional<Snapshot> capture(Path mainWorldFolder, ItemCustody batch, ItemDeliveryEvidence evidence, Baseline baseline) {
        if (!available() || mainWorldFolder == null || batch == null || evidence == null || !evidence.confirms(batch))
            return Optional.empty();
        try {
            Object prototype = nbt.read(batch.payload().bytes(), 512 * 1024);
            int version = nbt.integer(prototype, "DataVersion");
            if (version <= 0 || nbt.count(prototype) != batch.quantity() || !nbt.hasItemId(prototype))
                return Optional.empty();
            Path world = mainWorldFolder.toAbsolutePath().normalize();
            Path file = world.resolve(dataDirectory).resolve(batch.playerId() + ".dat").normalize();
            if (!file.startsWith(world)) return Optional.empty();
            return Optional.of(new Snapshot(this, file, batch, evidence, version, nbt.shape(prototype), baseline));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError uncertain) {
            return Optional.empty();
        }
    }

    /** Worker only, under the runtime's existing exclusive player-inventory custody. */
    public boolean verify(Snapshot snapshot) {
        if (!available() || snapshot == null || snapshot.owner != this) return false;
        return verifyFile(snapshot.file, bytes -> matches(bytes, snapshot));
    }

    /**
     * The persistent data key a hand-in writes into the player's saved data before each of its two saves
     * (ItemInventoryCustody.saveBeforeTake and collect). A file carrying a marker was written after that marker was set.
     */
    public static final String MARKER_KEY = "prosperitynpc:item_take";

    /** The marker value for one hand-in, before its take or after it. A fresh hand-in id means no older file has it. */
    public static String marker(UUID collectionId, boolean afterTake) {
        return Objects.requireNonNull(collectionId) + (afterTake ? "/after" : "/before");
    }

    /**
     * The persistent data key a REPAIR_RETURN delivery writes into the player's saved data: its before marker before the
     * save just ahead of the delivery (ItemInventoryCustody.saveBeforeDelivery), and its after marker just before the
     * delivery's own save. Its own key, so a return never overwrites a hand-in's marker. The value is
     * {@link #marker}(request nonce, after); a nonce is delivered at most once.
     */
    public static final String RETURN_MARKER_KEY = "prosperitynpc:item_return";

    /**
     * The same markers for a commerce delivery (MERCHANT_GOODS or WORKER_OUTPUT) while
     * town.commerce.delivery-save-marker is on. Its own key, so an order never overwrites a hand-in's or a return's marker.
     */
    public static final String DELIVERY_MARKER_KEY = "prosperitynpc:item_delivery";

    /** The marker key a delivery of this source writes and its proof reads. */
    static String markerKeyFor(ItemCustody.Source source) {
        return switch (Objects.requireNonNull(source)) {
            case REPAIR_RETURN -> RETURN_MARKER_KEY;
            case MERCHANT_GOODS, WORKER_OUTPUT -> DELIVERY_MARKER_KEY;
        };
    }

    /**
     * Worker only, under the player's inventory gate, before a marked delivery: reads back and forces the save
     * ItemInventoryCustody.saveBeforeDelivery made. Empty unless the file is this player's, carries this delivery's
     * before marker under {@link #markerKeyFor} its source (so that save really happened) and is at the batch's
     * DataVersion. The units are every unit of the item anywhere in the file.
     */
    public Optional<Baseline> deliveryBaseline(Path mainWorldFolder, ItemCustody batch) {
        if (batch == null) return Optional.empty();
        Optional<Path> file = playerFile(mainWorldFolder, batch.playerId());
        if (file.isEmpty()) return Optional.empty();
        var found = new AtomicReference<Baseline>();
        boolean read = verifyFile(file.get(), bytes -> {
            var value = deliveryBaselineOf(file.get(), bytes, batch);
            value.ifPresent(found::set);
            return value.isPresent();
        });
        return read ? Optional.ofNullable(found.get()) : Optional.empty();
    }

    /** No I/O: the before-delivery baseline these saved bytes give for a batch, or empty. */
    Optional<Baseline> deliveryBaselineOf(Path file, byte[] bytes, ItemCustody batch) {
        if (!available() || file == null || bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES || batch == null)
            return Optional.empty();
        try {
            String key = markerKeyFor(batch.source());
            Object prototype = nbt.read(batch.payload().bytes(), 512 * 1024);
            int version = nbt.integer(prototype, "DataVersion");
            if (version <= 0 || nbt.count(prototype) != batch.quantity() || !nbt.hasItemId(prototype)) return Optional.empty();
            Object shape = nbt.shape(prototype);
            Object root = nbt.read(bytes, MAX_NBT_HEAP);
            if (nbt.integer(root, "DataVersion") != version || !nbt.uuidMatches(root, batch.playerId())
                    || !marker(batch.requestNonce(), false).equals(nbt.marker(root, key))) return Optional.empty();
            return Optional.of(new Baseline(this, key, file, batch.playerId(), batch.requestNonce(), version,
                    List.of(shape), List.of(nbt.everywhere(root, shape))));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError uncertain) {
            return Optional.empty();
        }
    }

    /**
     * Worker only, under the player's inventory gate, before the take: reads back and forces the save that
     * ItemInventoryCustody.saveBeforeTake made. Empty unless the file is this player's, carries this hand-in's before
     * marker (so that save really happened), and every prototype is one item at one DataVersion.
     */
    public Optional<Baseline> baseline(Path mainWorldFolder, UUID player, UUID collectionId, List<CustodyBytes> prototypes) {
        Optional<Path> file = playerFile(mainWorldFolder, player);
        if (file.isEmpty() || collectionId == null) return Optional.empty();
        var found = new AtomicReference<Baseline>();
        boolean read = verifyFile(file.get(), bytes -> {
            var value = baselineOf(file.get(), bytes, player, collectionId, prototypes);
            value.ifPresent(found::set);
            return value.isPresent();
        });
        return read ? Optional.ofNullable(found.get()) : Optional.empty();
    }

    /** No I/O: the baseline these saved bytes give for the file, or empty (see {@link #baseline}). */
    Optional<Baseline> baselineOf(Path file, byte[] bytes, UUID player, UUID collectionId, List<CustodyBytes> prototypes) {
        if (!available() || file == null || bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES || player == null
                || collectionId == null || prototypes == null || prototypes.isEmpty()
                || prototypes.size() > ItemCollectionEvidence.MAX_LINES) return Optional.empty();
        try {
            int version = 0;
            var shapes = new ArrayList<Object>();
            for (CustodyBytes bytesOf : prototypes) {
                Object prototype = nbt.read(bytesOf.bytes(), 512 * 1024);
                int current = nbt.integer(prototype, "DataVersion");
                if (current <= 0 || (version != 0 && current != version) || nbt.count(prototype) != 1 || !nbt.hasItemId(prototype))
                    return Optional.empty();
                version = current;
                shapes.add(nbt.shape(prototype));
            }
            Object root = nbt.read(bytes, MAX_NBT_HEAP);
            if (nbt.integer(root, "DataVersion") != version || !nbt.uuidMatches(root, player)
                    || !marker(collectionId, false).equals(nbt.marker(root, MARKER_KEY))) return Optional.empty();
            var units = new ArrayList<Integer>();
            for (Object shape : shapes) units.add(nbt.everywhere(root, shape));
            return Optional.of(new Baseline(this, MARKER_KEY, file, player, collectionId, version, List.copyOf(shapes), units));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError uncertain) {
            return Optional.empty();
        }
    }

    /**
     * Capture on main after an item take and save; no file I/O. Each expectation is a one-item prototype, the units
     * the saved file must hold in inventory storage plus ender chest, and the units the take removed. The baseline is
     * the save read back before the take, for the same player and the same prototypes in the same order. Shared by
     * every hand-in feature.
     */
    public Optional<CollectionSnapshot> captureCollection(Path mainWorldFolder, UUID player, List<Expectation> expectations,
                                                          Baseline baseline) {
        if (baseline == null || baseline.owner != this || !MARKER_KEY.equals(baseline.key) || !baseline.player.equals(player))
            return Optional.empty();
        var snapshot = captureCollection(mainWorldFolder, player, expectations, baseline, true);
        if (snapshot.isEmpty()) return snapshot;
        CollectionSnapshot value = snapshot.get();
        if (!value.file.equals(baseline.file) || value.version != baseline.version || !value.shapes.equals(baseline.shapes))
            return Optional.empty();
        for (int i = 0; i < expectations.size(); i++)
            if (expectations.get(i).takenUnits() > baseline.units.get(i)) return Optional.empty();
        return snapshot;
    }

    /** For tests of the storage counts only: no baseline, so {@link #verify(CollectionSnapshot)} always refuses it. */
    Optional<CollectionSnapshot> captureCollection(Path mainWorldFolder, UUID player, List<Expectation> expectations) {
        return captureCollection(mainWorldFolder, player, expectations, null, false);
    }

    private Optional<CollectionSnapshot> captureCollection(Path mainWorldFolder, UUID player, List<Expectation> expectations,
                                                           Baseline baseline, boolean bound) {
        if (!available() || mainWorldFolder == null || player == null || expectations == null || expectations.isEmpty()
                || expectations.size() > ItemCollectionEvidence.MAX_LINES || bound != (baseline != null)) return Optional.empty();
        try {
            int version = 0;
            var shapes = new ArrayList<Object>();
            for (Expectation expectation : expectations) {
                Object prototype = nbt.read(expectation.prototype().bytes(), 512 * 1024);
                int current = nbt.integer(prototype, "DataVersion");
                if (current <= 0 || (version != 0 && current != version) || nbt.count(prototype) != 1 || !nbt.hasItemId(prototype))
                    return Optional.empty();
                version = current;
                shapes.add(nbt.shape(prototype));
            }
            Path world = mainWorldFolder.toAbsolutePath().normalize();
            Path file = world.resolve(dataDirectory).resolve(player + ".dat").normalize();
            if (!file.startsWith(world)) return Optional.empty();
            return Optional.of(new CollectionSnapshot(this, file, player, version, List.copyOf(shapes),
                    expectations.stream().map(Expectation::expectedUnits).toList(),
                    expectations.stream().map(Expectation::takenUnits).toList(), baseline));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError uncertain) {
            return Optional.empty();
        }
    }

    /** The player's saved file under a world folder, or empty when unavailable or outside the world. */
    public Optional<Path> playerFile(Path worldFolder, UUID player) {
        if (!available() || worldFolder == null || player == null) return Optional.empty();
        Path world = worldFolder.toAbsolutePath().normalize();
        Path file = world.resolve(dataDirectory).resolve(player + ".dat").normalize();
        return file.startsWith(world) ? Optional.of(file) : Optional.empty();
    }

    /**
     * What an offline read found: units per prototype in inventory storage plus ender chest, the saved file's DataVersion
     * (null when the file has none) and the distinct DataVersions recorded on the prototypes, in order (empty when none
     * carries one). wholeFile is every unit of each prototype anywhere in the file, nested containers and a ridden
     * animal's chest included. handInMarker, returnMarker and deliveryMarker are the markers a hand-in, a return delivery
     * and a marked commerce delivery write before their saves, as found in the file (null when absent).
     */
    public record SavedUnits(List<Integer> units, Integer fileDataVersion, List<Integer> recordedDataVersions,
                             List<Integer> wholeFile, String handInMarker, String returnMarker, String deliveryMarker) {
        public SavedUnits {
            units = List.copyOf(units);
            recordedDataVersions = List.copyOf(recordedDataVersions);
            wholeFile = List.copyOf(wholeFile);
            if (wholeFile.size() != units.size()) throw new IllegalArgumentException("One whole-file count per line");
        }

        /** A read with no commerce delivery marker. */
        public SavedUnits(List<Integer> units, Integer fileDataVersion, List<Integer> recordedDataVersions,
                          List<Integer> wholeFile, String handInMarker, String returnMarker) {
            this(units, fileDataVersion, recordedDataVersions, wholeFile, handInMarker, returnMarker, null);
        }
    }

    /**
     * Offline evidence only (the server stopped): plain units per prototype in a saved player file, inventory storage
     * plus ender chest and the whole file, with both DataVersions and the hand-in, return and delivery markers. Empty
     * when the bytes are not that player's readable file or a prototype is not one item. The counts are only comparable
     * when {@link #dataVersionProblem} finds nothing: after a server upgrade an item can be saved in a new shape, and
     * then it reads as 0.
     */
    public Optional<SavedUnits> readSaved(byte[] savedFile, UUID player, List<CustodyBytes> prototypes) {
        if (!available() || savedFile == null || savedFile.length == 0 || savedFile.length > MAX_FILE_BYTES || player == null
                || prototypes == null || prototypes.isEmpty() || prototypes.size() > ItemCollectionEvidence.MAX_LINES)
            return Optional.empty();
        try {
            var shapes = new ArrayList<Object>();
            var recorded = new ArrayList<Integer>();
            for (CustodyBytes bytes : prototypes) {
                Object prototype = nbt.read(bytes.bytes(), 512 * 1024);
                if (nbt.count(prototype) != 1 || !nbt.hasItemId(prototype)) return Optional.empty();
                Integer version = nbt.optionalInteger(prototype, "DataVersion");
                if (version != null && !recorded.contains(version)) recorded.add(version);
                shapes.add(nbt.shape(prototype));
            }
            Object root = nbt.read(savedFile, MAX_NBT_HEAP);
            if (!nbt.uuidMatches(root, player)) return Optional.empty();
            var units = new ArrayList<Integer>();
            var whole = new ArrayList<Integer>();
            for (Object shape : shapes) {
                units.add(Math.addExact(nbt.units(root, "Inventory", shape, false), nbt.units(root, "EnderItems", shape, true)));
                whole.add(nbt.everywhere(root, shape));
            }
            return Optional.of(new SavedUnits(units, nbt.optionalInteger(root, "DataVersion"), recorded, whole,
                    nbt.marker(root, MARKER_KEY), nbt.marker(root, RETURN_MARKER_KEY), nbt.marker(root, DELIVERY_MARKER_KEY)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Offline evidence only (the server stopped), for one delivery batch: its payload is the prototype (count equal to the
     * batch quantity), and the units of that item in storage plus ender chest and in the whole file are read the way the
     * delivery proof counts them. recordedDataVersions is the payload's DataVersion (empty when it has none). Empty when
     * the bytes are not the batch player's readable file or the payload is not the batch.
     */
    public Optional<SavedUnits> readSavedDelivery(byte[] savedFile, ItemCustody batch) {
        if (!available() || savedFile == null || savedFile.length == 0 || savedFile.length > MAX_FILE_BYTES || batch == null)
            return Optional.empty();
        try {
            Object prototype = nbt.read(batch.payload().bytes(), 512 * 1024);
            if (nbt.count(prototype) != batch.quantity() || !nbt.hasItemId(prototype)) return Optional.empty();
            Integer version = nbt.optionalInteger(prototype, "DataVersion");
            Object shape = nbt.shape(prototype);
            Object root = nbt.read(savedFile, MAX_NBT_HEAP);
            if (!nbt.uuidMatches(root, batch.playerId())) return Optional.empty();
            int units = Math.addExact(nbt.units(root, "Inventory", shape, false), nbt.units(root, "EnderItems", shape, true));
            return Optional.of(new SavedUnits(List.of(units), nbt.optionalInteger(root, "DataVersion"),
                    version == null ? List.of() : List.of(version), List.of(nbt.everywhere(root, shape)),
                    nbt.marker(root, MARKER_KEY), nbt.marker(root, RETURN_MARKER_KEY), nbt.marker(root, DELIVERY_MARKER_KEY)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Null when the saved file's DataVersion is the one recorded with the items, or when no DataVersion was ever recorded
     * with them (older records, which are not checked). Otherwise why the counts cannot be trusted.
     */
    public static String dataVersionProblem(Integer fileDataVersion, List<Integer> recordedDataVersions) {
        if (recordedDataVersions == null || recordedDataVersions.isEmpty()) return null;
        String skipped = " The tool skips this check only when no DataVersion was recorded with the items.";
        if (recordedDataVersions.size() > 1)
            return "The items were recorded at more than one DataVersion " + recordedDataVersions + ", so the counts cannot be trusted."
                    + skipped;
        int recorded = recordedDataVersions.getFirst();
        if (fileDataVersion == null)
            return "The player file has no DataVersion, but the items were recorded at DataVersion " + recorded + "." + skipped;
        if (fileDataVersion != recorded)
            return "The player file is DataVersion " + fileDataVersion + " but the items were recorded at DataVersion " + recorded
                    + ". After a server upgrade an item can be saved differently and read as 0, so the counts cannot be trusted." + skipped;
        return null;
    }

    /**
     * Worker only: reads and forces the saved file, then checks every expected unit count and that this file is the
     * take's own save. See {@link #matchesCollection}.
     */
    public boolean verify(CollectionSnapshot snapshot) {
        if (!available() || snapshot == null || snapshot.owner != this) return false;
        return verifyFile(snapshot.file, bytes -> matchesCollection(bytes, snapshot));
    }

    @FunctionalInterface private interface FileMatch { boolean test(byte[] bytes) throws ReflectiveOperationException; }

    /** Stable read, content match, then force the file and its directory; any doubt is false. */
    private boolean verifyFile(Path target, FileMatch match) {
        try {
            List<Identity> directories = directories(target.getParent());
            BasicFileAttributes before = attributes(target);
            if (!before.isRegularFile() || before.fileKey() == null || before.size() <= 0 || before.size() > MAX_FILE_BYTES)
                return false;
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() != before.size() || !sameFile(before, attributes(target))) return false;
                ByteBuffer bytes = ByteBuffer.allocate((int) before.size());
                while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) return false;
                if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != before.size()) return false;
                if (!match.test(bytes.array())) return false;
                if (!sameFile(before, attributes(target)) || !sameDirectories(directories)) return false;
                channel.force(true);
                // The save replaces a directory entry; forcing only its inode is insufficient.
                try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS)) { directory.force(true); }
                return channel.size() == before.size() && sameFile(before, attributes(target))
                        && sameDirectories(directories);
            }
        } catch (Exception | LinkageError uncertain) {
            return false;
        }
    }

    /**
     * The removal proof. Storage plus ender chest must hold the expected units, and the file must be the save made after
     * the take: it carries this hand-in's after marker (so it differs from the before-take save), and every unit of each line
     * anywhere in the file (storage, hotbar, offhand, armor, ender chest, nested shulker boxes and bundles, a ridden
     * animal's chest) dropped by exactly the units taken. A stale file, or one with the recipe moved somewhere the
     * storage count does not look, is refused.
     */
    private boolean matchesCollection(byte[] bytes, CollectionSnapshot snapshot) throws ReflectiveOperationException {
        if (!storageMatches(bytes, snapshot)) return false;
        // captureCollection bound the baseline to this proof, file, player, DataVersion and prototypes.
        Baseline before = snapshot.baseline;
        if (before == null) return false;
        // The after marker also proves this file differs from the before-take save, which can only carry the before one.
        Object root = nbt.read(bytes, MAX_NBT_HEAP);
        if (!marker(before.collectionId, true).equals(nbt.marker(root, MARKER_KEY))) return false;
        for (int i = 0; i < snapshot.shapes.size(); i++)
            if (nbt.everywhere(root, snapshot.shapes.get(i)) != before.units.get(i) - snapshot.taken.get(i)) return false;
        return true;
    }

    /** Storage plus ender chest hold exactly the expected units, per line. */
    private boolean storageMatches(byte[] bytes, CollectionSnapshot snapshot) throws ReflectiveOperationException {
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || snapshot == null || snapshot.owner != this) return false;
        Object root = nbt.read(bytes, MAX_NBT_HEAP);
        if (nbt.integer(root, "DataVersion") != snapshot.version || !nbt.uuidMatches(root, snapshot.player)) return false;
        int[] persisted = new int[snapshot.shapes.size()];
        for (int i = 0; i < persisted.length; i++)
            persisted[i] = Math.addExact(nbt.units(root, "Inventory", snapshot.shapes.get(i), false),
                    nbt.units(root, "EnderItems", snapshot.shapes.get(i), true));
        return unitsMatch(persisted, snapshot.expected);
    }

    /** Every line's saved units equal the expected units, in order. */
    static boolean unitsMatch(int[] persisted, List<Integer> expected) {
        if (persisted.length != expected.size() || persisted.length == 0) return false;
        for (int i = 0; i < persisted.length; i++) if (persisted[i] != expected.get(i)) return false;
        return true;
    }

    /**
     * One plain item prototype (count 1), the units the saved file must hold for it in storage plus ender chest, and the
     * units the take removed (the drop the whole file must show against the before-take save).
     */
    public record Expectation(CustodyBytes prototype, int expectedUnits, int takenUnits) {
        public Expectation {
            Objects.requireNonNull(prototype);
            if (expectedUnits < 0 || expectedUnits > ItemDeliveryEvidence.MAX_OBSERVED_UNITS)
                throw new IllegalArgumentException("Invalid expected units");
            if (takenUnits < 0 || takenUnits > ItemCustody.MAX_QUANTITY) throw new IllegalArgumentException("Invalid taken units");
        }
    }

    /** Opaque, immutable binding of the saved file, player, prototypes, expected unit counts and the before-take save. */
    public static final class CollectionSnapshot {
        private final ItemSaveProof owner;
        private final Path file;
        private final UUID player;
        private final int version;
        private final List<Object> shapes;
        private final List<Integer> expected;
        private final List<Integer> taken;
        private final Baseline baseline;

        private CollectionSnapshot(ItemSaveProof owner, Path file, UUID player, int version, List<Object> shapes,
                                   List<Integer> expected, List<Integer> taken, Baseline baseline) {
            this.owner = owner;
            this.file = file;
            this.player = player;
            this.version = version;
            this.shapes = shapes;
            this.expected = List.copyOf(expected);
            this.taken = List.copyOf(taken);
            this.baseline = baseline;
        }
    }

    /**
     * Opaque, immutable read of the save made just before a take or a marked delivery: its file, hand-in or delivery and
     * every unit of each line anywhere in it.
     */
    public static final class Baseline {
        private final ItemSaveProof owner;
        /** MARKER_KEY for a hand-in's before-take save, {@link #markerKeyFor} the source for a delivery's before-delivery save. */
        private final String key;
        private final Path file;
        private final UUID player;
        private final UUID collectionId;
        private final int version;
        private final List<Object> shapes;
        private final List<Integer> units;

        private Baseline(ItemSaveProof owner, String key, Path file, UUID player, UUID collectionId, int version,
                         List<Object> shapes, List<Integer> units) {
            this.owner = owner;
            this.key = key;
            this.file = file;
            this.player = player;
            this.collectionId = collectionId;
            this.version = version;
            this.shapes = shapes;
            this.units = List.copyOf(units);
        }

        /** Every unit of each line anywhere in the before-take save, for tests and reports. */
        public List<Integer> units() { return units; }
    }

    /**
     * Storage plus ender chest hold the recorded after units. A marked delivery (bound to its before-delivery save: every
     * REPAIR_RETURN, and commerce with the save marker on) must also be the delivery's own save: it carries this
     * delivery's after marker under the baseline's key, and every unit of the item anywhere in the file is exactly the
     * before-delivery count plus the batch. A failed save, the old file or a file with the items moved somewhere storage
     * does not look is refused. An unmarked commerce delivery (no baseline) is checked by the count alone; an unmarked
     * return is refused.
     */
    private boolean matches(byte[] bytes, Snapshot snapshot) throws ReflectiveOperationException {
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || snapshot == null || snapshot.owner != this) return false;
        Object root = nbt.read(bytes, MAX_NBT_HEAP);
        if (nbt.integer(root, "DataVersion") != snapshot.version || !nbt.uuidMatches(root, snapshot.batch.playerId())) return false;
        int persisted = Math.addExact(nbt.units(root, "Inventory", snapshot.prototype, false),
                nbt.units(root, "EnderItems", snapshot.prototype, true));
        if (persisted != snapshot.evidence.afterUnits() || !snapshot.evidence.confirms(snapshot.batch)) return false;
        Baseline before = snapshot.baseline;
        if (before == null) return snapshot.batch.source() != ItemCustody.Source.REPAIR_RETURN;
        return marker(before.collectionId, true).equals(nbt.marker(root, before.key))
                && nbt.everywhere(root, snapshot.prototype) == Math.addExact(before.units.getFirst(), snapshot.batch.quantity());
    }

    /** Opaque, immutable binding of the prototype, batch, immediately adjacent unit counts and, for a marked delivery, its baseline. */
    public static final class Snapshot {
        private final ItemSaveProof owner;
        private final Path file;
        private final ItemCustody batch;
        private final ItemDeliveryEvidence evidence;
        private final int version;
        private final Object prototype;
        private final Baseline baseline;

        private Snapshot(ItemSaveProof owner, Path file, ItemCustody batch,
                         ItemDeliveryEvidence evidence, int version, Object prototype, Baseline baseline) {
            this.owner = owner;
            this.file = file;
            this.batch = batch;
            this.evidence = evidence;
            this.version = version;
            this.prototype = prototype;
            this.baseline = baseline;
        }
    }

    private record Identity(Path path, Object key) { }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (value.isSymbolicLink() || value.isOther() || !path.toRealPath().equals(path))
            throw new IOException("Unstable or linked player-data path");
        return value;
    }

    private static List<Identity> directories(Path directory) throws IOException {
        List<Identity> identities = new ArrayList<>();
        Path next = directory.getRoot();
        for (Path part : directory) {
            next = next.resolve(part);
            BasicFileAttributes value = attributes(next);
            if (!value.isDirectory() || value.fileKey() == null) throw new IOException("Unknown directory identity");
            identities.add(new Identity(next, value.fileKey()));
        }
        return identities;
    }

    private static boolean sameDirectories(List<Identity> identities) throws IOException {
        for (Identity identity : identities) {
            BasicFileAttributes value = attributes(identity.path());
            if (!value.isDirectory() || !identity.key().equals(value.fileKey())) return false;
        }
        return true;
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile() && before.fileKey().equals(after.fileKey()) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static final class Nbt {
        private final Class<?> compound;
        private final Constructor<?> accounter;
        private final Method readCompressed, get, getId, getInt, getByte, getString, getIntArray, copy, remove, entrySet;

        Nbt(ClassLoader loader) throws ReflectiveOperationException {
            compound = Class.forName("net.minecraft.nbt.CompoundTag", true, loader);
            Class<?> tag = Class.forName("net.minecraft.nbt.Tag", true, loader);
            Class<?> account = Class.forName("net.minecraft.nbt.NbtAccounter", true, loader);
            Class<?> io = Class.forName("net.minecraft.nbt.NbtIo", true, loader);
            accounter = account.getConstructor(long.class, int.class);
            readCompressed = io.getMethod("readCompressed", InputStream.class, account);
            get = compound.getMethod("get", String.class);
            getId = tag.getMethod("getId");
            getInt = compound.getMethod("getInt", String.class);
            getByte = compound.getMethod("getByte", String.class);
            getString = compound.getMethod("getString", String.class);
            getIntArray = compound.getMethod("getIntArray", String.class);
            copy = compound.getMethod("copy");
            remove = compound.getMethod("remove", String.class);
            entrySet = compound.getMethod("entrySet");
        }

        /** The root-level marker under this key, or null when there is none or it is not a string. */
        String marker(Object root, String key) throws ReflectiveOperationException {
            Object values = get.invoke(root, "BukkitValues");
            if (values == null || ((Number) getId.invoke(values)).intValue() != 10) return null;
            Object value = get.invoke(values, key);
            if (value == null || ((Number) getId.invoke(value)).intValue() != 8) return null;
            return ((Optional<?>) getString.invoke(values, key)).orElse(null) instanceof String text ? text : null;
        }

        /**
         * Units of the exact prototype anywhere under this tag: every compound in the file that is that item, however
         * deeply nested (inventory, equipment, ender chest, container and bundle contents, a ridden animal's chest).
         * A matching item is counted and not searched further; anything else is searched.
         */
        int everywhere(Object tag, Object prototype) throws ReflectiveOperationException {
            int total = 0;
            if (compound.isInstance(tag)) {
                Object id = get.invoke(tag, "id");
                if (id != null && ((Number) getId.invoke(id)).intValue() == 8
                        && get.invoke(prototype, "id") != null
                        && getString.invoke(tag, "id").equals(getString.invoke(prototype, "id"))
                        && shape(tag).equals(prototype)) return count(tag);
                for (Object entry : (Set<?>) entrySet.invoke(tag))
                    total = Math.addExact(total, everywhere(((Map.Entry<?, ?>) entry).getValue(), prototype));
            } else if (tag instanceof List<?> entries) {
                for (Object entry : entries) total = Math.addExact(total, everywhere(entry, prototype));
            }
            return total;
        }

        Object read(byte[] bytes, long heap) throws ReflectiveOperationException {
            return readCompressed.invoke(null, new ByteArrayInputStream(bytes), accounter.newInstance(heap, 64));
        }

        int number(Object value, String key, int type, Method getter) throws ReflectiveOperationException {
            Object entry = get.invoke(value, key);
            if (entry == null || ((Number) getId.invoke(entry)).intValue() != type)
                throw new IllegalArgumentException("Missing or mistyped NBT number");
            return ((Number) ((Optional<?>) getter.invoke(value, key)).orElseThrow()).intValue();
        }

        int integer(Object value, String key) throws ReflectiveOperationException { return number(value, key, 3, getInt); }

        /** Null when absent; a present value of another type is refused like any malformed number. */
        Integer optionalInteger(Object value, String key) throws ReflectiveOperationException {
            return get.invoke(value, key) == null ? null : integer(value, key);
        }

        int count(Object item) throws ReflectiveOperationException {
            Object modern = get.invoke(item, "count"), legacy = get.invoke(item, "Count");
            if (modern != null && legacy != null) throw new IllegalArgumentException("Ambiguous item count");
            int count = modern != null ? integer(item, "count")
                    : legacy != null ? number(item, "Count", 1, getByte) : 1;
            if (count < 1 || count > ItemCustody.MAX_QUANTITY) throw new IllegalArgumentException("Invalid item count");
            return count;
        }

        boolean hasItemId(Object item) throws ReflectiveOperationException {
            Object id = get.invoke(item, "id");
            if (id == null || ((Number) getId.invoke(id)).intValue() != 8) return false;
            Object value = ((Optional<?>) getString.invoke(item, "id")).orElse(null);
            return value instanceof String name && name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    && !name.equals("minecraft:air");
        }

        Object shape(Object value) throws ReflectiveOperationException {
            Object shape = copy.invoke(value);
            for (String key : List.of("DataVersion", "Slot", "count", "Count")) remove.invoke(shape, key);
            return shape;
        }

        boolean uuidMatches(Object root, UUID player) throws ReflectiveOperationException {
            Object tag = get.invoke(root, "UUID");
            if (tag == null || ((Number) getId.invoke(tag)).intValue() != 11) return false;
            Object value = ((Optional<?>) getIntArray.invoke(root, "UUID")).orElse(null);
            int[] expected = {(int) (player.getMostSignificantBits() >>> 32), (int) player.getMostSignificantBits(),
                    (int) (player.getLeastSignificantBits() >>> 32), (int) player.getLeastSignificantBits()};
            return value instanceof int[] actual && Arrays.equals(expected, actual);
        }

        int units(Object root, String key, Object prototype, boolean ender) throws ReflectiveOperationException {
            Object tag = get.invoke(root, key);
            if (tag == null || ((Number) getId.invoke(tag)).intValue() != 9 || !(tag instanceof List<?> entries)
                    || entries.size() > (ender ? 27 : 41)) throw new IllegalArgumentException("Invalid inventory list");
            boolean[] occupied = new boolean[256];
            int total = 0;
            for (Object item : entries) {
                if (!compound.isInstance(item) || !hasItemId(item)) throw new IllegalArgumentException("Invalid inventory item");
                int slot = number(item, "Slot", 1, getByte);
                boolean storage = slot >= 0 && slot < (ender ? 27 : 36);
                if (!storage && (ender || !(slot == -106 || slot >= 100 && slot <= 103)))
                    throw new IllegalArgumentException("Invalid inventory slot");
                if (occupied[slot & 255]) throw new IllegalArgumentException("Duplicate inventory slot");
                occupied[slot & 255] = true;
                int count = count(item);
                if (storage && shape(item).equals(prototype)) total = Math.addExact(total, count);
            }
            return total;
        }
    }
}
