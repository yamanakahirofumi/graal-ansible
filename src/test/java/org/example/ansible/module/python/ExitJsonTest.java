package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExitJsonTest {

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testExitJsonStacktrace() {
        String script = """
            from ansible.module_utils.basic import AnsibleModule
            def main():
                module = AnsibleModule(argument_spec={})
                module.exit_json(changed=True, msg="Success")
            if __name__ == '__main__':
                main()
            """;

        taskExecutor.registerModule("exit_module", new PythonModule("exit_module", script));
        Task task = new Task("test", "exit_module", Map.of());

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());
        assertEquals("Success", result.data().get("msg"));
    }

    @Test
    void testFailJsonStacktrace() {
        String script = """
            from ansible.module_utils.basic import AnsibleModule
            def main():
                module = AnsibleModule(argument_spec={})
                module.fail_json(msg="Expected Failure")
            if __name__ == '__main__':
                main()
            """;

        taskExecutor.registerModule("fail_module", new PythonModule("fail_module", script));
        Task task = new Task("test", "fail_module", Map.of());

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), null);

        assertFalse(result.success());
        assertEquals("Expected Failure", result.message());
    }

    @Test
    void testExitJsonStopsExecution() {
        String script = """
            from ansible.module_utils.basic import AnsibleModule
            def main():
                module = AnsibleModule(argument_spec={})
                module.exit_json(changed=True, msg="First")
                module.exit_json(changed=False, msg="Second")
            if __name__ == '__main__':
                main()
            """;

        taskExecutor.registerModule("stop_module", new PythonModule("stop_module", script));
        Task task = new Task("test", "stop_module", Map.of());

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), null);

        assertTrue(result.success());
        assertEquals("First", result.data().get("msg"), "Execution should stop after first exit_json");
    }
}
