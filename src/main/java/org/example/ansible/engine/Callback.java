package org.example.ansible.engine;

import java.util.Map;

/**
 * Callback interface for handling execution events.
 * Method names are based on Ansible v2 API.
 */
public interface Callback {
    /** Called when Playbook execution starts */
    void v2_playbook_on_start(Playbook playbook);

    /** Called when a Play starts */
    void v2_playbook_on_play_start(Play play);

    /** Called when a Task starts */
    void v2_playbook_on_task_start(Task task, boolean isConditional);

    /** Called when a Task execution is successful (ok) */
    void v2_runner_on_ok(Task task, String host, TaskResult result);

    /** Called when a Task execution fails (failed) */
    void v2_runner_on_failed(Task task, String host, TaskResult result, boolean ignoreErrors);

    /** Called when a Task execution is skipped (skipped) */
    void v2_runner_on_skipped(Task task, String host, TaskResult result);

    /** Called when a host is unreachable */
    void v2_runner_on_unreachable(Task task, String host, TaskResult result);

    /** Called when a handler starts */
    void v2_playbook_on_handler_stats(String handlerName);

    /** Called at the end of execution to provide final statistics */
    void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats);
}
