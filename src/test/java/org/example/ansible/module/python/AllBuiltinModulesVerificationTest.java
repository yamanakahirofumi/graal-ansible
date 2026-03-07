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

    private TaskExecutor taskExecutor;
    private static final Path MODULES_PATH = Paths.get("target", "python-packages", "ansible", "modules");

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
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
        List<String> failedWithImportError = new ArrayList<>();
        List<String> failedWithOtherError = new ArrayList<>();

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
            } else if (msg.contains("Import error") || msg.contains("ModuleNotFoundError")) {
                failedWithImportError.add(moduleName);
            } else {
                failedWithOtherError.add(moduleName);
            }
        }

        System.out.println("\nVerification Summary:");
        System.out.println("---------------------");
        System.out.println("Total modules: " + modules.size());
        System.out.println("Success (executed): " + success.size());
        System.out.println("Success (loaded, but missing args): " + failedWithArgsError.size());
        System.out.println("Failed (Import error): " + failedWithImportError.size());
        System.out.println("Failed (Other error): " + failedWithOtherError.size());

        if (!failedWithImportError.isEmpty()) {
            System.out.println("\nImport Errors:");
            failedWithImportError.forEach(m -> {
                Task task = new Task("debug_import_" + m, m, Map.of());
                TaskResult result = taskExecutor.execute(task, BecomeContext.empty());
                System.out.println(" - " + m + ": " + result.message());
            });
        }

        if (!failedWithOtherError.isEmpty()) {
            System.out.println("\nOther Errors:");
            failedWithOtherError.forEach(m -> {
                Task task = new Task("debug_" + m, m, Map.of());
                TaskResult result = taskExecutor.execute(task, BecomeContext.empty());
                System.out.println(" - " + m + ": " + result.message());
            });
        }

        // In Phase 1, we expect at least 30 modules to load successfully (either success or missing args)
        int loadedCount = success.size() + failedWithArgsError.size();
        assertTrue(loadedCount >= 30, "Expected at least 30 modules to load, but only " + loadedCount + " did.");
    }
}
