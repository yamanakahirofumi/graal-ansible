package org.example.ansible.engine;

import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AsyncIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setup() {
        AsyncJobManager.setAsyncDir(tempDir.resolve(".ansible_async").toString());
    }

    @Test
    public void testAsyncWithPoll() throws IOException {
        String playbookYaml = """
                - name: Async test with poll
                  hosts: localhost
                  tasks:
                    - name: Sleep async
                      command: sleep 2
                      async: 10
                      poll: 1
                      register: async_result
                """;
        Path playbookFile = tempDir.resolve("playbook.yml");
        Files.writeString(playbookFile, playbookYaml);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(playbookFile.toFile());

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("localhost", "all");
        inventory.getHost("localhost").ifPresent(h -> h.variables().put("ansible_connection", "local"));

        try (TaskExecutor taskExecutor = new TaskExecutor()) {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
            VariableManager vm = new VariableManager(inventory, Map.of());

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory, vm, false);

            List<TaskResult> hostResults = results.get("localhost");
            assertNotNull(hostResults);
            assertFalse(hostResults.isEmpty());

            TaskResult lastResult = hostResults.get(hostResults.size() - 1);
            assertTrue(lastResult.success(), "Task should succeed: " + lastResult.message());

            // Check that it contains result from command
            Map<String, Object> data = lastResult.data();
            assertTrue(data.containsKey("ansible_job_id"));
        }
    }

    @Test
    public void testAsyncWithPollZeroAndStatusCheck() throws IOException {
        String playbookYaml = """
                - name: Async test with poll 0
                  hosts: localhost
                  tasks:
                    - name: Sleep async
                      command: sleep 2
                      async: 10
                      poll: 0
                      register: async_job
                    - name: Check status
                      async_status:
                        jid: "{{ async_job.ansible_job_id }}"
                      register: job_result
                      until: job_result.finished == 1
                      retries: 10
                      delay: 1
                """;
        Path playbookFile = tempDir.resolve("playbook.yml");
        Files.writeString(playbookFile, playbookYaml);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(playbookFile.toFile());

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("localhost", "all");
        inventory.getHost("localhost").ifPresent(h -> h.variables().put("ansible_connection", "local"));

        try (TaskExecutor taskExecutor = new TaskExecutor()) {
            // async_status is now registered in registerStandardModules,
            // but we need it here since we are using taskExecutor directly
            taskExecutor.registerModule("async_status", (args, becomeContext, context) -> {
                String jid = (String) args.get("jid");
                Map<String, Object> status = AsyncJobManager.getInstance().getJobStatus(jid);
                return TaskResult.success(status);
            });

            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
            VariableManager vm = new VariableManager(inventory, Map.of());

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory, vm, false);

            List<TaskResult> hostResults = results.get("localhost");
            assertNotNull(hostResults);

            TaskResult lastResult = hostResults.get(hostResults.size() - 1);
            assertTrue(lastResult.success(), "Status check should succeed");
            Map<String, Object> data = lastResult.data();
            assertEquals(1, data.get("finished"));
        }
    }
}
