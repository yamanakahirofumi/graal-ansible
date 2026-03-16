package org.example.ansible.engine;

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

    public PlaybookExecutor(ITaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
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
        Map<String, List<TaskResult>> results = new HashMap<>();
        VariableManager variableManager = new VariableManager(inventory, extraVars, baseDir);
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor);

        for (Play play : playbook.plays()) {
            tqm.executePlay(play, inventory, variableManager, results, globalCheckMode);
        }

        return results;
    }
}
