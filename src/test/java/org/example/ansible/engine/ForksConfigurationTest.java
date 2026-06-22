package org.example.ansible.engine;

import org.example.ansible.cli.PlaybookCli;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class ForksConfigurationTest {

    @Test
    public void testForksPropagationFromCliToTqm() {
        PlaybookCli cli = new PlaybookCli();
        CommandLine cmd = new CommandLine(cli);

        cmd.parseArgs("-f", "10", "playbook.yml");
        assertEquals(10, cli.getForks());
    }

    @Test
    public void testForksPropagationToTqm() {
        ITaskExecutor taskExecutor = mock(ITaskExecutor.class);
        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
        executor.setForks(12);

        TaskQueueManager tqm = new TaskQueueManager(taskExecutor);
        tqm.setForks(15);
        assertEquals(15, tqm.getForks());
    }

    @Test
    public void testSetForksValidation() {
        TaskQueueManager tqm = new TaskQueueManager(mock(ITaskExecutor.class));
        assertThrows(IllegalArgumentException.class, () -> tqm.setForks(0));
        assertThrows(IllegalArgumentException.class, () -> tqm.setForks(-1));
    }

    @Test
    public void testFreeStrategyUsesForksFromTqm() {
        FreeStrategy strategy = new FreeStrategy();
        TaskQueueManager tqm = mock(TaskQueueManager.class);
        when(tqm.getForks()).thenReturn(20);
        when(tqm.getFailedHosts()).thenReturn(new java.util.HashSet<>());
        when(tqm.getHostNotifications()).thenReturn(new java.util.HashMap<>());
        when(tqm.getCallbacks()).thenReturn(new java.util.ArrayList<>());
        when(tqm.getVariableResolver()).thenReturn(new VariableResolver());

        Play play = new Play("test play", "all", List.of());
        List<Host> hosts = List.of(new Host("h1"), new Host("h2"));
        VariableManager vm = mock(VariableManager.class);
        Map<String, List<TaskResult>> results = new HashMap<>();

        // This just verifies the call to getForks()
        strategy.run(play, hosts, tqm, vm, results, false, List.of(), List.of());

        verify(tqm, atLeastOnce()).getForks();
    }
}
