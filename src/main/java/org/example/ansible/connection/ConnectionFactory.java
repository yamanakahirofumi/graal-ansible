package org.example.ansible.connection;

import org.example.ansible.inventory.Host;
import java.util.Map;

/**
 * Factory for creating Connection instances.
 */
public interface ConnectionFactory {
    /**
     * Creates or gets a Connection based on the host and variables.
     *
     * @param host      The target host.
     * @param variables Resolved variables for the host.
     * @return A Connection instance.
     */
    Connection createConnection(Host host, Map<String, Object> variables);
}
