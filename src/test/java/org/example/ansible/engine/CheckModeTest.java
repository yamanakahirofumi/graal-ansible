package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckModeTest {

    @Test
    void testCheckModePrecedence() {
        List<Task> capturedTasks = new ArrayList<>();
        ITaskExecutor taskExecutor = new ITaskExecutor() {
            @Override
            public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory) {
                // We use TaskExecutor's real logic to resolve check mode, but mock the final execution
                TaskExecutor realExecutor = new TaskExecutor() {
                    @Override
                    public TaskResult execute(Task t, BecomeContext bc, Map<String, String> env) {
                        capturedTasks.add(t);
                        return TaskResult.success(Map.of());
                    }
                };
                return realExecutor.execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);
            }

            @Override
            public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) {
                return TaskResult.success(Map.of());
            }

            @Override
            public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
                return TaskResult.success(Map.of());
            }

            @Override
            public org.example.ansible.util.OSHandler getOsHandler() { return null; }
            @Override
            public void close() {}
        };

        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);
        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));

        // 1. Global check mode only
        Task task1 = new Task("task1", "debug", Map.of());
        Play play1 = new Play("play1", "all", List.of(task1));
        Playbook playbook1 = new Playbook(List.of(play1));

        executor.execute(playbook1, inventory, Map.of(), null, true);
        assertEquals(true, capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 2. Play level overrides global (true)
        Task task2 = new Task("task2", "debug", Map.of());
        Play play2 = new Play("play2", "all", List.of(task2), Map.of(), List.of(), List.of(), null, null, null, null, true, null);
        Playbook playbook2 = new Playbook(List.of(play2));

        executor.execute(playbook2, inventory, Map.of(), null, false);
        assertEquals(true, capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 3. Task level overrides global (false)
        Task task3 = new Task("task3", "debug", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, false, null);
        Play play3 = new Play("play3", "all", List.of(task3));
        Playbook playbook3 = new Playbook(List.of(play3));

        executor.execute(playbook3, inventory, Map.of(), null, true);
        assertNull(capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 4. Task level overrides play level (false)
        Task task4 = new Task("task4", "debug", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, false, null);
        Play play4 = new Play("play4", "all", List.of(task4), Map.of(), List.of(), List.of(), null, null, null, null, true, null);
        Playbook playbook4 = new Playbook(List.of(play4));

        executor.execute(playbook4, inventory, Map.of(), null, false);
        assertNull(capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 5. Block level inheritance
        Task task5 = new Task("task5", "debug", Map.of());
        Task block5 = new Task("block5", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(task5), List.of(), List.of(),
                null, null, null, null, true, null);
        Play play5 = new Play("play5", "all", List.of(block5), Map.of(), List.of(), List.of(), null, null, null, null, false, null);
        Playbook playbook5 = new Playbook(List.of(play5));

        executor.execute(playbook5, inventory, Map.of(), null, false);
        assertEquals(true, capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 6. Task level overrides block level
        Task task6 = new Task("task6", "debug", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, false, null);
        Task block6 = new Task("block6", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(task6), List.of(), List.of(),
                null, null, null, null, true, null);
        Play play6 = new Play("play6", "all", List.of(block6));
        Playbook playbook6 = new Playbook(List.of(play6));

        executor.execute(playbook6, inventory, Map.of(), null, false);
        assertNull(capturedTasks.get(0).args().get("_ansible_check_mode"));

        capturedTasks.clear();

        // 7. Play level templating
        Task task7 = new Task("task7", "debug", Map.of());
        Play play7 = new Play("play7", "all", List.of(task7), Map.of("should_check", true), List.of(), List.of(), null, null, null, null, "{{ should_check }}", null);
        Playbook playbook7 = new Playbook(List.of(play7));

        executor.execute(playbook7, inventory, Map.of(), null, false);
        assertEquals(true, capturedTasks.get(0).args().get("_ansible_check_mode"));
    }
}
