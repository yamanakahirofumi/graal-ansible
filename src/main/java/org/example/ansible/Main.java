package org.example.ansible;

import org.example.ansible.cli.PlaybookCli;
import picocli.CommandLine;

/**
 * Entry point for the graal-ansible application.
 */
public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new PlaybookCli()).execute(args);
        System.exit(exitCode);
    }
}
