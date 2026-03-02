package org.example.ansible.inventory;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlInventoryParserTest {

    private final YamlInventoryParser parser = new YamlInventoryParser();

    @Test
    void testSimpleYamlInventory() {
        String yaml = """
                all:
                  hosts:
                    host1:
                      ansible_host: 192.168.1.1
                    host2:
                  vars:
                    group_var: value
                """;
        Inventory inventory = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertNotNull(inventory);
        assertEquals("all", inventory.all().name());
        assertEquals(2, inventory.all().hosts().size());
        assertEquals("value", inventory.all().variables().get("group_var"));

        Map<String, Object> host1Vars = inventory.getVariablesForHost("host1");
        assertEquals("192.168.1.1", host1Vars.get("ansible_host"));
        assertEquals("value", host1Vars.get("group_var"));
    }

    @Test
    void testNestedGroupsYamlInventory() {
        String yaml = """
                all:
                  children:
                    web:
                      hosts:
                        web1:
                      vars:
                        http_port: 80
                    db:
                      hosts:
                        db1:
                      vars:
                        db_port: 5432
                """;
        Inventory inventory = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, inventory.all().children().size());

        Map<String, Object> web1Vars = inventory.getVariablesForHost("web1");
        assertEquals("80", web1Vars.get("http_port").toString());

        Map<String, Object> db1Vars = inventory.getVariablesForHost("db1");
        assertEquals("5432", db1Vars.get("db_port").toString());
    }

    @Test
    void testDeeplyNestedGroups() {
        String yaml = """
                all:
                  children:
                    production:
                      children:
                        web_prod:
                          hosts:
                            prod-web-1:
                          vars:
                            env: prod
                """;
        Inventory inventory = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> vars = inventory.getVariablesForHost("prod-web-1");
        assertEquals("prod", vars.get("env"));
    }
}
