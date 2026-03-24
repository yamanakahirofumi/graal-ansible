package org.example.ansible.module;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SetupModuleTest {

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        TaskExecutor.setCurrentConnection(new LocalConnection());
    }

    @AfterEach
    void tearDown() {
        TaskExecutor.clearCurrentConnection();
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSetupModule() {
        SetupModule setupModule = new SetupModule();
        TaskResult result = setupModule.execute(Map.of(), null, null);

        assertTrue(result.success());
        assertFalse(result.changed());

        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);

        // Basic facts should be present on most systems
        assertTrue(facts.containsKey("ansible_system"));
        assertTrue(facts.containsKey("ansible_architecture"));
        assertTrue(facts.containsKey("ansible_hostname"));

        System.out.println("System: " + facts.get("ansible_system"));
        System.out.println("OS Family: " + facts.get("ansible_os_family"));
        System.out.println("Distribution: " + facts.get("ansible_distribution"));
    }
}
