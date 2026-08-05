package org.example.ansible.parser;

import org.example.ansible.engine.Playbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class YamlParserTagTest {

    @Test
    void testUnknownTagParsing() {
        String yaml = """
                - name: play with unknown scalar tag
                  hosts: localhost
                  vars:
                    secret: !unknown_scalar |
                      $ANSIBLE_VAULT;1.1;AES256
                      31323334353637383930
                  tasks:
                    - name: debug secret
                      debug:
                        msg: "{{ secret }}"
                """;
        YamlParser parser = new YamlParser();

        // Verify that unknown tags like !unknown_scalar do not cause ConstructorException
        assertDoesNotThrow(() -> {
            Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            assertNotNull(playbook);
            Object secret = playbook.plays().get(0).vars().get("secret");
            assertNotNull(secret);
            assertTrue(secret instanceof String);
        }, "YamlParser should handle unknown scalar tags");
    }

    @Test
    void testUnknownSequenceAndMappingTags() {
        String yaml = """
                - name: play with unknown tags
                  hosts: localhost
                  vars:
                    my_list: !unknown_seq
                      - item1
                      - item2
                    my_map: !unknown_map
                      key1: val1
                  tasks:
                    - debug: msg="test"
                """;
        YamlParser parser = new YamlParser();
        assertDoesNotThrow(() -> {
            Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            assertNotNull(playbook);
            Object list = playbook.plays().get(0).vars().get("my_list");
            Object map = playbook.plays().get(0).vars().get("my_map");
            assertTrue(list instanceof java.util.List);
            assertTrue(map instanceof java.util.Map);
        });
    }
}
