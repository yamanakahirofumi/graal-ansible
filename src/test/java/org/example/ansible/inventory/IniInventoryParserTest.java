package org.example.ansible.inventory;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IniInventoryParserTest {

    @Test
    void testSimpleHosts() {
        String ini = """
                host1
                host2
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, inventory.all().hosts().size());
        assertTrue(inventory.all().hosts().stream().anyMatch(h -> h.name().equals("host1")));
        assertTrue(inventory.all().hosts().stream().anyMatch(h -> h.name().equals("host2")));
    }

    @Test
    void testGroupedHosts() {
        String ini = """
                [web]
                web1
                web2
                
                [db]
                db1
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        List<Group> groups = inventory.all().children();
        assertTrue(groups.stream().anyMatch(g -> g.name().equals("web")));
        assertTrue(groups.stream().anyMatch(g -> g.name().equals("db")));

        Group webGroup = groups.stream().filter(g -> g.name().equals("web")).findFirst().orElseThrow();
        assertEquals(2, webGroup.hosts().size());
        assertTrue(webGroup.hosts().stream().anyMatch(h -> h.name().equals("web1")));

        Group dbGroup = groups.stream().filter(g -> g.name().equals("db")).findFirst().orElseThrow();
        assertEquals(1, dbGroup.hosts().size());
        assertTrue(dbGroup.hosts().stream().anyMatch(h -> h.name().equals("db1")));
    }

    @Test
    void testChildrenGroups() {
        String ini = """
                [web]
                web1
                
                [db]
                db1
                
                [datacenter:children]
                web
                db
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        Group dcGroup = findGroup(inventory.all(), "datacenter");
        assertNotNull(dcGroup);
        assertEquals(2, dcGroup.children().size());
        assertTrue(dcGroup.children().stream().anyMatch(g -> g.name().equals("web")));
        assertTrue(dcGroup.children().stream().anyMatch(g -> g.name().equals("db")));
    }

    @Test
    void testGroupVars() {
        String ini = """
                [web]
                web1
                
                [web:vars]
                http_port=80
                ansible_user=admin
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        Group webGroup = findGroup(inventory.all(), "web");
        assertNotNull(webGroup);
        Map<String, Object> vars = webGroup.variables();
        assertEquals("80", vars.get("http_port"));
        assertEquals("admin", vars.get("ansible_user"));
    }

    @Test
    void testHostVars() {
        String ini = """
                host1 ansible_ssh_host=192.168.1.1 ansible_port=22
                host2 key1=val1
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        Host host1 = inventory.all().hosts().stream().filter(h -> h.name().equals("host1")).findFirst().orElseThrow();
        assertEquals("192.168.1.1", host1.variables().get("ansible_ssh_host"));
        assertEquals("22", host1.variables().get("ansible_port"));

        Host host2 = inventory.all().hosts().stream().filter(h -> h.name().equals("host2")).findFirst().orElseThrow();
        assertEquals("val1", host2.variables().get("key1"));
    }

    @Test
    void testVariableResolutionHierarchy() {
        String ini = """
                [all:vars]
                level=all
                common=all
                
                [parent]
                host1
                
                [parent:vars]
                level=parent
                parent_var=parent
                
                [child]
                host1
                
                [child:vars]
                level=child
                child_var=child
                
                [parent:children]
                child
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> host1Vars = inventory.getVariablesForHost("host1");
        
        assertEquals("child", host1Vars.get("level")); // child overrides parent and all
        assertEquals("all", host1Vars.get("common"));
        assertEquals("parent", host1Vars.get("parent_var"));
        assertEquals("child", host1Vars.get("child_var"));
    }

    @Test
    void testDeepHierarchy() {
        String ini = """
                [g1]
                h1
                [g2:children]
                g1
                [g3:children]
                g2
                [g4:children]
                g3
                """;
        IniInventoryParser parser = new IniInventoryParser();
        Inventory inventory = parser.parse(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)));

        Group g4 = findGroup(inventory.all(), "g4");
        assertNotNull(g4);
        Group g3 = g4.children().get(0);
        assertEquals("g3", g3.name());
        Group g2 = g3.children().get(0);
        assertEquals("g2", g2.name());
        Group g1 = g2.children().get(0);
        assertEquals("g1", g1.name());
        assertEquals("h1", g1.hosts().get(0).name());
    }

    private Group findGroup(Group root, String name) {
        if (root.name().equals(name)) return root;
        for (Group child : root.children()) {
            Group found = findGroup(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
