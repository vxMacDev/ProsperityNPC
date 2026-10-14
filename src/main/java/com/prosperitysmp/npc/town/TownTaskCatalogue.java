package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Original authored obligations. Their notes are virtual, never inventory currency. Givers and recipients are fixed
 * resident keys, so the tasks keep their UUIDs whatever the cast is. A task whose giver or recipient is not in the
 * active cast is hidden.
 */
public final class TownTaskCatalogue {
    private TownTaskCatalogue() { }
    private static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    private static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    private static final UUID NICO = ResidentCatalogue.idFor("nico-reed");
    private static final ResidentCast BUILT_IN = ResidentCast.builtIn();
    private static final List<TownTaskDefinition> TASKS = List.of(
            note("mara-material-list", "Restoration materials", "Take my sealed materials list to Ellis Ward.", MARA, ELLIS, Stage.STRANGER),
            note("ellis-neighborhood-note", "A neighborhood introduction", "Take my introduction note to Nico Reed.", ELLIS, NICO, Stage.STRANGER),
            note("nico-home-note", "Settling into the neighborhood", "Take my household note to Mara Vale.", NICO, MARA, Stage.STRANGER),
            note("mara-restoration-plan", "A trusted restoration contact", "I trust your follow-through. Take this restoration plan to Nico Reed.", MARA, NICO, Stage.TRUSTED),
            note("ellis-supplier-introduction", "A trusted supplier introduction", "Take my sealed supplier introduction to Mara Vale. We will remember who delivered it.", ELLIS, MARA, Stage.TRUSTED),
            note("nico-local-partnership", "A trusted local contact", "Take my local partnership note to Ellis Ward.", NICO, ELLIS, Stage.TRUSTED));

    /** The built-in cast, as before. */
    public static List<TownTaskDefinition> offers(UUID giver) { return offers(giver, BUILT_IN); }
    public static Optional<TownTaskDefinition> find(String id) { return find(id, BUILT_IN); }
    public static String name(UUID id) { return name(id, BUILT_IN); }

    public static List<TownTaskDefinition> offers(UUID giver, ResidentCast cast) {
        return TASKS.stream().filter(task -> task.giverId().equals(giver) && inCast(task, cast)).toList();
    }

    public static Optional<TownTaskDefinition> find(String id, ResidentCast cast) {
        return TASKS.stream().filter(task -> task.id().equals(id) && inCast(task, cast)).findFirst();
    }

    public static String name(UUID id, ResidentCast cast) {
        return cast.byId(id).map(person -> person.name()).orElse("the named resident");
    }

    private static boolean inCast(TownTaskDefinition task, ResidentCast cast) {
        return cast.contains(task.giverId()) && cast.contains(task.recipientId());
    }

    private static TownTaskDefinition note(String id, String title, String description, UUID from, UUID to, Stage stage) {
        return new TownTaskDefinition(id, title, description, from, to, stage, 180);
    }
}
