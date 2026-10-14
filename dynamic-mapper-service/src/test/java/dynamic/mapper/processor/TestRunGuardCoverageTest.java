package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A test run (Dry Run or inbound Live Test) must never change a mapping's runtime status. The guard is
 * {@code ProcessingContext.affectsMappingStatus()}, applied at every call site, so a new call site that forgets
 * it would silently let a failing test count towards the failure streak and auto-deactivate the mapping.
 * This test fails for such a call site.
 */
class TestRunGuardCoverageTest {

    private static final Path PROCESSOR_SOURCES = Paths.get("src/main/java/dynamic/mapper/processor");

    /** How many lines above the call the guard may sit. */
    private static final int WINDOW = 12;

    @Test
    void everyFailureCountUpdateIsGuardedByAffectsMappingStatus() throws IOException {
        List<String> unguarded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PROCESSOR_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains("increaseAndHandleFailureCount(")) {
                        continue;
                    }
                    boolean guarded = false;
                    for (int j = Math.max(0, i - WINDOW); j < i; j++) {
                        if (lines.get(j).contains("affectsMappingStatus()")) {
                            guarded = true;
                        }
                    }
                    if (!guarded) {
                        unguarded.add(file + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(unguarded.isEmpty(),
                "increaseAndHandleFailureCount without an affectsMappingStatus() guard (a test run would count): "
                        + unguarded);
    }

    @Test
    void everyFailureStreakResetIsGuarded() throws IOException {
        List<String> unguarded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PROCESSOR_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("resetFailureCountOnSuccess(")
                            && lines.subList(Math.max(0, i - WINDOW), i).stream()
                                    .noneMatch(l -> l.contains("affectsMappingStatus()"))) {
                        unguarded.add(file + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(unguarded.isEmpty(), "failure streak reset without a guard: " + unguarded);
    }
}
