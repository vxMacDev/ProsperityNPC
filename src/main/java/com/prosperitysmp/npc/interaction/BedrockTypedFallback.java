package com.prosperitysmp.npc.interaction;

import java.util.ArrayList;
import java.util.List;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import java.util.Locale;

/** Typed-command lines for Bedrock players whose chat fallback cannot use clickable text. */
final class BedrockTypedFallback {
    private BedrockTypedFallback() { }

    static List<String> lines(List<ResidentAction> actions, List<ResidentDialogue.Topic> topics) {
        var lines = new ArrayList<String>();
        for (int i = 0; i < actions.size(); i++)
            lines.add("Type /resident act " + (i + 1) + " for: " + actions.get(i).label());
        if (!topics.isEmpty()) {
            var names = new ArrayList<String>();
            for (var topic : topics) names.add(topic.name().toLowerCase(Locale.ROOT));
            lines.add("Type /resident choose <topic>: " + String.join(", ", names));
        }
        lines.add("Type /resident leave to end the chat.");
        return List.copyOf(lines);
    }

    /** Typed action numbers start at 1; returns the zero-based index or -1. */
    static int index(String typed) {
        try {
            int n = Integer.parseInt(typed.trim());
            return n >= 1 && n <= 99 ? n - 1 : -1;
        }
        catch (NumberFormatException bad) { return -1; }
    }
}
