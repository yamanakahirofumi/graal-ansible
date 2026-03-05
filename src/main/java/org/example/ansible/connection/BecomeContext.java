package org.example.ansible.connection;

/**
 * Context for privilege escalation (become).
 *
 * @param become       Whether to enable privilege escalation.
 * @param becomeMethod The privilege escalation method (e.g., sudo, su).
 * @param becomeUser   The user to become.
 * @param becomeFlags  Additional flags for privilege escalation.
 */
public record BecomeContext(
        boolean become,
        String becomeMethod,
        String becomeUser,
        String becomeFlags
) {
    /**
     * Creates a default BecomeContext with privilege escalation disabled.
     * @return A default BecomeContext.
     */
    public static BecomeContext empty() {
        return new BecomeContext(false, "sudo", "root", "");
    }
}
