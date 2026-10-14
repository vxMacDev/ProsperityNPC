package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Source-wiring regression only; not a substitute for Citizens runtime or client tests. */
class ConversationPauseWiringTest {
    private static final String ROOT = "src/main/java/com/prosperitysmp/npc/";

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(ROOT + path));
    }

    private static int count(String code, String needle) {
        return code.split(Pattern.quote(needle), -1).length - 1;
    }

    /** The body of the first method whose declaration starts with {@code signature}, by brace matching. */
    private static String method(String code, String signature) {
        int start = code.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = code.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(start, i + 1);
        }
        throw new AssertionError("Unbalanced " + signature);
    }

    @Test void everyRenderGetsThePolicyAndThePauseIsBuiltOnlyInItsConstructor() throws Exception {
        String code = source("render/RenderManager.java");
        assertEquals(0, count(code, "new ConversationPause()"));
        assertEquals(1, count(code, "new ConversationPause("));
        assertEquals(3, count(code, "new Render(pausePolicy)"));
        assertEquals(3, count(code, "new Render("));
        assertTrue(code.contains("final ConversationPause conversationPause;"));
        assertEquals(1, count(code, "conversationPause ="), "the pause is assigned only in the Render constructor");
        String constructor = method(code, "Render(ConversationPause.Policy policy)");
        assertTrue(constructor.contains("conversationPause = new ConversationPause(policy);"));
        assertTrue(code.contains("r.conversationPause.acquire(visitor.getUniqueId(), now, !r.walkingHome)"),
                "a premium relocation walk never renews");
        assertTrue(method(code, "public boolean pauseEndingSoon(UUID residentId)")
                .contains("r.pausedWalk && now < r.pausedUntilMs && r.conversationPause.capReached(now, !r.walkingHome)"));
    }

    @Test void theFlagIsReadOnceInRenderManagerOnly() throws Exception {
        String needle = "getBoolean(\"ux.conversation-sliding-deadline\"";
        try (var files = Files.walk(Path.of(ROOT))) {
            var readers = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try { return Files.readString(path).contains(needle); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).map(path -> path.getFileName().toString()).toList();
            assertEquals(java.util.List.of("RenderManager.java"), readers);
        }
        assertEquals(1, count(source("render/RenderManager.java"), needle));
        String controller = source("interaction/ResidentEncounterController.java");
        assertTrue(controller.contains("longerConversations = renderer.longerConversations();"));
        assertTrue(controller.contains("new EncounterSessions(longerConversations)"));
        assertTrue(controller.indexOf("longerConversations = renderer.longerConversations();")
                < controller.indexOf("new EncounterSessions(longerConversations)"));
    }

    /** A misspelled key or a default that differs from config.yml would silently ignore what the owner set. */
    @Test void pauseKeysAndDefaultsMatchTheShippedConfig() throws Exception {
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        String manager = source("render/RenderManager.java");
        int read = manager.indexOf("this.pausePolicy = ConversationPause.Policy.of(");
        assertTrue(read >= 0);
        String policy = manager.substring(read, manager.indexOf("));", read) + 3);
        var flag = Pattern.compile("getBoolean\\(\"(ux\\.conversation-sliding-deadline)\", (true|false)\\)").matcher(policy);
        assertTrue(flag.find(), "the flag is read with an explicit default");
        assertTrue(config.isBoolean(flag.group(1)), flag.group(1));
        assertEquals(config.getBoolean(flag.group(1)), Boolean.parseBoolean(flag.group(2)), "flag default");
        var ints = Pattern.compile("getInt\\(\"([a-z.-]+)\", (\\d+)\\)").matcher(policy);
        var keys = new java.util.ArrayList<String>();
        while (ints.find()) {
            keys.add(ints.group(1));
            assertTrue(config.isInt(ints.group(1)), ints.group(1) + " is in config.yml");
            assertEquals(config.getInt(ints.group(1)), Integer.parseInt(ints.group(2)), ints.group(1) + " default");
        }
        assertEquals(java.util.List.of("ux.conversation-pause-cap-seconds", "ux.conversation-pause-cooldown-seconds"), keys);
    }

    @Test void aHomeWalkThatStartsMidStopKeepsOnlyItsFirst90Seconds() throws Exception {
        String adopt = method(source("render/RenderManager.java"), "private boolean adoptPremiumWalk(");
        int walking = adopt.indexOf("r.walkingHome = true;");
        int gate = adopt.indexOf("if (pausePolicy.renews() && r.pausedUntilMs > now)");
        int clamp = adopt.indexOf("r.pausedUntilMs = Math.min(r.pausedUntilMs, r.conversationPause.stopRenewing());");
        assertTrue(walking >= 0 && gate > walking && clamp > gate
                && adopt.substring(gate, clamp).strip().equals("if (pausePolicy.renews() && r.pausedUntilMs > now)"),
                "the clamp follows walkingHome and is gated by the flag");
        assertEquals(1, count(source("render/RenderManager.java"), ".stopRenewing()"));
    }

    @Test void renewalsAreGatedAndFollowAdmissionAndAuthorization() throws Exception {
        String code = source("interaction/ResidentEncounterController.java");
        assertEquals(3, count(code, "renderer.acknowledge("));
        assertEquals(2, count(code, "if (longerConversations) renderer.acknowledge("));
        assertTrue(method(code, "public void begin(Player player, UUID resident)").contains("renderer.acknowledge(resident, player);"));

        String choose = method(code, "private void choose(Player player, UUID token, ResidentDialogue.Topic topic, boolean mayDefer)");
        int admit = choose.indexOf("sessions.admit(");
        int empty = choose.indexOf("if (admitted.isEmpty())");
        int renew = choose.indexOf("if (longerConversations) renderer.acknowledge(admitted.get().residentId(), player);");
        int request = choose.indexOf("request(admitted.get(), topic, false);");
        assertTrue(admit >= 0 && empty > admit && renew > empty && request > renew, "choose renews only after admission");
        assertTrue(renew > choose.indexOf("note(player, \"Please wait a moment before choosing another topic.\"); return;"),
                "a click that was not admitted never renews");

        String act = method(code, "private void act(Player player, UUID token, int index, boolean mayDefer)");
        int actAdmit = act.indexOf("sessions.admit(");
        int actor = act.indexOf("if (!townActor(ticket)) { viewClosed(player.getUniqueId(), token); return; }");
        int actRenew = act.indexOf("if (longerConversations) renderer.acknowledge(ticket.residentId(), player);");
        int firstKind = act.indexOf("ResidentAction.Kind.ORDER || selected.kind() == ResidentAction.Kind.COLLECT");
        assertTrue(actAdmit >= 0 && actor > actAdmit && actRenew > actor && firstKind > actRenew,
                "act renews only after the townActor check and before every action kind");

        String show = method(code, "private void show(Player player, EncounterSessions.Ticket ticket, EncounterView shown)");
        int valid = show.indexOf("if (!valid(ticket)) return;");
        int notice = show.indexOf("if (longerConversations && renderer.pauseEndingSoon(ticket.residentId())) shown = shown.withNotice(PAUSE_ENDING_LINE);");
        int tips = show.indexOf("TipsPresentation.withTopic(shown");
        assertTrue(valid >= 0 && notice > valid && tips > notice);
        assertEquals(1, count(code, "renderer.pauseEndingSoon("));
        assertTrue(code.contains("static final String PAUSE_ENDING_LINE = \"I can only stay a little longer.\";"));
    }

    @Test void bedrockFormsNeverTalkToTheRendererDirectly() throws Exception {
        String bedrock = source("interaction/BedrockNpcUi.java");
        assertFalse(bedrock.contains("renderer"));
        assertFalse(bedrock.contains("RenderManager"));
        assertFalse(bedrock.contains(".acknowledge("));
    }
}
