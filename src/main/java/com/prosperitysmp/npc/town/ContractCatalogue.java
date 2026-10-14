package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * town.contracts.* and town.partnerships.* read on start and /npcadmin reload. Contracts and offers are keyed by
 * resident UUID, so every server writes its own. An invalid entry or a resident outside the active cast is skipped
 * with one warning each. A bad limit holds that whole feature off with a warning. It replaces nothing: errands stay
 * in {@link TownTaskCatalogue}. Nothing here moves money or items.
 */
public record ContractCatalogue(boolean contractsEnabled, boolean partnershipsEnabled, int perPlayerPerDay,
                                boolean requireSurvival, Set<String> handoverWorlds, List<ContractDefinition> definitions,
                                int termDays, int renewWindowDays, int maxPartners, RelationshipMemory.Stage partnerMinimumStage,
                                int minContracts, boolean rankBonusEnabled, Map<UUID, PartnerOffer> offersByResident,
                                int skipped, List<String> warnings) {
    public static final String P = "town.contracts.";
    public static final String Q = "town.partnerships.";
    public static final int MAX_DEFINITIONS = 64;
    public static final int MAX_WORLDS = 64;
    public static final ContractCatalogue OFF = new ContractCatalogue(false, false, 1, true, Set.of(), List.of(), 7, 2, 4,
            RelationshipMemory.Stage.TRUSTED, 2, false, Map.of(), 0, List.of());

    private static final String MARA = ResidentCatalogue.idFor("mara-vale").toString();
    private static final String ELLIS = ResidentCatalogue.idFor("ellis-ward").toString();
    private static final String NICO = ResidentCatalogue.idFor("nico-reed").toString();
    /** The shipped examples, used when the key is absent. Same as config.yml. */
    public static final List<Map<String, Object>> DEFAULT_DEFINITIONS = List.of(
            entry("mara-wall-stone", MARA, null, "Wall stone", "I am rebuilding a garden wall. Bring me cobblestone.",
                    "COBBLESTONE", 64, "STRANGER", 1440),
            entry("mara-beam-logs", MARA, ELLIS, "Beam logs", "Ellis needs oak logs for new shelves. Take them to him.",
                    "OAK_LOG", 16, "ACQUAINTANCE", 720),
            entry("ellis-shelf-stock", ELLIS, null, "Shelf stock", "My shelves are bare. Bring me wheat.",
                    "WHEAT", 32, "ACQUAINTANCE", 1440),
            entry("ellis-hinge-iron", ELLIS, MARA, "Hinge iron", "Mara needs iron for door hinges. Take it to her.",
                    "IRON_INGOT", 8, "TRUSTED", 720),
            entry("nico-coal-run", NICO, MARA, "Coal run", "Mara's forge is low. Take her some coal.",
                    "COAL", 16, "STRANGER", 180),
            entry("nico-route-bread", NICO, null, "Route bread", "I eat on my rounds. Bring me some bread.",
                    "BREAD", 8, "ACQUAINTANCE", 1440));
    public static final List<Map<String, Object>> DEFAULT_OFFERS = List.of(
            offer(ELLIS, "IRON_INGOT", 16, 40, 0, "Keep me stocked with 16 Iron Ingot a week and I cut my fee by 40% for you."),
            offer(MARA, "STONE", 64, 25, 0, "Bring me 64 Stone a week and I cut my fee by 25% for you."),
            offer(NICO, "COAL", 16, 0, 2, "Bring me 16 Coal a week and I tell new arrivals about your homes."));

    public ContractCatalogue {
        handoverWorlds = Set.copyOf(handoverWorlds);
        definitions = List.copyOf(definitions);
        offersByResident = Map.copyOf(offersByResident);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(partnerMinimumStage);
    }

    /** Either feature on, which shows the topic (town.enabled is checked by the caller). */
    public boolean anyOn() { return contractsEnabled || partnershipsEnabled; }

    /** Contracts this resident gives, in config order. */
    public List<ContractDefinition> offers(UUID giver) {
        return definitions.stream().filter(d -> d.giverId().equals(giver)).toList();
    }

    public Optional<ContractDefinition> find(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public Optional<PartnerOffer> partnerOffer(UUID resident) { return Optional.ofNullable(offersByResident.get(resident)); }

    public int skippedCount() { return skipped; }

    /** Empty list means any world a resident renders in. */
    public boolean worldAllowed(String world) { return handoverWorlds.isEmpty() || handoverWorlds.contains(world); }

    /** Current config perks per resident (for min(snapshot, config)). Empty with partnerships off. */
    public PartnerPerks configPerks(UUID resident) {
        if (!partnershipsEnabled) return PartnerPerks.NONE;
        return partnerOffer(resident).map(PartnerOffer::perks).orElse(PartnerPerks.NONE);
    }

    /** Rank points per resident for the housing snapshot. Empty unless partnerships and the rank bonus are on. */
    public Map<UUID, Integer> rankCaps() {
        if (!partnershipsEnabled || !rankBonusEnabled) return Map.of();
        var out = new LinkedHashMap<UUID, Integer>();
        offersByResident.forEach((resident, o) -> {
            if (o.rankBonusPoints() > 0) out.put(resident, o.rankBonusPoints());
        });
        return Map.copyOf(out);
    }

    public TownContractRepository.PartnerTerms terms(PartnerOffer offer) {
        return new TownContractRepository.PartnerTerms(offer.perks(), termDays, maxPartners, renewWindowDays,
                partnerMinimumStage, minContracts, offer.material(), offer.quantity());
    }

    /** get reads one config path (null when absent: the shipped default). itemMaterial says a name is a current item. */
    public static ContractCatalogue load(Function<String, Object> get, ResidentCast cast, Predicate<String> itemMaterial) {
        Objects.requireNonNull(get);
        Objects.requireNonNull(cast);
        Objects.requireNonNull(itemMaterial);
        var warnings = new ArrayList<String>();
        boolean contracts = flag(get.apply(P + "enabled"), P + "enabled", warnings);
        boolean partnerships = flag(get.apply(Q + "enabled"), Q + "enabled", warnings);
        boolean contractsHeld = false, partnersHeld = false;
        int perDay = integer(get.apply(P + "per-player-per-day"), 1);
        if (perDay < 1 || perDay > 3) {
            warnings.add(P + "per-player-per-day must be 1 to 3; contracts are held off.");
            contractsHeld = true;
            perDay = 1;
        }
        boolean survival = safety(get.apply(P + "require-survival"), P + "require-survival", warnings);
        Set<String> worlds = new HashSet<>();
        Object rawWorlds = get.apply(P + "handover-worlds");
        if (rawWorlds != null) {
            if (!(rawWorlds instanceof List<?> list) || list.size() > MAX_WORLDS
                    || list.stream().anyMatch(w -> !(w instanceof String s) || !s.matches("[A-Za-z0-9_./-]{1,64}"))) {
                warnings.add(P + "handover-worlds must be a list of world names; contracts and partnerships are held off.");
                contractsHeld = true;
                partnersHeld = true;
            } else for (Object w : list) worlds.add((String) w);
        }
        int term = integer(get.apply(Q + "term-days"), 7);
        if (term < 1 || term > 30) {
            warnings.add(Q + "term-days must be 1 to 30; partnerships are held off.");
            partnersHeld = true;
            term = 7;
        }
        int window = integer(get.apply(Q + "renew-window-days"), 2);
        if (window < 1 || window > term) {
            warnings.add(Q + "renew-window-days must be 1 to term-days; partnerships are held off.");
            partnersHeld = true;
            window = 1;
        }
        int seats = integer(get.apply(Q + "max-partners-per-resident"), 4);
        if (seats < 1 || seats > 16) {
            warnings.add(Q + "max-partners-per-resident must be 1 to 16; partnerships are held off.");
            partnersHeld = true;
            seats = 4;
        }
        RelationshipMemory.Stage stage = RelationshipMemory.Stage.TRUSTED;
        Object rawStage = get.apply(Q + "minimum-stage");
        if (rawStage != null) {
            try {
                stage = RelationshipMemory.Stage.valueOf(String.valueOf(rawStage).toUpperCase(Locale.ROOT));
                if (stage == RelationshipMemory.Stage.STRANGER) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                warnings.add(Q + "minimum-stage must be ACQUAINTANCE, TRUSTED or ESTABLISHED; partnerships are held off.");
                partnersHeld = true;
                stage = RelationshipMemory.Stage.TRUSTED;
            }
        }
        int minContracts = integer(get.apply(Q + "min-contracts"), 2);
        if (minContracts < 0 || minContracts > 10) {
            warnings.add(Q + "min-contracts must be 0 to 10; partnerships are held off.");
            partnersHeld = true;
            minContracts = 2;
        }
        boolean rank = flag(get.apply(Q + "rank-bonus-enabled"), Q + "rank-bonus-enabled", warnings);
        int[] skipped = {0};
        var definitions = definitions(get.apply(P + "definitions"), cast, itemMaterial, warnings, skipped);
        var offers = offers(get.apply(Q + "offers"), cast, itemMaterial, warnings, skipped);
        return new ContractCatalogue(contracts && !contractsHeld, partnerships && !partnersHeld, perDay, survival, worlds,
                definitions, term, window, seats, stage, minContracts, rank, offers, skipped[0], warnings);
    }

    private static List<ContractDefinition> definitions(Object raw, ResidentCast cast, Predicate<String> item,
                                                        List<String> warnings, int[] skipped) {
        List<?> list = raw == null ? DEFAULT_DEFINITIONS : raw instanceof List<?> l ? l : null;
        if (list == null) {
            warnings.add(P + "definitions is not a list; no contracts are loaded.");
            return List.of();
        }
        var out = new ArrayList<ContractDefinition>();
        var ids = new HashSet<String>();
        int index = 0;
        for (Object entry : list) {
            index++;
            if (out.size() >= MAX_DEFINITIONS) {
                warnings.add(P + "definitions: only the first " + MAX_DEFINITIONS + " contracts are loaded.");
                skipped[0] += list.size() - index + 1;
                break;
            }
            try {
                ContractDefinition definition = definition(entry, cast, item);
                if (!ids.add(definition.id())) throw new IllegalArgumentException("duplicate id " + definition.id());
                out.add(definition);
            } catch (IllegalArgumentException | ClassCastException invalid) {
                skipped[0]++;
                warnings.add(P + "definitions entry " + index + " skipped: " + reason(invalid));
            }
        }
        return List.copyOf(out);
    }

    private static ContractDefinition definition(Object entry, ResidentCast cast, Predicate<String> item) {
        if (!(entry instanceof Map<?, ?> map)) throw new IllegalArgumentException("not a map");
        UUID giver = resident(map.get("giver"), cast, "giver");
        UUID recipient = map.get("recipient") == null ? giver : resident(map.get("recipient"), cast, "recipient");
        String material = material(map.get("material"), item);
        int minutes = whole(map.get("duration-minutes"), "duration-minutes");
        if (minutes < ContractDefinition.MIN_AUTHORED_MINUTES || minutes > ContractDefinition.MAX_DURATION)
            throw new IllegalArgumentException("duration-minutes must be 30 to 1440");
        RelationshipMemory.Stage stage;
        try { stage = RelationshipMemory.Stage.valueOf(String.valueOf(map.get("minimum-stage")).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("unknown minimum-stage"); }
        return new ContractDefinition(text(map.get("id"), "id"),
                giver.equals(recipient) ? ContractDefinition.Kind.SUPPLY : ContractDefinition.Kind.DELIVER,
                text(map.get("title"), "title"), text(map.get("description"), "description"), giver, recipient, material,
                whole(map.get("quantity"), "quantity"), stage, minutes);
    }

    private static Map<UUID, PartnerOffer> offers(Object raw, ResidentCast cast, Predicate<String> item,
                                                  List<String> warnings, int[] skipped) {
        List<?> list = raw == null ? DEFAULT_OFFERS : raw instanceof List<?> l ? l : null;
        if (list == null) {
            warnings.add(Q + "offers is not a list; no partnership offers are loaded.");
            return Map.of();
        }
        var out = new LinkedHashMap<UUID, PartnerOffer>();
        int index = 0;
        for (Object entry : list) {
            index++;
            try {
                if (!(entry instanceof Map<?, ?> map)) throw new IllegalArgumentException("not a map");
                UUID resident = resident(map.get("resident"), cast, "resident");
                if (out.containsKey(resident)) throw new IllegalArgumentException("a second offer for one resident");
                out.put(resident, new PartnerOffer(resident, material(map.get("material"), item), whole(map.get("quantity"), "quantity"),
                        whole(map.get("fee-discount-percent"), "fee-discount-percent"),
                        whole(map.get("rank-bonus-points"), "rank-bonus-points"),
                        text(map.get("pitch"), "pitch")));
            } catch (IllegalArgumentException | ClassCastException invalid) {
                skipped[0]++;
                warnings.add(Q + "offers entry " + index + " skipped: " + reason(invalid));
            }
        }
        return Map.copyOf(out);
    }

    private static UUID resident(Object raw, ResidentCast cast, String field) {
        if (!(raw instanceof String s) || !s.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException(field + " is not a resident UUID");
        UUID id = UUID.fromString(s);
        if (!cast.contains(id)) throw new IllegalArgumentException(field + " is not a resident on this server");
        return id;
    }

    private static String material(Object raw, Predicate<String> item) {
        if (!(raw instanceof String name) || !name.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("material is not an item name");
        boolean ok;
        try {
            ok = item.test(name);
        } catch (RuntimeException unknown) {
            ok = false;
        }
        if (!ok) throw new IllegalArgumentException("material " + name + " is not an item");
        return name;
    }

    private static String text(Object raw, String field) {
        if (!(raw instanceof String s)) throw new IllegalArgumentException(field + " is missing");
        return s;
    }

    private static int whole(Object raw, String field) {
        if (!(raw instanceof Integer value)) throw new IllegalArgumentException(field + " is not a whole number");
        return value;
    }

    private static String reason(RuntimeException failure) {
        String m = failure.getMessage();
        return m == null || m.isBlank() ? "invalid entry" : m.replaceAll("[\\r\\n]+", " ");
    }

    private static int integer(Object raw, int fallback) {
        if (raw == null) return fallback;
        return raw instanceof Integer value ? value : Integer.MIN_VALUE;
    }

    /** Feature flags: only true turns on. Anything else is off, with a warning when it is not false. */
    private static boolean flag(Object raw, String key, List<String> warnings) {
        if (raw != null && !(raw instanceof Boolean)) warnings.add(key + " is not true or false; it stays off.");
        return Boolean.TRUE.equals(raw);
    }

    /** Safety checks fail toward on: anything but false keeps the check. */
    private static boolean safety(Object raw, String key, List<String> warnings) {
        if (raw == null || Boolean.TRUE.equals(raw)) return true;
        if (Boolean.FALSE.equals(raw)) return false;
        warnings.add(key + " is not true or false; the check stays on.");
        return true;
    }

    private static Map<String, Object> entry(String id, String giver, String recipient, String title, String description,
                                             String material, int quantity, String stage, int minutes) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("giver", giver);
        if (recipient != null) map.put("recipient", recipient);
        map.put("title", title);
        map.put("description", description);
        map.put("material", material);
        map.put("quantity", quantity);
        map.put("minimum-stage", stage);
        map.put("duration-minutes", minutes);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> offer(String resident, String material, int quantity, int fee, int rank, String pitch) {
        var map = new LinkedHashMap<String, Object>();
        map.put("resident", resident);
        map.put("material", material);
        map.put("quantity", quantity);
        map.put("fee-discount-percent", fee);
        map.put("rank-bonus-points", rank);
        map.put("pitch", pitch);
        return Collections.unmodifiableMap(map);
    }
}
