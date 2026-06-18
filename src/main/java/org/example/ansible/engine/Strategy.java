package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import java.util.List;
import java.util.Map;

/**
 * Interface for execution strategies.
 */
public interface Strategy {
    /**
     * Executes the specified play.
     *
     * @param play             The play to execute.
     * @param targetHosts      The list of target hosts.
     * @param tqm              The TaskQueueManager.
     * @param variableManager  The variable manager.
     * @param results          The results map.
     * @param globalCheckMode  Whether in global check mode.
     * @param runTags          Tags to run.
     * @param skipTags         Tags to skip.
     */
    void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags);

    /**
     * Returns the name of the strategy.
     */
    String getName();
}
