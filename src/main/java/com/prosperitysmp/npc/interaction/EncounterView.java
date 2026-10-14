package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation contains only this caller's conversation, not private financial or other-player data. */
public record EncounterView(String name, String occupation, String familiarity, String line,
                            List<ResidentDialogue.Topic> topics, List<ResidentAction> actions, String moodSuffix) {
    public EncounterView {
        topics = List.copyOf(topics);
        actions = List.copyOf(actions);
        if (actions.size() > 3) throw new IllegalArgumentException("Too many encounter actions");
    }
    public EncounterView(String name, String occupation, String familiarity, String line,
                         List<ResidentDialogue.Topic> topics, List<ResidentAction> actions) {
        this(name, occupation, familiarity, line, topics, actions, null);
    }
    public EncounterView(String name, String occupation, String familiarity, String line, List<ResidentDialogue.Topic> topics) {
        this(name, occupation, familiarity, line, topics, List.of());
    }

    /** Use a main-thread observation at delivery, never a movement claim captured before SQL. */
    public EncounterView withActivity(ResidentDialogue.Topic topic, ResidentActivity actual) {
        return topic == ResidentDialogue.Topic.ROUTINE && actual != null
                ? new EncounterView(name,occupation,familiarity,
                        moodSuffix == null ? actual.line() : actual.line() + " " + moodSuffix,topics,actions,moodSuffix) : this;
    }

    /** Appends a short mood sentence that survives the delivery-time activity override. */
    public EncounterView withMood(String sentence) {
        return sentence == null || sentence.isBlank() ? this
                : new EncounterView(name,occupation,familiarity,line + " " + sentence,topics,actions,sentence);
    }

    /** Appends one sentence to the line. Apply after withActivity and withMood so neither can drop it. */
    public EncounterView withNotice(String sentence) {
        return sentence == null || sentence.isBlank() ? this
                : new EncounterView(name,occupation,familiarity,line + " " + sentence,topics,actions,moodSuffix);
    }

    public static EncounterView of(ResidentProfile profile, RelationshipMemory.State state,
                                   ResidentDialogue.Reply reply) {
        return new EncounterView(profile.name(), label(profile.occupation().name()),
                label(state.stage().name()), reply.line(), reply.choices());
    }

    public static String topicLabel(ResidentDialogue.Topic topic) {
        return switch (topic) {
            case GREETING -> "Say hello";
            case ROUTINE -> "How is your day?";
            case HOME -> "What makes a good home?";
            case BUSINESS -> "Business and partnerships";
            case COMMERCE -> "Supplies and hired work";
            case HISTORY -> "Our history";
            case ABOUT -> "Tell me about yourself";
            case TIPS -> "Any tips?";
            case CONTRACTS -> "Contracts and partners";
        };
    }

    /**
     * A partnership confirmation: no topics, and the first action confirms. Bedrock shows it as a form with only its
     * own buttons (Confirm, Back), Java as clickable chat.
     */
    public boolean confirmation() {
        return topics.isEmpty() && !actions.isEmpty() && (actions.getFirst().kind() == ResidentAction.Kind.PARTNER_CONFIRM
                || actions.getFirst().kind() == ResidentAction.Kind.PARTNER_END_CONFIRM);
    }

    public static String label(String key) {
        String s = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static List<String> wrap(String text, int width) {
        if (width < 8 || width > 80) throw new IllegalArgumentException("Unsupported lore width");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (word.length() > width) {
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!word.isEmpty()) {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return List.copyOf(lines);
    }
}
