package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.training.TrainingRepository;
import com.prosperitysmp.npc.training.TrainingRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Authored mentors offer only their actual trade and the player's verified Jobs membership. */
final class TrainingPresentation {
    private TrainingPresentation() { }

    static List<String> trades(ResidentProfile.Occupation occupation) {
        return switch (occupation) {
            case RESTORER, CARPENTER -> List.of("builder", "woodcutter");
            case GARDENER -> List.of("farmer", "woodcutter");
            case COOK -> List.of("farmer", "fisherman");
            case SHOPKEEPER, COURIER -> List.of();
        };
    }

    static EncounterView add(EncounterView view, ResidentProfile profile, Set<String> employed,
                             TrainingRuntime.View status) {
        return add(view, profile, employed, status, false);
    }

    /** reserveTrain: when errands already fill every action slot, the last one yields so Train stays reachable. */
    static EncounterView add(EncounterView view, ResidentProfile profile, Set<String> employed,
                             TrainingRuntime.View status, boolean reserveTrain) {
        if (trades(profile.occupation()).isEmpty() || status == null
                || Set.of(TrainingRuntime.Status.DISABLED, TrainingRuntime.Status.UNAVAILABLE,
                          TrainingRuntime.Status.UNCERTAIN, TrainingRuntime.Status.INELIGIBLE).contains(status.status())) return view;
        if (status.status() == TrainingRuntime.Status.UNKNOWN || status.status() == TrainingRuntime.Status.LOADING)
            return new EncounterView(view.name(), view.occupation(), view.familiarity(),
                    TownOfferPresentation.replyLine("Checking your saved training. Reopen Business in a moment.", view.line()),
                    view.topics(), view.actions());
        if (status.status() == TrainingRuntime.Status.ACTIVE) {
            var grant = status.grant().orElseThrow();
            return new EncounterView(view.name(), view.occupation(), view.familiarity(),
                    TownOfferPresentation.replyLine("Your " + grant.jobName() + " training lasts until "
                            + grant.expiresAt().toString().substring(11, 16) + " UTC.", view.line()), view.topics(), view.actions());
        }
        var actions = new ArrayList<>(view.actions());
        if (reserveTrain && actions.size() >= 3 && actions.stream().noneMatch(a -> a.kind() == ResidentAction.Kind.TRAIN)
                && trades(profile.occupation()).stream().anyMatch(employed::contains)) {
            for (int i = actions.size() - 1; i >= 0 && actions.size() >= 3; i--)
                if (actions.get(i).kind() != ResidentAction.Kind.TRAIN) actions.remove(i);
        }
        for (String job : trades(profile.occupation())) {
            if (actions.size() == 3) break;
            if (employed.contains(job)) actions.add(new ResidentAction(ResidentAction.Kind.TRAIN, job, null,
                    profile.id(), "Train " + job + ": +5% pay, +10% XP / 30m"));
        }
        return new EncounterView(view.name(), view.occupation(), view.familiarity(), view.line(), view.topics(), actions);
    }

    static String result(TrainingRepository.Result result) {
        return switch (result.code()) {
            case GRANTED -> "Practice agreed: +5% Jobs pay and +10% XP for 30 minutes. One session per service day.";
            case ALREADY_ACTIVE -> "Your existing practice session is still active. Its original deadline is unchanged.";
            case ACTIVE_CONFLICT -> "You already have another practice session running.";
            case DAILY_LIMIT -> "You've used today's practice session. Return after the 4 AM service reset.";
            case RELATIONSHIP_TOO_LOW -> "Introduce yourself first. Practice needs an Acquaintance relationship.";
            case NOT_EMPLOYED -> "You need to be employed in that job before we can practice.";
            case DISABLED, UNAVAILABLE -> "Training is unavailable right now. No new benefit is active.";
            case UNCERTAIN -> "Training confirmation is unresolved. Benefits are held; ask again to check stored state.";
            case UNAUTHORIZED, UNKNOWN_TRAINER, TIME_REGRESSION, BUSY -> "I cannot confirm that practice session right now.";
        };
    }
}
