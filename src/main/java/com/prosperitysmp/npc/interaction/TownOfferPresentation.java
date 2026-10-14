package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskCatalogue;
import com.prosperitysmp.npc.town.TownTaskRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure view of actual obligations. No claims of wages, goods or available financial contracts. */
final class TownOfferPresentation {
    private TownOfferPresentation() { }

    static EncounterView view(EncounterView base, ResidentProfile person, RelationshipMemory.State relationship,
                              Optional<TownTask> active, List<TownTask> history, LocalDate today, Set<UUID> reachable) {
        return view(base, person, relationship, active, history, today, reachable, ResidentCast.builtIn());
    }

    /** Names and offers come from the active cast; a task with a giver or recipient outside it is not offered. */
    static EncounterView view(EncounterView base, ResidentProfile person, RelationshipMemory.State relationship,
                              Optional<TownTask> active, List<TownTask> history, LocalDate today, Set<UUID> reachable,
                              ResidentCast cast) {
        var actions = new ArrayList<ResidentAction>();
        String line;
        if (active.isPresent()) {
            TownTask task = active.orElseThrow();
            String recipient = TownTaskCatalogue.name(task.definition().recipientId(), cast);
            if (person.id().equals(task.definition().recipientId())) {
                line = "You have a sealed note for me: " + task.definition().title() + ". Ready to hand it over?";
                actions.add(action(ResidentAction.Kind.COMPLETE, task, "Deliver sealed note"));
            } else {
                line = "Your current errand is " + task.definition().title() + ". Deliver it to " + recipient + ".";
            }
            actions.add(action(ResidentAction.Kind.DIRECTIONS, task, "Find " + recipient));
            actions.add(action(ResidentAction.Kind.CANCEL, task, "Cancel errand (today's slot stays used)"));
            line += " Until " + task.expiresAt().toString().substring(11, 16) + " UTC.";
        } else if (history.stream().anyMatch(task -> !task.acceptedEconomyDay().isBefore(today))) {
            line = "You've taken an errand this service day. Next slot: 4 AM reset. Cancelled or expired notes cost no trust.";
        } else {
            var offers = TownTaskCatalogue.offers(person.id(), cast);
            var available = offers.stream().filter(task -> relationship.stage().ordinal() >= task.minimumStage().ordinal()
                    && reachable.contains(task.recipientId())).toList();
            for (var task : available) actions.add(new ResidentAction(ResidentAction.Kind.ACCEPT, task.id(), null,
                    task.recipientId(), "Take: " + task.title()));
            line = available.isEmpty() ? "I have no reachable errand to offer right now."
                    : available.getFirst().description() + " One virtual note per service day; 3h to deliver.";
            if (offers.stream().anyMatch(task -> relationship.stage().ordinal() < task.minimumStage().ordinal()))
                line += " Trusted friends unlock specialist introductions.";
            line += " Builds familiarity, not cash or items.";
        }
        return new EncounterView(base.name(), base.occupation(), base.familiarity(), line, base.topics(), actions);
    }

    private static ResidentAction action(ResidentAction.Kind kind, TownTask task, String label) {
        return new ResidentAction(kind, null, task.taskId(), task.definition().recipientId(), label);
    }

    static String replyLine(String result, String context) {
        String combined = result + " " + context;
        return ResidentSpeech.wrap(combined).size() <= 6 ? combined : result;
    }

    static String result(TownTaskRepository.Code code) {
        return switch (code) {
            case ACCEPTED -> "The sealed note is in your errand journal.";
            case ALREADY_ACTIVE -> "You already carry this note. Your original deadline is unchanged.";
            case COMPLETED -> "Delivered. We both remember your follow-through.";
            case ALREADY_COMPLETED -> "That note was already delivered; it has not earned another memory.";
            case CANCELLED, ALREADY_CANCELLED -> "The errand is cancelled. No trust is lost, but today's slot remains used.";
            case EXPIRED -> "The note has expired. No trust is lost; ask for a new errand on another service day.";
            case DAILY_LIMIT -> "You've already taken an errand this service day. Come back after the next 4 AM reset.";
            case ACTIVE_CONFLICT -> "Finish or cancel your current errand before taking another.";
            case RELATIONSHIP_TOO_LOW -> "That introduction needs a Trusted relationship first.";
            case WRONG_RECIPIENT -> "That note is addressed to someone else.";
            case UNKNOWN_GIVER, UNKNOWN_RECIPIENT, TIME_REGRESSION, NOT_ACTIVE, NOT_FOUND -> "This errand is unavailable. Nothing new was granted.";
        };
    }
}
