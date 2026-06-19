package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoopCallbackIntegrationTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    public void restoreStreams() {
        System.setOut(originalOut);
    }

    @Test
    void testLoopOutputWithLabels() {
        String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  tasks:
                    - name: Loop with labels
                      debug:
                        msg: "Hello {{ item }}"
                      loop:
                        - apple
                        - banana
                      loop_control:
                        label: "FRUIT: {{ item }}"
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
            executor.clearCallbacks();
            executor.addCallback(new DefaultCallback());

            executor.execute(playbook, inventory);

            String output = outContent.toString();
            // Verify output contains the labels
            assertTrue(output.contains("ok: [localhost] => (item=FRUIT: apple)"), "Output should contain first label. Output: " + output);
            assertTrue(output.contains("ok: [localhost] => (item=FRUIT: banana)"), "Output should contain second label. Output: " + output);
        } finally {
            taskExecutor.close();
        }
    }

    @Test
    void testFailedLoopOutput() {
        String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  tasks:
                    - name: Failing loop
                      fail:
                        msg: "Failed on {{ item }}"
                      loop:
                        - fail_me
                      ignore_errors: yes
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
            executor.clearCallbacks();
            executor.addCallback(new DefaultCallback());

            executor.execute(playbook, inventory);

            String output = outContent.toString();
            assertTrue(output.contains("fatal: [localhost]: FAILED! => (item=fail_me)"), "Output should contain failure with item. Output: " + output);
            assertTrue(output.contains("...ignoring"), "Output should contain ignore message");
        } finally {
            taskExecutor.close();
        }
    }
}
