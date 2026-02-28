package org.example.ansible.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI implementation for graal-ansible, compatible with ansible-playbook.
 */
@Command(name = "graal-ansible", mixinStandardHelpOptions = true, version = "graal-ansible 1.0",
        description = "Runs Ansible playbooks using GraalVM.")
public class PlaybookCli implements Callable<Integer> {

    @Parameters(index = "0", description = "The playbook file to run.")
    private File playbook;

    @Option(names = {"-i", "--inventory"}, description = "Specify inventory host path.")
    private String inventory;

    @Option(names = {"-e", "--extra-vars"}, description = "Set additional variables as key=value or YAML/JSON.")
    private List<String> extraVars = new ArrayList<>();

    @Option(names = {"-v", "--verbose"}, description = "Verbose mode (-v, -vv, -vvv, etc.)")
    private boolean[] verbose;

    @Option(names = {"-l", "--limit"}, description = "Further limit selected hosts to an additional pattern.")
    private String limit;

    @Option(names = {"-t", "--tags"}, description = "Only run plays and tasks tagged with these values.")
    private List<String> tags = new ArrayList<>();

    @Option(names = {"-C", "--check"}, description = "Don't make any changes; instead, try to predict some of the changes that may occur.")
    private boolean check;

    @Override
    public Integer call() {
        int verbosity = verbose == null ? 0 : verbose.length;
        if (verbosity > 0) {
            System.out.printf("Running playbook: %s%n", playbook);
            if (inventory != null) {
                System.out.printf("Inventory: %s%n", inventory);
            }
            if (!extraVars.isEmpty()) {
                System.out.printf("Extra vars: %s%n", extraVars);
            }
            System.out.printf("Verbosity level: %d%n", verbosity);
        }

        // TODO: Integrate with PlaybookExecutor when implemented.
        System.out.println("Playbook execution not yet fully implemented.");

        return 0;
    }

    // Getters for testing
    public File getPlaybook() { return playbook; }
    public String getInventory() { return inventory; }
    public List<String> getExtraVars() { return extraVars; }
    public int getVerbose() { return verbose == null ? 0 : verbose.length; }
    public String getLimit() { return limit; }
    public List<String> getTags() { return tags; }
    public boolean isCheck() { return check; }
}
