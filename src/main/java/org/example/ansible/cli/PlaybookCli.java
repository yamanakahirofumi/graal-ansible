package org.example.ansible.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import org.example.ansible.engine.Playbook;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.example.ansible.module.python.PythonModule;
import org.example.ansible.engine.PlaybookExecutor;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.VariableManager;
import org.example.ansible.inventory.FileInventoryProvider;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.InventoryManager;
import org.example.ansible.inventory.ScriptInventoryProvider;
import org.example.ansible.parser.YamlParser;
import org.graalvm.polyglot.Context;
import org.yaml.snakeyaml.Yaml;

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

    @Option(names = {"-f", "--forks"}, description = "Specify number of parallel processes to use (default=%s)", defaultValue = "5")
    private int forks;

    @Option(names = {"-e", "--extra-vars"}, description = "Set additional variables as key=value or YAML/JSON.")
    private List<String> extraVars = new ArrayList<>();

    @Option(names = {"-v", "--verbose"}, description = "Verbose mode (-v, -vv, -vvv, etc.)")
    private boolean[] verbose;

    @Option(names = {"-l", "--limit"}, description = "Further limit selected hosts to an additional pattern.")
    private String limit;

    @Option(names = {"-t", "--tags"}, description = "Only run plays and tasks tagged with these values.")
    private List<String> tags = new ArrayList<>();

    @Option(names = {"--skip-tags"}, description = "Only run plays and tasks whose tags do not match these values.")
    private List<String> skipTags = new ArrayList<>();

    @Option(names = {"-C", "--check"}, description = "Don't make any changes; instead, try to predict some of the changes that may occur.")
    private boolean check;

    @Option(names = {"-D", "--diff"}, description = "When changing (small) files and templates, show the differences in those files; works great with --check.")
    private boolean diff;

    @Option(names = {"-b", "--become"}, description = "Run operations with become (does not imply password prompting).")
    private boolean become;

    @Option(names = "--become-method", description = "Privilege escalation method to use (default=%s)", defaultValue = "sudo")
    private String becomeMethod;

    @Option(names = "--become-user", description = "Run operations as this user (default=%s)", defaultValue = "root")
    private String becomeUser;

    @Option(names = "--become-flags", description = "Privilege escalation flags to use")
    private String becomeFlags;

    @Option(names = {"-K", "--ask-become-pass"}, description = "Ask for privilege escalation password")
    private boolean askBecomePass;

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
            Playbook playbook = yamlParser.parse(playbookFile);

            // Load Inventory using InventoryManager
            Inventory inventory;
            if (inventoryPath != null) {
                InventoryManager inventoryManager = new InventoryManager();
                inventoryManager.addProvider(new ScriptInventoryProvider());
                inventoryManager.addProvider(new FileInventoryProvider());

                inventory = inventoryManager.loadInventory(List.of(inventoryPath));
            } else {
                // Default or empty inventory if not provided
                System.err.println("No inventory provided. Execution might skip hosts.");
                return 1;
            }

            // Setup TaskExecutor with standard modules
            try (TaskExecutor taskExecutor = new TaskExecutor()) {
                registerStandardModules(taskExecutor);

                // Parse extra-vars
                Map<String, Object> parsedExtraVars = parseExtraVars(extraVars);

                // Execute Playbook
                PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
                executor.setForks(forks);

                // Select Callback Plugin
                String callbackName = System.getenv("ANSIBLE_STDOUT_CALLBACK");
                if ("json".equalsIgnoreCase(callbackName)) {
                    executor.clearCallbacks();
                    executor.addCallback(new org.example.ansible.engine.JsonCallback());
                } else if (callbackName != null && !"default".equalsIgnoreCase(callbackName)) {
                    System.err.println("Warning: Unknown callback plugin '" + callbackName + "'. Using 'default'.");
                }

                java.nio.file.Path baseDir = playbookFile.getAbsoluteFile().getParentFile().toPath();

                java.nio.file.Path inventoryDirPath = null;
                if (inventoryPath != null) {
                    File invFile = new File(inventoryPath);
                    if (invFile.isDirectory()) {
                        inventoryDirPath = invFile.getAbsoluteFile().toPath();
                    } else {
                        File parent = invFile.getAbsoluteFile().getParentFile();
                        if (parent != null) {
                            inventoryDirPath = parent.toPath();
                        }
                    }
                }

                Map<String, Object> cliVars = new HashMap<>();
                cliVars.put("ansible_check_mode", check);
                cliVars.put("ansible_diff_mode", diff);
                cliVars.put("ansible_become", become);
                cliVars.put("ansible_become_method", becomeMethod);
                cliVars.put("ansible_become_user", becomeUser);
                if (becomeFlags != null) {
                    cliVars.put("ansible_become_flags", becomeFlags);
                }

                if (askBecomePass) {
                    java.io.Console console = System.console();
                    if (console != null) {
                        String password = new String(console.readPassword("BECOME password: "));
                        cliVars.put("ansible_become_password", password);
                    } else {
                        System.err.println("Error: --ask-become-pass specified but no console available.");
                        return 1;
                    }
                }

                cliVars.put("ansible_verbosity", verbosity);
                cliVars.put("ansible_run_tags", tags);
                cliVars.put("ansible_skip_tags", skipTags);

                VariableManager variableManager = new VariableManager(inventory, cliVars, parsedExtraVars, baseDir, inventoryDirPath);
                executor.execute(playbook, inventory, variableManager, check, tags, skipTags, limit);

                return 0;
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbosity > 1) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExtraVars(List<String> extraVars) {
        Map<String, Object> result = new HashMap<>();
        Yaml yaml = new Yaml();
        for (String var : extraVars) {
            String trimmedVar = var.trim();
            if (trimmedVar.startsWith("@")) {
                String filePath = trimmedVar.substring(1);
                File file = new File(filePath);
                try (InputStream is = new FileInputStream(file)) {
                    Object data = yaml.load(is);
                    if (data instanceof Map) {
                        result.putAll((Map<String, Object>) data);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load extra-vars from file: " + filePath, e);
                }
            } else if (trimmedVar.startsWith("{")) {
                Object data = yaml.load(trimmedVar);
                if (data instanceof Map) {
                    result.putAll((Map<String, Object>) data);
                }
            } else if (trimmedVar.contains("=")) {
                String[] parts = trimmedVar.split("=", 2);
                result.put(parts[0].trim(), parts[1].trim());
            } else {
                try {
                    Object data = yaml.load(trimmedVar);
                    if (data instanceof Map) {
                        result.putAll((Map<String, Object>) data);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    private void registerStandardModules(TaskExecutor executor) {
        // debug is now mostly handled by Action Plugin (DebugAction), but we keep this as a fallback for direct module calls if needed.
        executor.registerModule("debug", (args, becomeContext, context) -> {
            Object msg = args.getOrDefault("msg", "Hello world");
            System.out.println("DEBUG: " + msg);
            return TaskResult.success(false, Map.of("msg", msg));
        });

        // command, shell, and setup are now registered by default in TaskExecutor constructor.

        executor.registerModule("file", new PythonModule("ansible.builtin.file"));
        executor.registerModule("copy", new PythonModule("ansible.builtin.copy"));
    }

    // Getters for testing
    public File getPlaybook() { return playbookFile; }
    public String getInventory() { return inventoryPath; }
    public int getForks() { return forks; }
    public List<String> getExtraVars() { return extraVars; }
    public int getVerbose() { return verbose == null ? 0 : verbose.length; }
    public String getLimit() { return limit; }
    public List<String> getTags() { return tags; }
    public boolean isCheck() { return check; }
}
