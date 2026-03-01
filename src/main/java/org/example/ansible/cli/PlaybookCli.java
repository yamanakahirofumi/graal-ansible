package org.example.ansible.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.PlaybookExecutor;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI implementation for graal-ansible, compatible with ansible-playbook.
 */
@Command(name = "graal-ansible", mixinStandardHelpOptions = true, version = "graal-ansible 1.0",
        description = "Runs Ansible playbooks using GraalVM.")
public class PlaybookCli implements Callable<Integer> {

    @Parameters(index = "0", description = "The playbook file to run.")
    private File playbookFile;

    @Option(names = {"-i", "--inventory"}, description = "Specify inventory host path.")
    private String inventoryPath;

    @Option(names = {"-e", "--extra-vars"}, description = "Set additional variables as key=value or YAML/JSON.")
    private List<String> extraVars = new ArrayList<>();

    @Option(names = {"-v", "--verbose"}, description = "Verbose mode (-v, -vv, -vvv, etc.)")
    private boolean[] verbose;

    @Option(names = {"-l", "--limit"}, description = "Further limit selected hosts to an additional pattern.")
    private String limit;

    @Option(names = {"-t", "--tags"}, description = "Only run plays and tasks tagged with these values.")
    private List<String> tags = new ArrayList<>();

    @Option(names = {"-C", "--check"}, description = "Don't make any changes; instead, try to predict some of the changes that may occur.")
    private boolean check;

    @Override
    public Integer call() {
        int verbosity = verbose == null ? 0 : verbose.length;
        if (verbosity > 0) {
            System.out.printf("Running playbook: %s%n", playbookFile);
            if (inventoryPath != null) {
                System.out.printf("Inventory: %s%n", inventoryPath);
            }
            if (!extraVars.isEmpty()) {
                System.out.printf("Extra vars: %s%n", extraVars);
            }
            System.out.printf("Verbosity level: %d%n", verbosity);
        }

        try {
            // Load Playbook
            YamlParser yamlParser = new YamlParser();
            Playbook playbook;
            try (InputStream is = new FileInputStream(playbookFile)) {
                playbook = yamlParser.parse(is);
            }

            // Load Inventory
            Inventory inventory;
            if (inventoryPath != null) {
                IniInventoryParser inventoryParser = new IniInventoryParser();
                try (InputStream is = new FileInputStream(inventoryPath)) {
                    inventory = inventoryParser.parse(is);
                }
            } else {
                // Default or empty inventory if not provided
                System.err.println("No inventory provided. Execution might skip hosts.");
                return 1;
            }

            // Setup TaskExecutor with standard modules
            TaskExecutor taskExecutor = new TaskExecutor();
            registerStandardModules(taskExecutor);

            // Parse extra-vars
            Map<String, Object> parsedExtraVars = parseExtraVars(extraVars);

            // Execute Playbook
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory, parsedExtraVars);

            // Print Results
            printSummary(results);

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbosity > 1) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private Map<String, Object> parseExtraVars(List<String> extraVars) {
        Map<String, Object> result = new HashMap<>();
        for (String var : extraVars) {
            if (var.contains("=")) {
                String[] parts = var.split("=", 2);
                result.put(parts[0].trim(), parts[1].trim());
            } else {
                // Support for JSON/YAML extra-vars can be added here
                // For now, only key=value is supported as per the simple implementation
            }
        }
        return result;
    }

    private void registerStandardModules(TaskExecutor executor) {
        executor.registerModule("debug", args -> {
            Object msg = args.getOrDefault("msg", "Hello world");
            System.out.println("DEBUG: " + msg);
            return TaskResult.success(false, Map.of("msg", msg));
        });
        // You can register more modules here (e.g., shell, command if implemented)
    }

    private void printSummary(Map<String, List<TaskResult>> results) {
        System.out.println("\nPLAY RECAP *********************************************************************");
        for (Map.Entry<String, List<TaskResult>> entry : results.entrySet()) {
            String host = entry.getKey();
            List<TaskResult> hostResults = entry.getValue();
            long ok = hostResults.stream().filter(r -> r.success() && !r.changed()).count();
            long changed = hostResults.stream().filter(r -> r.success() && r.changed()).count();
            long failed = hostResults.stream().filter(r -> !r.success()).count();

            System.out.printf("%-20s : ok=%-4d changed=%-4d failed=%-4d%n", host, ok, changed, failed);
        }
    }

    // Getters for testing
    public File getPlaybook() { return playbookFile; }
    public String getInventory() { return inventoryPath; }
    public List<String> getExtraVars() { return extraVars; }
    public int getVerbose() { return verbose == null ? 0 : verbose.length; }
    public String getLimit() { return limit; }
    public List<String> getTags() { return tags; }
    public boolean isCheck() { return check; }
}
