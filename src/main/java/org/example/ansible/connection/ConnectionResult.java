package org.example.ansible.connection;

import java.io.Serializable;

/**
 * Represents the result of a command execution via a connection plugin.
 */
public final class ConnectionResult implements Serializable {
    private final String stdout;
    private final String stderr;
    private final int exitCode;

    public ConnectionResult(String stdout, String stderr, int exitCode) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    // For compatibility with Record-style accessors
    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    @Override
    public String toString() {
        return "ConnectionResult[stdout=" + stdout + ", stderr=" + stderr + ", exitCode=" + exitCode + "]";
    }
}
