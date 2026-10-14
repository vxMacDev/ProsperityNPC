package com.prosperitysmp.npc.training;

import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/** Narrow installed-API boundary; tests substitute employment, not the event multiplier implementation. */
interface TrainingJobsAccess {
    Plugin plugin();
    TrainingJobsAdapter.EventBinding money();
    TrainingJobsAdapter.EventBinding xp();
    Optional<String> employed(UUID id, String job) throws ReflectiveOperationException;
}
