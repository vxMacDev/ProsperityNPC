package com.prosperitysmp.npc.publication;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AtomicOccupancyLockProcessTest {
    @TempDir Path directory;

    @Test @Timeout(20)
    void anotherJvmIsExcludedUntilOwnerCloses() throws Exception {
        try (var owner = new AtomicOccupancyArtifacts(directory)) {
            assertNotNull(owner);
            assertEquals(new Result(2, "BUSY"), child());
        }
        assertEquals(new Result(0, "ACQUIRED"), child());
    }

    private Result child() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = String.join(File.pathSeparator, List.of(location(Child.class),
                location(AtomicOccupancyArtifacts.class), location(JsonObject.class), location(Test.class)));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, Child.class.getName(), directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(7, TimeUnit.SECONDS), "child JVM lock probe timed out");
            String output = new String(process.getInputStream().readNBytes(8193), StandardCharsets.UTF_8).trim();
            assertTrue(output.length() <= 8192, "unexpected child output volume");
            return new Result(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS), "own child did not terminate");
            }
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private record Result(int exit, String output) { }

    /** No inherited file handles or shared JVM lock registry with the parent. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            try (var writer = new AtomicOccupancyArtifacts(Path.of(args[0]))) {
                if (writer == null) throw new AssertionError();
                System.out.println("ACQUIRED");
            } catch (IOException failure) {
                if (!"Occupancy writer busy".equals(failure.getMessage())) throw failure;
                System.out.println("BUSY");
                System.exit(2);
            }
        }
    }
}
