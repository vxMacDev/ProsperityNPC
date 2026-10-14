package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskCatalogue;
import com.prosperitysmp.npc.training.TrainingRuntime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Optional "Any tips?" topic: one configured tip per player per day plus a read-only status. No money, items or trust. */
final class TipsPresentation {
    static final int MAX_TIPS = 24;
    static final int MAX_LENGTH = 160;
    static final String IDLE = "Nothing on your plate right now.";
    static final String TRAINING_UNCHECKED = "I can't check your training just now.";
    private TipsPresentation() { }

    /** Trimmed, bounded, dash-normalised copy. Blank or non-text entries are dropped, so an empty list hides the topic. */
    static List<String> clean(List<?> raw) {
        var out = new ArrayList<String>();
        if (raw == null) return List.of();
        for (Object entry : raw) {
            if (!(entry instanceof String text)) continue;
            String tip = text.replace('—', '-').replace('–', '-').replaceAll("\\s+", " ").strip();
            if (tip.isEmpty()) continue;
            if (tip.length() > MAX_LENGTH) tip = tip.substring(0, MAX_LENGTH).stripTrailing();
            out.add(tip);
            if (out.size() == MAX_TIPS) break;
        }
        return List.copyOf(out);
    }

    /** Stable per player and economy day; consecutive days step through the list so nobody sees the same tip twice running. */
    static Optional<String> tip(List<String> tips, UUID player, LocalDate day) {
        if (tips == null || tips.isEmpty()) return Optional.empty();
        long mixed = player.getMostSignificantBits() * 31 + player.getLeastSignificantBits();
        mixed ^= mixed >>> 29;
        long size = tips.size();
        return Optional.of(tips.get((int) Math.floorMod(Math.floorMod(mixed, size) + day.toEpochDay(), size)));
    }

    static String status(Optional<TownTask> errand, TrainingRuntime.View training, Instant now) {
        return status(errand, training, now, ResidentCast.builtIn());
    }

    static String status(Optional<TownTask> errand, TrainingRuntime.View training, Instant now, ResidentCast cast) {
        var parts = new ArrayList<String>();
        if (errand != null && errand.isPresent() && errand.get().status() == TownTask.Status.ACTIVE
                && errand.get().expiresAt().isAfter(now))
            parts.add("You are carrying a note for " + TownTaskCatalogue.name(errand.get().definition().recipientId(), cast)
                    + ". Deliver it to them; ask about Business for directions.");
        if (training != null && training.status() == TrainingRuntime.Status.ACTIVE && training.grant().isPresent()
                && training.grant().get().expiresAt().isAfter(now)) {
            long minutes = Math.max(1, (Duration.between(now, training.grant().get().expiresAt()).toSeconds() + 59) / 60);
            parts.add("Your " + training.grant().get().jobName() + " training has " + minutes + " min left.");
        }
        // UNKNOWN, LOADING and UNCERTAIN are not proof of no training, so never claim an idle plate for them.
        else if (training != null && (training.status() == TrainingRuntime.Status.UNKNOWN
                || training.status() == TrainingRuntime.Status.LOADING
                || training.status() == TrainingRuntime.Status.UNCERTAIN))
            parts.add(TRAINING_UNCHECKED);
        return parts.isEmpty() ? IDLE : String.join(" ", parts);
    }

    static EncounterView view(EncounterView base, List<String> tips, UUID player, LocalDate day,
                              Optional<TownTask> errand, TrainingRuntime.View training, Instant now) {
        return view(base, tips, player, day, errand, training, now, ResidentCast.builtIn());
    }

    static EncounterView view(EncounterView base, List<String> tips, UUID player, LocalDate day,
                              Optional<TownTask> errand, TrainingRuntime.View training, Instant now, ResidentCast cast) {
        String status = status(errand, training, now, cast);
        // The status is the part that must survive: the tip is added only when both fit in the speech limit.
        String line = tip(tips, player, day).map(tip -> {
            String combined = "Tip: " + tip + " " + status;
            return ResidentSpeech.wrap(combined).size() <= 6 ? combined : status;
        }).orElse(status);
        return new EncounterView(base.name(), base.occupation(), base.familiarity(), line, base.topics(), List.of());
    }

    /** Adds the topic only when a tip exists; otherwise the view is returned untouched. */
    static EncounterView withTopic(EncounterView view, boolean tipsAvailable) {
        if (!tipsAvailable || view.topics().contains(ResidentDialogue.Topic.TIPS)) return view;
        var topics = new ArrayList<>(view.topics());
        topics.add(ResidentDialogue.Topic.TIPS);
        return new EncounterView(view.name(), view.occupation(), view.familiarity(), view.line(), topics, view.actions());
    }
}
