package com.prosperitysmp.npc.checks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

public final class SeamFaultChecks {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private record Fault(String id, String source, String subjectClass, String testClass, int testCount,
                         List<String> failedMethods, List<String> before, List<String> after,
                         List<String> testMethods, String assertionMessage) {
        boolean selectsMethods() { return testMethods != null && !testMethods.isEmpty(); }
    }
    private record Request(Fault fault, boolean baseline, String expectedOrigin) { }

    private SeamFaultChecks() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--case")) {
            if (!runCase(Path.of(args[1]))) throw new IllegalStateException("Invalid or unsuccessful fault check");
            return;
        }
        if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException("project, report folder, main classes, selection and optional catalogue required");
        Path project = Path.of(args[0]).toRealPath();
        Path mainClasses = Path.of(args[2]).toRealPath();
        String catalogue = args.length == 5 ? args[4] : "seams.json";
        if (!Set.of("seams.json", "bills.json").contains(catalogue)) throw new IllegalArgumentException("Unknown catalogue");
        List<Fault> all = JSON.fromJson(Files.readString(project.resolve("tools/faults").resolve(catalogue)),
                new TypeToken<List<Fault>>() { }.getType());
        Set<String> selected = args[3].equals("all") ? new LinkedHashSet<>()
                : new LinkedHashSet<>(Arrays.asList(args[3].split(",", -1)));
        if (args[3].equals("all")) all.forEach(fault -> selected.add(fault.id()));
        if (selected.isEmpty() || !all.stream().map(Fault::id).toList().containsAll(selected))
            throw new IllegalArgumentException("Select known IDs from tools/faults/" + catalogue);
        List<Fault> faults = all.stream().filter(fault -> selected.contains(fault.id())).toList();
        Path report = Path.of(args[1]).toAbsolutePath().resolve("run-" + UUID.randomUUID());
        Files.createDirectories(report);
        if (catalogue.equals("bills.json") && Files.getFileStore(report).getUsableSpace() < (6L << 30))
            throw new IllegalStateException("Billing SQL checks need at least 6 GB free on the report drive");
        String classpath = System.getProperty("java.class.path");
        Set<String> baselines = new LinkedHashSet<>();
        var results = new LinkedHashMap<String, Object>();
        for (Fault fault : faults) {
            String cohort = fault.testClass() + "#" + (fault.selectsMethods() ? fault.testMethods().toString() : "*")
                    + " (" + fault.testCount() + ")";
            if (baselines.add(cohort)) {
                Path folder = Files.createDirectory(report.resolve("baseline-" + baselines.size()));
                launch(project, folder, new Request(fault, true, mainClasses.toString()), classpath);
                System.out.println("Baseline passed: " + cohort);
            }
            Path folder = Files.createDirectory(report.resolve(fault.id()));
            Path source = project.resolve(fault.source()).toRealPath();
            if (!source.startsWith(project.resolve("src/main/java")))
                throw new IllegalArgumentException("Source must be under src/main/java");
            String original = Files.readString(source).replace("\r\n", "\n");
            String before = String.join("\n", fault.before()) + "\n";
            String after = String.join("\n", fault.after()) + "\n";
            int offset = original.indexOf(before);
            if (before.isBlank() || before.equals(after) || offset < 0 || original.indexOf(before, offset + 1) >= 0)
                throw new IllegalStateException(fault.id() + ": source edit must match exactly once");
            Path changed = folder.resolve(source.getFileName());
            Files.writeString(changed, original.replace(before, after), StandardOpenOption.CREATE_NEW);
            Path classes = Files.createDirectory(folder.resolve("classes"));
            try (var log = Files.newOutputStream(folder.resolve("compile.log"))) {
                var compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) throw new IllegalStateException("Run with JDK 25");
                int result = compiler.run(null, log, log, "--release", "25", "-encoding", "UTF-8", "-g",
                        "-proc:none", "-implicit:none", "-classpath", classpath, "-d", classes.toString(), changed.toString());
                if (result != 0) throw new IllegalStateException(fault.id() + ": compilation failed; see " + folder);
            }
            launch(project, folder, new Request(fault, false, classes.toString()), classes + File.pathSeparator + classpath);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("source", fault.source());
            entry.put("originalSha256", hash(source));
            entry.put("mutatedSha256", hash(changed));
            entry.put("result", "KILLED");
            results.put(fault.id(), entry);
            System.out.println(fault.id() + " caught by " + fault.failedMethods());
        }
        write(report.resolve("summary.json"), Map.of("faults", results, "baselineCohorts", baselines.size(),
                "scope", "Selected source faults and unit/SQL fixtures. Native server, provider lifecycle, client and load acceptance are separate."));
        System.out.println("Reports: " + report);
    }

    private static void launch(Path project, Path folder, Request request, String classpath) throws Exception {
        Path input = folder.resolve("request.json");
        write(input, request);
        Path args = folder.resolve("java.args");
        Path temporary = Files.createDirectory(folder.resolve("tmp"));
        Files.writeString(args, quote("-Djava.io.tmpdir=" + temporary) + "\n-cp\n" + quote(classpath) + "\n" + SeamFaultChecks.class.getName()
                + "\n--case\n" + quote(input.toString()) + "\n", StandardOpenOption.CREATE_NEW);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        var process = new ProcessBuilder(java.toString(), "@" + args).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(folder.resolve("tests.log").toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly().waitFor();
            throw new IllegalStateException("Test process timed out: " + folder);
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Test check failed; see " + folder.resolve("tests.log"));
        if (!Files.isRegularFile(folder.resolve("result.json"))) throw new IllegalStateException("No test result: " + folder);
        Path actualTemporary = temporary.toRealPath();
        if (!actualTemporary.getParent().equals(folder.toRealPath())) throw new IllegalStateException("Temporary folder moved");
        try (var paths = Files.walk(actualTemporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static boolean runCase(Path input) throws Exception {
        Request request = JSON.fromJson(Files.readString(input), Request.class);
        Fault fault = request.fault();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        var discovery = LauncherDiscoveryRequestBuilder.request();
        if (fault.selectsMethods()) {
            for (String method : fault.testMethods()) discovery.selectors(selectMethod(fault.testClass() + "#" + method));
        } else discovery.selectors(selectClass(fault.testClass()));
        launcher.execute(discovery.build());
        var summary = listener.getSummary();
        var failedMethods = new LinkedHashSet<String>();
        var failures = new ArrayList<Object>();
        boolean assertionsOnly = true;
        for (var failure : summary.getFailures()) {
            String method = failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source
                    ? source.getMethodName() : "<container>";
            failedMethods.add(method);
            if (fault.assertionMessage() == null) {
                assertionsOnly &= failure.getException() instanceof org.opentest4j.AssertionFailedError;
            } else {
                assertionsOnly &= !fault.assertionMessage().isBlank()
                        && failure.getException().getClass() == AssertionError.class
                        && fault.assertionMessage().equals(failure.getException().getMessage());
            }
            failures.add(Map.of("method", method, "exception", failure.getException().getClass().getName(),
                    "message", String.valueOf(failure.getException().getMessage())));
        }
        Path actual = Path.of(Class.forName(fault.subjectClass()).getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        boolean valid = fault.testCount() > 0 && summary.getTestsFoundCount() == fault.testCount()
                && summary.getTestsStartedCount() == fault.testCount()
                && summary.getTestsSkippedCount() == 0 && summary.getTestsAbortedCount() == 0
                && summary.getTestsSucceededCount() + summary.getTestsFailedCount() == fault.testCount()
                && summary.getFailures().size() == summary.getTestsFailedCount()
                && actual.equals(Path.of(request.expectedOrigin()).toRealPath());
        boolean passed = valid && summary.getTestsFailedCount() == 0;
        boolean killed = valid && assertionsOnly && summary.getTestsFailedCount() > 0
                && summary.getTestsFailedCount() == fault.failedMethods().size()
                && failedMethods.equals(new LinkedHashSet<>(fault.failedMethods()));
        var result = new LinkedHashMap<String, Object>();
        result.put("id", fault.id());
        result.put("baseline", request.baseline());
        result.put("found", summary.getTestsFoundCount());
        result.put("started", summary.getTestsStartedCount());
        result.put("passed", summary.getTestsSucceededCount());
        result.put("failed", summary.getTestsFailedCount());
        result.put("skipped", summary.getTestsSkippedCount());
        result.put("aborted", summary.getTestsAbortedCount());
        result.put("failures", failures);
        result.put("subjectCodeSource", actual.toString());
        String outcome;
        if (!valid) outcome = "INVALID";
        else if (request.baseline()) outcome = passed ? "PASS" : "FAILED";
        else if (killed) outcome = "KILLED";
        else outcome = passed ? "SURVIVED" : "INVALID";
        result.put("result", outcome);
        write(input.getParent().resolve("result.json"), result);
        return request.baseline() ? passed : killed;
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String quote(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Unsupported argument character");
        return '"' + value.replace('\\', '/') + '"';
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.toJson(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
