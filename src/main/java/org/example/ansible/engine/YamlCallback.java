package org.example.ansible.engine;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YamlCallback outputs execution task results in pretty structured YAML format.
 */
public class YamlCallback extends DefaultCallback {

    private final Yaml yaml;

    public YamlCallback() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        this.yaml = new Yaml(options);
    }

    @Override
    public synchronized void v2_runner_on_ok(Task task, String host, TaskResult result) {
        String status = result.changed() ? "changed" : "ok";
        System.out.println(status + ": [" + host + "] => \n" + formatYamlData(result));
    }

    @Override
    public synchronized void v2_runner_on_failed(Task task, String host, TaskResult result, boolean ignoreErrors) {
        System.out.println("fatal: [" + host + "]: FAILED! => \n" + formatYamlData(result));
        if (ignoreErrors) {
            System.out.println("...ignoring");
        }
    }

    @Override
    public synchronized void v2_runner_on_skipped(Task task, String host, TaskResult result) {
        System.out.println("skipping: [" + host + "]");
    }

    @Override
    public synchronized void v2_runner_on_unreachable(Task task, String host, TaskResult result) {
        System.out.println("fatal: [" + host + "]: UNREACHABLE! => \n" + formatYamlData(result));
    }

    private String formatYamlData(TaskResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (result.data() != null) {
            data.putAll(result.data());
        }
        data.put("changed", result.changed());
        if (!result.success()) {
            data.put("failed", true);
            if (result.message() != null && !result.message().isEmpty()) {
                data.put("msg", result.message());
            }
        }
        String dumped = yaml.dump(data).trim();
        StringBuilder sb = new StringBuilder();
        for (String line : dumped.split("\r?\n")) {
            sb.append("  ").append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
