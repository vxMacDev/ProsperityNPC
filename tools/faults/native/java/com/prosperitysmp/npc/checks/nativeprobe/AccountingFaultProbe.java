package com.prosperitysmp.npc.checks.nativeprobe;

import com.google.gson.GsonBuilder;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.economy.AccountingProjection;
import com.prosperitysmp.npc.config.KillSwitches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class AccountingFaultProbe extends JavaPlugin {
    private static final String LAB_FLAG = "prosperitynpc.fault-lab";
    private ProsperityNpcPlugin core;
    private AccountingProjection projection;

    @Override public void onEnable() {
        if (!Boolean.getBoolean(LAB_FLAG)) {
            getLogger().warning("Fault probe disabled; start a disposable test server with -D" + LAB_FLAG + "=true to enable it.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        core = (ProsperityNpcPlugin) Bukkit.getPluginManager().getPlugin("ProsperityNPC");
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException(reason);
    }

    private void requireLab() {
        require(Boolean.getBoolean(LAB_FLAG), "Lab flag is missing");
        require(Bukkit.isPrimaryThread() && Bukkit.getOnlinePlayers().isEmpty(), "Main-thread empty lab required");
        require(core != null && core.isEnabled() && core.killSwitches() != null, "Core is not ready");
        for (var gate : KillSwitches.Switch.values())
            require(!core.killSwitches().isEnabled(gate), "All simulation switches must be off");
        require(!core.getConfig().getBoolean("economy.payments-enabled")
                && !core.getConfig().getBoolean("bills.enabled")
                && !core.getConfig().getBoolean("item-repairs.enabled"), "Payments, bills and item repairs must be off");
        require("internal".equals(core.accountingSinkId()) && "vault".equals(core.moneySourceId()),
                "Use the internal sink and built-in Vault money source");
        require(projection != null, "No actual accounting projection");
    }

    private Object field(String name) throws Exception {
        var field = AccountingProjection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(projection);
    }

    private void tick() throws Exception {
        var method = AccountingProjection.class.getDeclaredMethod("tick");
        method.setAccessible(true);
        method.invoke(projection);
    }

    private static Object component(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private Map<String, Object> status() throws Exception {
        requireLab();
        var out = new LinkedHashMap<String, Object>();
        out.put("players", Bukkit.getOnlinePlayers().size());
        out.put("ready", projection.ready());
        out.put("generation", field("generation"));
        out.put("inFlight", field("inFlight"));
        out.put("acknowledged", field("acknowledged"));
        var snapshot = projection.snapshot();
        out.put("snapshotGeneration", snapshot == null ? null : snapshot.generation());
        out.put("controls", core.killSwitches().summary());
        out.put("paymentsEnabled", core.getConfig().getBoolean("economy.payments-enabled"));
        out.put("billsEnabled", core.getConfig().getBoolean("bills.enabled"));
        Path corePath = Path.of(AccountingProjection.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        out.put("coreSha256", hash(corePath));
        out.put("probeSha256", hash(Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())));
        try (var stream = getClass().getResourceAsStream("AccountingFaultProbe.class")) {
            out.put("probeClassSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
        }
        try (var stream = AccountingProjection.class.getResourceAsStream("AccountingProjection.class")) {
            out.put("projectionClassSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
        }
        return out;
    }

    private void artifact(Map<String, Object> out) throws Exception {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        require(registration != null, "No actual economy registration");
        var provider = registration.getProvider();
        var pin = core.moneySource().pin().orElseThrow();
        require(pin.stillCurrent() && pin.enabled(), "Provider is unavailable");
        Path actual = pin.artifact();
        Path concrete = Path.of(provider.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        Path api = Path.of(Economy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        out.put("providerName", provider.getName());
        out.put("providerClass", provider.getClass().getName());
        out.put("registrationOwner", registration.getPlugin().getName());
        out.put("pinArtifact", actual.toString());
        out.put("pinArtifactSha256", hash(actual));
        out.put("providerArtifact", concrete.toString());
        out.put("providerArtifactSha256", hash(concrete));
        out.put("apiArtifact", api.toString());
        out.put("apiArtifactSha256", hash(api));
        if (!actual.toRealPath().equals(concrete.toRealPath()) || !hash(actual).equals(hash(concrete)))
            throw new AssertionError("Money pin differs from concrete provider artifact");
    }

    private void race(Map<String, Object> out) throws Exception {
        out.put("before", status());
        require(projection.ready() && !(boolean) field("inFlight"), "Ready idle projection required");
        require(!core.getConfig().getBoolean("economy.payments-enabled")
                && !core.getConfig().getBoolean("bills.enabled"), "Financial switches must be off");
        try (Connection blocker = core.database().getConnection(); var command = blocker.createStatement()) {
            command.execute("LOCK TABLES npc_economy_events WRITE");
            try {
                projection.invalidate();
                require(!projection.ready() && projection.snapshot() == null, "Invalidate retained readiness");
                tick();
                long oldGeneration = (long) field("generation");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
                String waiting = null;
                while (System.nanoTime() < deadline && waiting == null) {
                    try (var rows = command.executeQuery("SHOW FULL PROCESSLIST")) {
                        while (rows.next()) {
                            String info = rows.getString("Info"), state = rows.getString("State");
                            if (info != null && info.contains("SELECT e.event_id,e.source")) {
                                out.put("workerObservedQuery", info);
                                out.put("workerObservedState", state);
                                if (state != null && state.toLowerCase(Locale.ROOT).contains("lock")) waiting = state;
                            }
                        }
                    }
                    if (waiting == null) Thread.sleep(2);
                }
                require(waiting != null, "Actual projection query was not observed waiting");
                out.put("workerWaitingState", waiting);
                out.put("oldGeneration", oldGeneration);
                projection.invalidate();
                out.put("invalidatedGeneration", field("generation"));
                command.execute("UNLOCK TABLES");
                Object done = null;
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
                while (System.nanoTime() < deadline && done == null) {
                    synchronized (field("completionLock")) { done = field("completion"); }
                    if (done == null) Thread.sleep(2);
                }
                require(done != null, "No actual completed worker read");
                require((long) component(done, "generation") == oldGeneration, "Unexpected completion generation");
                var snapshot = (AccountingProjection.Snapshot) component(done, "snapshot");
                require(snapshot != null && snapshot.generation() == oldGeneration, "Old read failed or was replaced");
                out.put("successfulOldCompletion", true);
                tick();
                out.put("afterOldCompletion", status());
                if (projection.ready() || projection.snapshot() != null)
                    throw new AssertionError("Old accounting generation became ready after invalidation");
            } finally { command.execute("UNLOCK TABLES"); }
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)
                && !(sender instanceof org.bukkit.command.RemoteConsoleCommandSender)) return false;
        if (args.length != 2 || !args[1].matches("[a-z0-9-]{1,64}")
                || !(args[0].equals("accounting-race") || args[0].equals("status"))) return false;
        Path report = getDataFolder().toPath().resolve(args[1] + ".json");
        var out = new LinkedHashMap<String, Object>();
        out.put("command", args[0]);
        try {
            Files.createDirectories(report.getParent());
            try (var writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                try {
                    projection = core.accountingProjection();
                    if (args[0].equals("accounting-race")) race(out);
                    else { out.putAll(status()); artifact(out); }
                    out.put("result", "PASS");
                } catch (AssertionError failure) {
                    out.put("result", "FAILED");
                    out.put("exception", failure.getClass().getName());
                    out.put("message", String.valueOf(failure.getMessage()));
                } catch (Exception | LinkageError failure) {
                    out.put("result", "INVALID");
                    out.put("exception", failure.getClass().getName());
                    out.put("message", String.valueOf(failure.getMessage()));
                }
                writer.write(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out));
                writer.write("\n");
            }
            sender.sendMessage("Saved " + args[1] + ": " + out.get("result") + ". See " + report);
        } catch (FileAlreadyExistsException exists) {
            sender.sendMessage("Report label already exists; no check was run.");
        } catch (Exception failure) {
            sender.sendMessage("Probe output failed: " + failure.getClass().getSimpleName());
        }
        return true;
    }
}
