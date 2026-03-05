package org.example.ansible.parser;

import org.example.ansible.engine.Play;
import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.Task;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class YamlParserTest {

    @Test
    void testSimplePlaybookParsing() {
        String yaml = """
                - name: test play
                  hosts: localhost
                  tasks:
                    - name: hello task
                      debug:
                        msg: "hello world"
                    - name: shell task
                      shell: echo hi
                """;
        InputStream inputStream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(inputStream);

        assertNotNull(playbook);
        assertEquals(1, playbook.plays().size());

        Play play = playbook.plays().get(0);
        assertEquals("test play", play.name());
        assertEquals("localhost", play.hosts());
        assertEquals(2, play.tasks().size());

        Task task1 = play.tasks().get(0);
        assertEquals("hello task", task1.name());
        assertEquals("debug", task1.action());
        assertEquals("hello world", task1.args().get("msg"));

        Task task2 = play.tasks().get(1);
        assertEquals("shell task", task2.name());
        assertEquals("shell", task2.action());
        assertEquals("echo hi", task2.args().get("_raw_params"));
    }

    @Test
    void testReservedKeywordsParsing() {
        String yaml = """
                - name: reserved keywords play
                  hosts: localhost
                  tasks:
                    - name: full task
                      shell: echo hi
                      ignore_errors: true
                      ignore_unreachable: true
                      delegate_to: otherhost
                      delegate_facts: true
                      run_once: true
                """;
        InputStream inputStream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(inputStream);

        Task task = playbook.plays().get(0).tasks().get(0);
        assertEquals("full task", task.name());
        assertEquals("shell", task.action());
        assertTrue(task.ignoreErrors());
        assertTrue(task.ignoreUnreachable());
        assertEquals("otherhost", task.delegateTo());
        assertTrue(task.delegateFacts());
        assertTrue(task.runOnce());
    }

    @Test
    void testInvalidTaskParsing() {
        String yaml = """
                - name: invalid play
                  hosts: localhost
                  tasks:
                    - name: task without action
                """;
        InputStream inputStream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        YamlParser parser = new YamlParser();

        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(inputStream);
        });
    }

    @Test
    void testComprehensiveParsing() {
        String yaml = """
                - name: comprehensive play
                  hosts: all
                  become: true
                  become_method: sudo
                  become_user: root
                  become_flags: "-H"
                  vars:
                    play_var: value
                  vars_files:
                    - vars.yml
                  tasks:
                    - name: complex task
                      command: /usr/bin/uptime
                      vars:
                        task_var: tvalue
                      when: ansible_os_family == "RedHat"
                      register: uptime_result
                      loop: [1, 2, 3]
                      notify:
                        - restart service
                      failed_when: uptime_result.rc != 0
                      changed_when: false
                      ignore_errors: true
                      ignore_unreachable: true
                      until: uptime_result is success
                      retries: 5
                      delay: 10
                      delegate_to: localhost
                      delegate_facts: true
                      run_once: true
                      become: false
                    - name: block task
                      block:
                        - debug: msg="in block"
                      rescue:
                        - debug: msg="in rescue"
                      always:
                        - debug: msg="in always"
                  handlers:
                    - name: restart service
                      shell: service restart
                """;
        InputStream inputStream = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(inputStream);

        Play play = playbook.plays().get(0);
        assertEquals("comprehensive play", play.name());
        assertEquals("all", play.hosts());
        assertEquals(true, play.become());
        assertEquals("sudo", play.becomeMethod());
        assertEquals("root", play.becomeUser());
        assertEquals("-H", play.becomeFlags());
        assertEquals("value", play.vars().get("play_var"));
        assertEquals(1, play.varsFiles().size());
        assertEquals("vars.yml", play.varsFiles().get(0));
        assertEquals(1, play.handlers().size());
        assertEquals("restart service", play.handlers().get(0).name());

        Task task = play.tasks().get(0);
        assertEquals("complex task", task.name());
        assertEquals("command", task.action());
        assertEquals("/usr/bin/uptime", task.args().get("_raw_params"));
        assertEquals("tvalue", task.vars().get("task_var"));
        assertEquals("ansible_os_family == \"RedHat\"", task.when());
        assertEquals("uptime_result", task.register());
        assertEquals(List.of(1, 2, 3), task.loop());
        assertEquals(List.of("restart service"), task.notifications());
        assertEquals("uptime_result.rc != 0", task.failedWhen());
        assertEquals(false, task.changedWhen());
        assertTrue(task.ignoreErrors());
        assertTrue(task.ignoreUnreachable());
        assertEquals("uptime_result is success", task.until());
        assertEquals(5, task.retries());
        assertEquals(10, task.delay());
        assertEquals("localhost", task.delegateTo());
        assertTrue(task.delegateFacts());
        assertTrue(task.runOnce());
        assertEquals(false, task.become());

        Task blockTask = play.tasks().get(1);
        assertEquals("block task", blockTask.name());
        assertEquals(1, blockTask.block().size());
        assertEquals(1, blockTask.rescue().size());
        assertEquals(1, blockTask.always().size());
        assertEquals("debug", blockTask.block().get(0).action());
    }
}
