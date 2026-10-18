package com.prosperitysmp.npc.command;

import java.sql.SQLException;

/** A piece of {@code /npcnav} work for the nav worker thread. No Bukkit calls. */
@FunctionalInterface
interface Work {
    Reply run() throws SQLException;
}
