package com.prosperitysmp.npc.publication.bootstrap;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.LogManager;

/** JDK-only startup agent. No credentials, network, SQL, bytecode transformation, or logging-property changes. */
public final class ChronicleBootstrap {
    public static final String VERSION = "npc-chronicle-bootstrap-v1";
    private static final String PAPER_MANAGER = "io.papermc.paper.log.CustomLogManager";
    // Installed Paper 26.1.2 class: only preserves JUL loggers across reset; no HTTP diagnostic configuration.
    private static final String PAPER_MANAGER_SHA256 = "dad4bb4c2a19f56f424c80ab407fa856a89a0c21b2aac01d220026a3234da050";
    private static final List<String> DIAGNOSTICS = List.of("jdk.httpclient.HttpClient.log", "jdk.internal.httpclient.debug",
            "jdk.internal.httpclient.websocket.debug", "jdk.internal.httpclient.hpack.debug", "javax.net.debug", "java.security.debug",
            "jdk.internal.httpclient.disableHostnameVerification", "java.util.logging.config.class", "java.util.logging.manager",
            "java.system.class.loader", "jdk.system.logger.level");
    private static volatile Proof proof;
    private static volatile String auditStatus = "NO_PREMAIN";
    private static boolean invoked;
    private ChronicleBootstrap() { }
    private record Input(Path logical, Path real, Object key, long size, FileTime modified, String sha256, int bound) { }
    private record Proof(long pid, Instant started, List<Input> inputs, String javaHome, String loggingConfig, Class<?> log,
                         Class<?> utils, Class<?> ssl, String[] report) { }

