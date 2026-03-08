package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test for all modules in the ansible.builtin collection.
 */
class AllBuiltinModulesVerificationTest {

    private static final Path MODULES_PATH = Paths.get("target", "python-packages", "ansible", "modules");

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
    void verifyAllBuiltinModules() throws IOException {
        if (!Files.exists(MODULES_PATH)) {
            fail("Modules directory not found at " + MODULES_PATH + ". Run 'mvn generate-resources' first.");
        }

        List<String> modules;
        try (Stream<Path> stream = Files.list(MODULES_PATH)) {
            modules = stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".py") && !name.equals("__init__.py"))
                    .map(name -> name.substring(0, name.length() - 3))
                    .sorted()
                    .collect(Collectors.toList());
        }

        System.out.println("Verifying " + modules.size() + " modules in ansible.builtin...");

        List<String> success = new ArrayList<>();
        List<String> failedWithArgsError = new ArrayList<>();
        Map<String, String> importErrors = new java.util.TreeMap<>();
        Map<String, String> otherErrors = new java.util.TreeMap<>();

        // Use a single TaskExecutor/Context for efficiency in verification tests
        try (TaskExecutor taskExecutor = new TaskExecutor()) {
            for (String moduleName : modules) {
                taskExecutor.registerModule(moduleName, new PythonModule(moduleName));
                Task task = new Task("verify_" + moduleName, moduleName, Map.of());
                TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

                String msg = result.message() != null ? result.message() : "";

                if (result.success()) {
                    success.add(moduleName);
                } else if (msg.contains("missing required arguments") || msg.contains("at least one of the following is required")) {
                    // This means the module loaded and started executing!
                    failedWithArgsError.add(moduleName);
                } else if (msg.contains("Import error") || msg.contains("ModuleNotFoundError") || msg.contains("ImportError")) {
                    importErrors.put(moduleName, msg);
                } else {
                    otherErrors.put(moduleName, msg);
                }
            }
        }

        System.out.println("\nVerification Summary:");
        System.out.println("---------------------");
        System.out.println("Total modules: " + modules.size());
        System.out.println("Success (executed): " + success.size());
        System.out.println("Success (loaded, but missing args): " + failedWithArgsError.size());
        System.out.println("Failed (Import error): " + importErrors.size());
        System.out.println("Failed (Other error): " + otherErrors.size());

        if (!importErrors.isEmpty()) {
            System.out.println("\nImport Errors:");
            importErrors.forEach((m, msg) -> System.out.println(" - " + m + ": " + msg));
        }

        if (!otherErrors.isEmpty()) {
            System.out.println("\nOther Errors:");
            otherErrors.forEach((m, msg) -> System.out.println(" - " + m + ": " + msg));
        }

        // In Phase 1, we expect a certain number of modules to load successfully (either success or missing args)
        int loadedCount = success.size() + failedWithArgsError.size();
        int threshold = 30;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            threshold = 5; // Windows is expected to have lower compatibility in Phase 1
        }

        assertTrue(loadedCount >= threshold, "Expected at least " + threshold + " modules to load on " + osName + ", but only " + loadedCount + " did.");
    }
}
