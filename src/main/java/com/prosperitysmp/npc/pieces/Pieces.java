package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.Piece;
import com.prosperitysmp.npc.api.PropertyBackend;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Bukkit side of piece selection: reads the configured ids, lists third-party registrations from the
 * ServicesManager and binds deferred pieces when the server finishes starting. Registered as a listener only when
 * a configured id names a third-party piece, so a server using the built-in pieces sees no new listener.
 */
public final class Pieces implements Listener {
    public static final String MONEY_KEY = "money.source";
    public static final String PROPERTY_KEY = "property.backend";
    public static final String ACCOUNTING_KEY = "accounting.sink";

    private final Server server;
    private final Map<String, ? extends MoneySource> moneyBuiltIns;
    private final Map<String, ? extends PropertyBackend> propertyBuiltIns;
    private final Deferred<MoneySource> money;
    private final Deferred<PropertyBackend> property;
    // Runs after the deferred property backend's binding attempt at startup (bound or not). Main thread.
    private Runnable propertyBound;

    /** Either deferred may be null when its setting names a built-in piece. */
    public Pieces(Server server, Map<String, ? extends MoneySource> moneyBuiltIns,
                  Map<String, ? extends PropertyBackend> propertyBuiltIns,
                  Deferred<MoneySource> money, Deferred<PropertyBackend> property) {
        this.server = server;
        this.moneyBuiltIns = moneyBuiltIns;
        this.propertyBuiltIns = propertyBuiltIns;
        this.money = money;
        this.property = property;
    }

    /** Called once after the deferred property backend's startup binding attempt, whether it bound or not. */
    public void onPropertyBound(Runnable callback) {
        this.propertyBound = callback;
    }

    /** The configured id: trimmed and lowercased, or the default when unset or blank. */
    public static String configuredId(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** The third-party registrations of one kind, in ServicesManager order. Priority is carried but never used. */
    public static <T extends Piece> List<PieceSelector.Candidate<T>> registrations(Server server, Class<T> type) {
        var out = new ArrayList<PieceSelector.Candidate<T>>();
        for (RegisteredServiceProvider<T> registration : server.getServicesManager().getRegistrations(type)) {
            Plugin owner = registration.getPlugin();
            String name = owner == null ? "unknown" : owner.getName();
            String version = owner == null ? "?" : owner.getPluginMeta().getVersion();
            out.add(new PieceSelector.Candidate<>(registration.getProvider(), name, version,
                    registration.getPriority().ordinal()));
        }
        return out;
    }

    public PieceSelector.Selection<MoneySource> selectMoney(String id) {
        return new PieceSelector<>(MoneySource.class).select(id, moneyBuiltIns, registrations(server, MoneySource.class));
    }

    public PieceSelector.Selection<PropertyBackend> selectProperty(String id) {
        return new PieceSelector<>(PropertyBackend.class).select(id, propertyBuiltIns,
                registrations(server, PropertyBackend.class));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        boolean startup = event.getType() == ServerLoadEvent.LoadType.STARTUP;
        if (money != null) money.serverLoaded(startup, () -> selectMoney(money.id()));
        if (property != null) property.serverLoaded(startup, () -> selectProperty(property.id()));
        if (startup && property != null && propertyBound != null) propertyBound.run();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(ServiceRegisterEvent event) {
        var registration = event.getProvider();
        String from = registration.getPlugin() == null ? "unknown" : registration.getPlugin().getName();
        if (money != null && registration.getService() == MoneySource.class) money.registered(from);
        if (property != null && registration.getService() == PropertyBackend.class) property.registered(from);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        Object provider = event.getProvider().getProvider();
        if (money != null) money.unregistered(provider);
        if (property != null) property.unregistered(provider);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        if (money != null) money.pluginDisabled(name);
        if (property != null) property.pluginDisabled(name);
    }
}
