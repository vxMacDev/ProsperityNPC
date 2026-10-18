package com.prosperitysmp.npc.command;

import net.kyori.adventure.text.format.NamedTextColor;

/** One line of a {@code /npcnav} reply. */
record Line(NamedTextColor color, String text) { }
