package com.prosperitysmp.npc;

import com.prosperitysmp.npc.db.DatabaseWorkers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Shared fixtures for the command, config and root-plugin tests: a plugin instance built without a server, and
 * command senders that record what they are told. Nothing here moves money or touches a database.
 */
public final class CoreTestSupport {
    private CoreTestSupport() { }

    /** One message a sender received: its plain text and colour. */
    public record Message(String text, NamedTextColor color) { }

    /** Everything a recording sender was sent, in order. */
    public static final class Inbox {
        private final List<Message> messages = new ArrayList<>();

        public List<Message> messages() {
            return List.copyOf(messages);
        }

        public List<String> texts() {
            return messages.stream().map(Message::text).toList();
        }

        public Message only() {
            if (messages.size() != 1) throw new AssertionError("expected one message but got " + texts());
            return messages.getFirst();
        }

        void add(Component component) {
            if (!(component instanceof TextComponent text)) throw new AssertionError("unexpected component " + component);
            messages.add(new Message(text.content(), text.color() instanceof NamedTextColor named ? named : null));
        }
    }

    /**
     * A command sender of the given type. {@code allowed} answers every permission check; any other call that
     * the production code makes on the sender fails the test.
     */
    public static <T extends CommandSender> T sender(Class<T> type, Inbox inbox, Predicate<String> allowed, UUID id,
                                                     String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "sendMessage":
                    if (args != null && args.length == 1 && args[0] instanceof Component component) {
                        inbox.add(component);
                        return null;
                    }
                    throw new AssertionError("Unexpected sendMessage overload: " + method);
                case "hasPermission":
                    if (args[0] instanceof String node) return allowed.test(node);
                    throw new AssertionError("Unexpected permission lookup: " + method);
                case "getUniqueId":
                    return id;
                case "getName":
                    return name;
                case "isOnline":
                    return true;
                case "isDead":
                    return false;
                case "toString":
                    return type.getSimpleName() + ":" + name;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new AssertionError("Unexpected sender call: " + method);
            }
        }));
    }

    /** A plugin whose config is the given YAML and whose saved config goes to {@code data}/config.yml. */
    public static ProsperityNpcPlugin plugin(Path data, YamlConfiguration config, List<String> log) {
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            ProsperityNpcPlugin plugin = (ProsperityNpcPlugin) allocate.invoke(unsafe, ProsperityNpcPlugin.class);
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) {
                    log.add(record.getLevel() + ": " + record.getMessage());
                }
                @Override public void flush() { }
                @Override public void close() { }
            });
            File folder = data.toFile();
            set(JavaPlugin.class, plugin, "dataFolder", folder);
            set(JavaPlugin.class, plugin, "configFile", new File(folder, "config.yml"));
            set(JavaPlugin.class, plugin, "newConfig", config);
            set(JavaPlugin.class, plugin, "logger", logger);
            set(JavaPlugin.class, plugin, "classLoader", CoreTestSupport.class.getClassLoader());
            set(ProsperityNpcPlugin.class, plugin, "databaseWorkers", new DatabaseWorkers());
            return plugin;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Sets a private field of the plugin, for the services a test supplies itself. */
    public static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (InvalidConfigurationException failure) {
            throw new AssertionError(failure);
        }
        return config;
    }
}
