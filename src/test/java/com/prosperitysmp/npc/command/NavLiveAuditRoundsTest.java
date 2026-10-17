package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavPalette;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.NavSweep;
import com.prosperitysmp.npc.nav.NavTerrainCapture;
import com.prosperitysmp.npc.nav.StreetRules;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A live audit reads the world twice. The second read has to use the very palette the first one read with:
 * looking it up again by name could pick up a config reload in between and mix two palettes in one verdict.
 * The command's job records are private, so this drives them by reflection.
 */
class NavLiveAuditRoundsTest {

    private static NavPalette palette(String name) throws Exception {
        NavPalette.Parsed parsed = NavPalette.parse(null, "default", text -> text.toUpperCase(java.util.Locale.ROOT), n -> true);
        Constructor<NavPalette> constructor = NavPalette.class.getDeclaredConstructor(NavPalette.Parsed.class, Map.class);
        constructor.setAccessible(true);
        NavPalette.Parsed named = new NavPalette.Parsed(name, parsed.materials(), parsed.rules(), parsed.legacy(),
                parsed.hash(), null);
        return constructor.newInstance(named, new EnumMap<Material, NavSurface>(Material.class));
    }

    /** Sidewalk, three road cells, sidewalk, in a line along z. */
    private static NavGraph crossing() {
        NavGraph.Builder b = NavGraph.builder();
        b.addNode(new NavGraph.Node(1, 0, 64, 0, Set.of("home"), NavSurface.SIDEWALK));
        for (int i = 0; i < 3; i++) {
            b.addNode(new NavGraph.Node(2 + i, 0, 64, 1 + i, Set.of(), NavSurface.ROAD, 3));
        }
        b.addNode(new NavGraph.Node(5, 0, 64, 4, Set.of("work"), NavSurface.SIDEWALK));
        for (int i = 1; i <= 4; i++) {
            b.addBiEdge(i, i + 1, 10);
        }
        return b.build();
    }

    private static Object build(Class<?> type, Object... args) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    @Test
    void theSecondRoundOfALiveAuditUsesThePaletteTheFirstRoundReadWith() throws Exception {
        NavGraph graph = crossing();
        NavSweep sweep = NavSweep.forClasses(graph);
        NavSweep.Reader reader = (x, y, z) -> NavTerrainCapture.CLEAR;
        while (!sweep.step(reader, 512, Long.MAX_VALUE, () -> 0L)) {
            // read the first round
        }
        NavPalette firstRound = palette("city");
        Class<?> outcomeType = Class.forName("com.prosperitysmp.npc.command.NpcNavCommand$SweepOutcome");
        Class<?> settingsType = Class.forName("com.prosperitysmp.npc.command.NpcNavCommand$Settings");
        // sweep, hash, palette name, world, no error, world present, chunk counts, tickets, palette
        Object outcome = build(outcomeType, sweep, "hash-1", "city", "world", null, false, 0, 0, 0, 0, firstRound);
        // rules, no rules error, limits and counts, no report sink (this path writes no report)
        Object settings = build(settingsType, new StreetRules(12, 16, 0.4), null, 1L, 1, 1, false, 1, 1, 1, 1, 0, 0, null);
        var info = new NavGraphRepository.GraphInfo(UUID.randomUUID(), UUID.randomUUID(), "region", "old-list-palette",
                1, "ACTIVE", graph.nodeCount(), graph.edgeCount(), null);

        Method liveAudit = NpcNavCommand.class.getDeclaredMethod("liveAudit", NavGraphRepository.GraphInfo.class,
                NavGraph.class, settingsType, boolean.class, outcomeType);
        liveAudit.setAccessible(true);
        Object reply = liveAudit.invoke(null, info, graph, settings, false, outcome);

        Object next = invoke(reply, "next");
        assertNotNull(next, "the width probes are read by a second round");
        assertSame(firstRound, invoke(next, "palette"), "the second round carries the first round's palette itself");
        assertNull(invoke(next, "requestedPalette"), "it is not asked for by name again");
        assertEquals("old-list-palette", invoke(next, "paletteName"));
        assertTrue((Boolean) invoke(next, "live"));
    }

    @Test
    void startingARoundWithAPaletteInHandNeverLooksOneUpByName() throws Exception {
        try (var bytes = NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            var model = ClassFile.of().parse(bytes.readAllBytes());
            var start = model.methods().stream().filter(m -> m.methodName().equalsString("startSweep")).findFirst().orElseThrow();
            var calls = start.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).toList();
            assertTrue(calls.stream().anyMatch(c -> c.owner().asInternalName().endsWith("SweepJob")
                    && c.name().equalsString("palette")), "startSweep reads the palette a round brings with it");
            assertFalse(calls.stream().anyMatch(c -> c.owner().asInternalName().endsWith("SweepOutcome")
                    && c.name().equalsString("paletteName")));
        }
    }

    @Test
    void aGraphOverTheAuditNodeCapIsNotReadFromTheWorldFirst() {
        // Control: within the cap a live audit, or one for an unclassified graph, reads the world.
        assertTrue(NpcNavCommand.needsWorldRead(true, false, 100, 100));
        assertTrue(NpcNavCommand.needsWorldRead(false, true, 100, 100));
        assertFalse(NpcNavCommand.needsWorldRead(false, false, 100, 100), "a classified graph audited from its own classes");
        // Over the cap there is nothing to read: the audit refuses it straight away.
        assertFalse(NpcNavCommand.needsWorldRead(true, false, 101, 100));
        assertFalse(NpcNavCommand.needsWorldRead(false, true, 101, 100));
    }

    @Test
    void theNodeCapComesFromConfigAndReachesTheAuditOptions() throws Exception {
        Class<?> settingsType = Class.forName("com.prosperitysmp.npc.command.NpcNavCommand$Settings");
        Object settings = build(settingsType, new StreetRules(12, 16, 0.4), null, 1L, 1, 4321, false, 1, 1, 1, 1, 0, 0, null);
        Method options = settingsType.getDeclaredMethod("auditOptions");
        options.setAccessible(true);
        assertEquals(4321, ((com.prosperitysmp.npc.nav.StreetAudit.Options) options.invoke(settings)).maxNodes());
        try (var bytes = NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            var model = ClassFile.of().parse(bytes.readAllBytes());
            boolean reads = false;
            for (var entry : model.constantPool()) {
                if (entry instanceof java.lang.classfile.constantpool.StringEntry text
                        && text.stringValue().equals("nav.audit.max-nodes")) reads = true;
            }
            assertTrue(reads, "readSettings reads nav.audit.max-nodes");
        }
    }
}