    /** Only startup invocation is supported. Failed audit holds publication, without aborting base server startup. */
    public static synchronized void premain(String options, Instrumentation instrumentation) {
        if (invoked) {
            proof = null;
            auditStatus = "HELD_REPEATED_PREMAIN";
            return;
        }
        invoked = true;
        try {
            auditStatus = "HELD_STARTUP_CLASSLOADER";
            if (options != null && !options.isEmpty() || instrumentation == null
                    || !instrumentation.getClass().getName().equals("sun.instrument.InstrumentationImpl")
                    || !"java.instrument".equals(instrumentation.getClass().getModule().getName())
                    || ChronicleBootstrap.class.getClassLoader() != ClassLoader.getSystemClassLoader()
                    || Runtime.version().feature() != 25) return;
            auditStatus = "HELD_PRELOADED_DIAGNOSTICS";
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                String name = loaded.getName();
                if ("java.net.http".equals(loaded.getModule().getName()) || name.equals("sun.net.NetProperties")
                        || name.equals("sun.security.ssl.SSLLogger")) return;
            }
            auditStatus = "HELD_STARTUP_PROPERTIES";
            cleanProperties();
            for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
                if (!System.getenv().getOrDefault(variable, "").isBlank()) return;
            auditStatus = "HELD_STARTUP_OPTIONS";
            Path agent = Path.of(ChronicleBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            List<String> optionsList = ManagementFactory.getRuntimeMXBean().getInputArguments();
            int agents = 0;
            for (String option : optionsList) {
                if (option.startsWith("-javaagent:")) {
                    agents++;
                    if (!Path.of(option.substring(11)).toRealPath().equals(agent)) return;
                } else if (option.startsWith("-agentlib:") || option.startsWith("-agentpath:") || option.startsWith("-Xbootclasspath")
                        || option.startsWith("--patch-module") || option.startsWith("--upgrade-module-path")
                        || option.startsWith("-XX:SharedArchiveFile") || option.equals("-XX:+EnableDynamicAgentLoading")) return;
            }
            if (agents != 1 || !optionsList.contains("-XX:-EnableDynamicAgentLoading")) return;
            auditStatus = "HELD_STARTUP_EXECUTABLE";
            Path home = Path.of(System.getProperty("java.home")).toRealPath();
            Path executable = Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath();
            String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            if (!executable.equals(home.resolve("bin").resolve(binary).toRealPath())) return;
            auditStatus = "HELD_STARTUP_FILES";
            List<Input> inputs = new ArrayList<>();
            inputs.add(input(agent, 1_048_576));
            Input net = input(home.resolve("conf/net.properties"), 65536);
            inputs.add(net);
            Input logging = input(home.resolve("conf/logging.properties"), 65536);
            inputs.add(logging);
            Input release = input(home.resolve("release"), 16384);
            inputs.add(release);
            String configured = System.getProperty("java.util.logging.config.file");
            if (configured != null) inputs.add(input(Path.of(configured).toAbsolutePath(), 65536));
            for (Input file : inputs.subList(1, inputs.size())) inspectProperties(file);
            auditStatus = "HELD_CACHED_DIAGNOSTICS";
            Class<?> log = Class.forName("jdk.internal.net.http.common.Log", false, ClassLoader.getPlatformClassLoader());
            Module target = ChronicleBootstrap.class.getModule();
            instrumentation.redefineModule(log.getModule(), Set.of(), Map.of(),
                    Map.of("jdk.internal.net.http.common", Set.of(target)), Set.of(), Map.of());
            instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("sun.security.ssl", Set.of(target)), Set.of(), Map.of());
            Class.forName(log.getName(), true, log.getClassLoader());
            Class<?> utils = Class.forName("jdk.internal.net.http.common.Utils", true, log.getClassLoader());
            Class<?> debug = Class.forName("jdk.internal.net.http.common.DebugLogger", true, log.getClassLoader());
            Class<?> ssl = Class.forName("sun.security.ssl.SSLLogger", true, null);
            cachedOff(log, utils, ssl);
            cleanProperties();
            for (Input file : inputs) unchanged(file);
            long pid = ProcessHandle.current().pid();
            Instant started = ProcessHandle.current().info().startInstant().orElseThrow();
            String launch = home + "\n" + executable + "\n" + String.join("\n", optionsList);
            String sources = String.join("\n", inputs.stream().map(file -> file.real() + "=" + file.sha256()).toList());
            String startup = sha(launch + "\n" + sources);
            String[] report = {VERSION, Long.toString(pid), started.toString(), inputs.getFirst().sha256(),
                    Runtime.version().toString(), startup, classHash(log), classHash(utils), classHash(debug), classHash(ssl), ""};
            report[10] = sha(String.join("\n", report) + "\n" + UUID.randomUUID());
            proof = new Proof(pid, started, List.copyOf(inputs), System.getProperty("java.home"), configured, log, utils, ssl, report);
            auditStatus = "READY";
        } catch (Exception | LinkageError held) { proof = null; }
    }

    /** Redacted, immutable per-process evidence. Empty means held; never prints options, file contents, or credentials. */
    public static String[] evidence() {
        Proof current = proof;
        if (current == null) return new String[0];
        try {
            if (current.pid() != ProcessHandle.current().pid()
                    || !current.started().equals(ProcessHandle.current().info().startInstant().orElseThrow())) return new String[0];
            if (!Objects.equals(current.javaHome(), System.getProperty("java.home"))
                    || !Objects.equals(current.loggingConfig(), System.getProperty("java.util.logging.config.file")))
                throw new IOException();
            cleanProperties(true);
            cachedOff(current.log(), current.utils(), current.ssl());
            for (Input input : current.inputs()) unchanged(input);
            return current.report().clone();
        } catch (Exception | LinkageError revoked) {
            proof = null;
            auditStatus = "HELD_RECHECK";
            return new String[0];
        }
    }
    /** Redacted audit phase only; no paths, properties, environment values, or exception messages. */
    public static String diagnosticStatus() { return auditStatus; }
    private static void cleanProperties() throws Exception { cleanProperties(false); }
    private static void cleanProperties(boolean runtime) throws Exception {
        for (String key : DIAGNOSTICS) {
            String value = System.getProperty(key);
            if (value == null) continue;
            if (runtime && key.equals("java.util.logging.manager") && value.equals(PAPER_MANAGER)) {
                Class<?> manager = LogManager.getLogManager().getClass();
                if (manager.getName().equals(PAPER_MANAGER) && classHash(manager).equals(PAPER_MANAGER_SHA256)) continue;
            }
            throw new IOException();
        }
    }
    private static void inspectProperties(Input input) throws Exception {
        Properties properties = new Properties() {
            private static final long serialVersionUID = 1L;
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate startup property");
                return super.put(key, value);
            }
        };
        byte[] bytes;
        try (var stream = Files.newInputStream(input.real(), LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(input.bound() + 1); }
        if (bytes.length != input.size() || !sha(bytes).equals(input.sha256())) throw new IOException();
        try (var stream = new ByteArrayInputStream(bytes)) { properties.load(stream); }
        for (String key : properties.stringPropertyNames()) {
            if ((DIAGNOSTICS.contains(key) || key.startsWith("jdk.httpclient.") || key.startsWith("jdk.internal.httpclient.")
                    || key.startsWith("sun.security.ssl.")) && (key.equals("javax.net.debug") || !properties.getProperty(key).isBlank()))
                throw new IOException();
        }
    }
    private static void cachedOff(Class<?> log, Class<?> utils, Class<?> ssl) throws ReflectiveOperationException, IOException {
        if (!Integer.valueOf(0).equals(field(log, "logging")) || field(log, "logger") != null
                || !Boolean.FALSE.equals(field(utils, "DEBUG")) || !Boolean.FALSE.equals(field(utils, "DEBUG_WS"))
                || !Boolean.FALSE.equals(field(utils, "isHostnameVerificationDisabled"))
                || !Boolean.FALSE.equals(field(ssl, "isOn")) || field(ssl, "logger") != null) throw new IOException();
        for (String name : List.of("DEBUG_CONFIG", "DEBUG_WS_CONFIG", "DEBUG_HPACK_CONFIG")) {
            Object config = field(utils, name);
            var method = config.getClass().getDeclaredMethod("on");
            method.setAccessible(true);
            if (!Boolean.FALSE.equals(method.invoke(config))) throw new IOException();
        }
    }
    private static Object field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static Input input(Path logical, int bound) throws Exception {
        Path real = logical.toRealPath();
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() < 1 || before.size() > bound) throw new IOException();
        byte[] bytes;
        try (var stream = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(bound + 1); }
        if (bytes.length != before.size()) throw new IOException();
        BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException();
        return new Input(logical, real, before.fileKey(), before.size(), before.lastModifiedTime(), sha(bytes), bound);
    }
    private static void unchanged(Input expected) throws Exception {
        if (!expected.equals(input(expected.logical(), expected.bound()))) throw new IOException();
    }
    private static String classHash(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw new IOException();
            byte[] bytes = stream.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IOException();
            return sha(bytes);
        }
    }
    private static String sha(String text) throws Exception { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
