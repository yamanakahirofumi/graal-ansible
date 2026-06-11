package org.example.ansible.engine;

import java.util.Map;
import java.util.TreeMap;

/**
 * Default implementation of Callback that prints execution progress to standard output.
 */
public class DefaultCallback implements Callback {

    private static final int LINE_WIDTH = 80;

    @Override
    public void v2_playbook_on_start(Playbook playbook) {
        // Usually Ansible doesn't print anything here unless it's a header.
    }

    @Override
    public void v2_playbook_on_play_start(Play play) {
        System.out.println();
        String title = "PLAY [" + (play.name() != null ? play.name() : play.hosts()) + "]";
        printHeader(title);
    }

    @Override
    public void v2_playbook_on_task_start(Task task, boolean isConditional) {
        System.out.println();
        String title = "TASK [" + (task.name() != null ? task.name() : task.action()) + "]";
        printHeader(title);
    }

    @Override
    public void v2_runner_on_ok(String host, TaskResult result) {
        if (result.changed()) {
            System.out.println("changed: [" + host + "]");
        } else {
            System.out.println("ok: [" + host + "]");
        }
    }

    @Override
    public void v2_runner_on_failed(String host, TaskResult result, boolean ignoreErrors) {
        String msg = ignoreErrors ? "...ignoring" : "fatal: [" + host + "]: FAILED! => " + result.data();
        System.out.println(msg);
    }

    @Override
    public void v2_runner_on_skipped(String host, TaskResult result) {
        System.out.println("skipping: [" + host + "]");
    }

    @Override
    public void v2_runner_on_unreachable(String host, TaskResult result) {
        System.out.println("fatal: [" + host + "]: UNREACHABLE! => " + result.data());
    }

    @Override
    public void v2_playbook_on_handler_stats(String handlerName) {
        System.out.println();
        String title = "RUNNING HANDLER [" + handlerName + "]";
        printHeader(title);
    }

    @Override
    public void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
        System.out.println();
        printHeader("PLAY RECAP");

        // Sort by host name for consistent output
        TreeMap<String, Map<String, Integer>> sortedStats = new TreeMap<>(stats);

        for (Map.Entry<String, Map<String, Integer>> entry : sortedStats.entrySet()) {
            String host = entry.getKey();
            Map<String, Integer> s = entry.getValue();

            System.out.printf("%-26s : ok=%-5d changed=%-5d unreachable=%-5d failed=%-5d skipped=%-5d%n",
                    host,
                    s.getOrDefault("ok", 0),
                    s.getOrDefault("changed", 0),
                    s.getOrDefault("unreachable", 0),
                    s.getOrDefault("failed", 0),
                    s.getOrDefault("skipped", 0));
        }
    }

    private void printHeader(String title) {
        StringBuilder sb = new StringBuilder(title);
        sb.append(" ");
        while (sb.length() < LINE_WIDTH) {
            sb.append("*");
        }
        System.out.println(sb.toString());
    }
}
