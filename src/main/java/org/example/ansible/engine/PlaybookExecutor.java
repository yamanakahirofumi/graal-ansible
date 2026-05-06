package org.example.ansible.engine;

import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.DefaultConnectionFactory;
import org.example.ansible.inventory.Inventory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PlaybookExecutor (PE) manages the overall execution of a playbook.
 * It parses the playbook YAML and coordinates with the TaskQueueManager (TQM).
 * It operates on the Control Node (管理ノード).
 */
public class PlaybookExecutor {

    private final ITaskExecutor taskExecutor;
    private final ConnectionFactory connectionFactory;
    private PromptProvider promptProvider = new ConsolePromptProvider();

    public PlaybookExecutor(ITaskExecutor taskExecutor) {
        this(taskExecutor, new DefaultConnectionFactory());
    }

    public PlaybookExecutor(ITaskExecutor taskExecutor, ConnectionFactory connectionFactory) {
        this.taskExecutor = taskExecutor;
        this.connectionFactory = connectionFactory;
    }

    public void setPromptProvider(PromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    /**
     * Executes the entire playbook.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory) {
        return execute(playbook, inventory, Map.of(), null, false);
    }

    /**
     * Executes the entire playbook with extra variables.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @param extraVars Extra variables provided from outside.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars) {
        return execute(playbook, inventory, extraVars, null, false);
    }

    /**
     * Executes the entire playbook with extra variables and a base directory for file resolution.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @param extraVars Extra variables provided from outside.
     * @param baseDir   The base directory for resolving relative paths (e.g., vars_files).
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars, Path baseDir) {
        return execute(playbook, inventory, extraVars, baseDir, false);
    }

    /**
     * Executes the entire playbook with extra variables and a base directory for file resolution.
     *
     * @param playbook        The playbook to execute.
     * @param inventory       The inventory to use.
     * @param extraVars       Extra variables provided from outside.
     * @param baseDir         The base directory for resolving relative paths (e.g., vars_files).
     * @param globalCheckMode Whether the execution is in global check mode.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars, Path baseDir, boolean globalCheckMode) {
        return execute(playbook, inventory, extraVars, baseDir, null, globalCheckMode);
    }

    /**
     * Executes the entire playbook with extra variables, a base directory, and an inventory directory.
     *
     * @param playbook        The playbook to execute.
     * @param inventory       The inventory to use.
     * @param extraVars       Extra variables provided from outside.
     * @param baseDir         The base directory for resolving relative paths (e.g., vars_files).
     * @param inventoryDir    The directory containing the inventory file.
     * @param globalCheckMode Whether the execution is in global check mode.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars, Path baseDir, Path inventoryDir, boolean globalCheckMode) {
        Map<String, Object> cliVars = new HashMap<>();
        cliVars.put("ansible_check_mode", globalCheckMode);
        // Additional CLI-relevant variables could be added here in the future
        return execute(playbook, inventory, new VariableManager(inventory, cliVars, extraVars, baseDir, inventoryDir), globalCheckMode);
    }

    /**
     * Executes the entire playbook with a pre-configured VariableManager.
     *
     * @param playbook        The playbook to execute.
     * @param inventory       The inventory to use.
     * @param variableManager The variable manager to use.
     * @param globalCheckMode Whether the execution is in global check mode.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, VariableManager variableManager, boolean globalCheckMode) {
        return execute(playbook, inventory, variableManager, globalCheckMode, List.of(), List.of(), null);
    }

    /**
     * Executes the entire playbook with tags and limit filtering.
     *
     * @param playbook        The playbook to execute.
     * @param inventory       The inventory.
     * @param variableManager The variable manager to use.
     * @param globalCheckMode Whether the execution is in global check mode.
     * @param runTags          The tags to run.
     * @param skipTags         The tags to skip.
     * @param limit            The host limit pattern.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, VariableManager variableManager, boolean globalCheckMode, List<String> runTags, List<String> skipTags, String limit) {
        Map<String, List<TaskResult>> results = new HashMap<>();

        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, connectionFactory);
        tqm.setPromptProvider(promptProvider);

        for (Play play : playbook.plays()) {
            tqm.executePlay(play, inventory, variableManager, results, globalCheckMode, runTags, skipTags, limit);
        }

        return results;
    }
}
