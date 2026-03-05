package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.example.ansible.util.OSHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BecomeTest {

    @Test
    void testBecomeResolution() {
        String yaml = """
            - name: Play with become
              hosts: all
              become: yes
              become_user: admin
              tasks:
                - name: Task without override
                  debug:
                    msg: hello
                - name: Task with override
                  debug:
                    msg: world
                  become: no
                - name: Task with user override
                  debug:
                    msg: override
                  become_user: root
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        when(taskExecutor.getOsHandler()).thenReturn(mock(OSHandler.class));
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        Inventory inventory = mock(Inventory.class);
        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        when(inventory.all()).thenReturn(allGroup);
        when(inventory.getVariablesForHost(anyString())).thenReturn(Map.of());

        playbookExecutor.execute(playbook, inventory);

        ArgumentCaptor<BecomeContext> contextCaptor = ArgumentCaptor.forClass(BecomeContext.class);
        verify(taskExecutor, times(3)).execute(any(Task.class), contextCaptor.capture());

        List<BecomeContext> contexts = contextCaptor.getAllValues();

        // Task 1: inherits from play
        assertTrue(contexts.get(0).become());
        assertEquals("admin", contexts.get(0).becomeUser());

        // Task 2: overrides become: no
        assertFalse(contexts.get(1).become());

        // Task 3: overrides become_user: root
        assertTrue(contexts.get(2).become());
        assertEquals("root", contexts.get(2).becomeUser());
    }

    @Test
    void testBecomeVariableResolution() {
        String yaml = """
            - name: Play with variables
              hosts: all
              become: "{{ use_become }}"
              become_user: "{{ target_user }}"
              vars:
                use_become: yes
                target_user: deploy
              tasks:
                - name: Resolved task
                  debug:
                    msg: hello
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        when(taskExecutor.getOsHandler()).thenReturn(mock(OSHandler.class));
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        Inventory inventory = mock(Inventory.class);
        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        when(inventory.all()).thenReturn(allGroup);
        when(inventory.getVariablesForHost(anyString())).thenReturn(Map.of());

        playbookExecutor.execute(playbook, inventory);

        ArgumentCaptor<BecomeContext> contextCaptor = ArgumentCaptor.forClass(BecomeContext.class);
        verify(taskExecutor).execute(any(Task.class), contextCaptor.capture());

        BecomeContext context = contextCaptor.getValue();
        assertTrue(context.become());
        assertEquals("deploy", context.becomeUser());
    }
}
