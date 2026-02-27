package org.example.ansible.parser;

import org.example.ansible.engine.Play;
import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.Task;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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
}
