package org.example.ansible.engine;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of Callback that prints execution progress to standard output.
 */
public class DefaultCallback implements Callback {

    private static final int LINE_WIDTH = 80;
    private final Set<Object> printedTasks = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized void v2_playbook_on_start(Playbook playbook) {
        // Usually Ansible doesn't print anything here unless it's a header.
    }

    @Override
    public synchronized void v2_playbook_on_play_start(Play play) {
        printedTasks.clear();
        System.out.println();
        String title = "PLAY [" + (play.name() != null ? play.name() : play.hosts()) + "]";
        printHeader(title);
    }

    @Override
    public synchronized void v2_playbook_on_task_start(Task task, boolean isConditional) {
        if (printedTasks.add(task)) {
            System.out.println();
            String title = "TASK [" + (task.name() != null ? task.name() : task.action()) + "]";
            printHeader(title);
        }
    }

    @Override
    public synchronized void v2_runner_on_ok(Task task, String host, TaskResult result) {
        if (isLoop(result)) {
            printLoopResults("ok", host, result);
        } else {
            if (result.changed()) {
                System.out.println("changed: [" + host + "]");
            } else {
                System.out.println("ok: [" + host + "]");
            }
        }
    }

    @Override
    public synchronized void v2_runner_on_failed(Task task, String host, TaskResult result, boolean ignoreErrors) {
        if (isLoop(result)) {
            printLoopResults("failed", host, result);
            if (ignoreErrors) {
                System.out.println("...ignoring");
            }
        } else {
            String msg = ignoreErrors ? "...ignoring" : "fatal: [" + host + "]: FAILED! => " + result.data();
            System.out.println(msg);
        }
    }

    @Override
    public synchronized void v2_runner_on_skipped(Task task, String host, TaskResult result) {
        if (isLoop(result)) {
            printLoopResults("skipping", host, result);
        } else {
            System.out.println("skipping: [" + host + "]");
        }
    }

    @Override
    public synchronized void v2_runner_on_unreachable(Task task, String host, TaskResult result) {
        if (isLoop(result)) {
            printLoopResults("unreachable", host, result);
        } else {
            System.out.println("fatal: [" + host + "]: UNREACHABLE! => " + result.data());
        }
    }

    private boolean isLoop(TaskResult result) {
        return result.data() != null && result.data().get("results") instanceof java.util.List;
    }

    @SuppressWarnings("unchecked")
    private void printLoopResults(String status, String host, TaskResult result) {
        java.util.List<java.util.Map<String, Object>> results = (java.util.List<java.util.Map<String, Object>>) result.data().get("results");
        for (java.util.Map<String, Object> iteration : results) {
            Object label = iteration.get("_ansible_item_label");
            if (label == null) {
                label = iteration.get("item");
            }

            String prefix = status;
            if ("ok".equals(status) && Boolean.TRUE.equals(iteration.get("changed"))) {
                prefix = "changed";
            } else if ("failed".equals(status)) {
                prefix = "fatal";
            }

            if ("fatal".equals(prefix)) {
                System.out.println(prefix + ": [" + host + "]: FAILED! => (item=" + label + ") => " + iteration);
            } else if ("unreachable".equals(prefix)) {
                System.out.println("fatal: [" + host + "]: UNREACHABLE! => (item=" + label + ") => " + iteration);
            } else if ("skipping".equals(prefix)) {
                System.out.println("skipping: [" + host + "] => (item=" + label + ")");
            } else {
                System.out.println(prefix + ": [" + host + "] => (item=" + label + ")");
            }
        }
    }

    @Override
    public synchronized void v2_playbook_on_handler_stats(String handlerName) {
        if (printedTasks.add("handler:" + handlerName)) {
            System.out.println();
            String title = "RUNNING HANDLER [" + handlerName + "]";
            printHeader(title);
        }
    }

    @Override
    public synchronized void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats) {
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
