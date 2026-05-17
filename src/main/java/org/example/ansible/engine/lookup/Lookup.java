package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import java.util.List;
import java.util.Map;

/**
 * Interface for Ansible-compatible lookup plugins.
 */
public interface Lookup {
    /**
     * Executes the lookup.
     *
     * @param interpreter The current Jinjava interpreter.
     * @param terms       The terms passed to the lookup.
     * @param kwargs      The keyword arguments passed to the lookup.
     * @return A list of result items.
     */
    List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs);

    /**
     * Returns the name of the lookup plugin.
     *
     * @return The lookup plugin name.
     */
    String getName();
}
