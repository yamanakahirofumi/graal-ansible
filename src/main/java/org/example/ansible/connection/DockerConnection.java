package org.example.ansible.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Implementation of Connection for executing commands inside a Docker container.
 */
public class DockerConnection implements Connection {

    private final String containerName;
    private final String dockerUser;

    public DockerConnection(String containerName, String dockerUser) {
        if (containerName == null || containerName.isEmpty()) {
            throw new IllegalArgumentException("Container name/ID must be specified for Docker connection.");
        }
        this.containerName = containerName;
        this.dockerUser = dockerUser;
    }

    public String getContainerName() {
        return containerName;
    }

    public String getDockerUser() {
        return dockerUser;
    }

    // Package-private method to allow overriding/mocking during tests
    Process startProcess(ProcessBuilder pb) throws IOException {
        return pb.start();
    }

    @Override
    public String getTransport() {
        return "docker";
    }

    @Override
    public void connect() {
        // Verify if the container exists and is running using docker inspect
        List<String> cmd = List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            Process process = startProcess(pb);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new UnreachableException("Docker container '" + containerName + "' is not found or Docker daemon is not running.", null);
            }
            String runningState = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!"true".equals(runningState)) {
                throw new UnreachableException("Docker container '" + containerName + "' is not running (state: " + runningState + ").", null);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UnreachableException("Failed to connect to Docker container '" + containerName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public ConnectionResult execCommand(String command, BecomeContext becomeContext, Map<String, String> environment) {
        List<String> commandList = new ArrayList<>();
        commandList.add("docker");
        commandList.add("exec");

        // Pass environment variables via docker exec -e options
        if (environment != null && !environment.isEmpty()) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                commandList.add("-e");
                commandList.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        String userToUse = dockerUser;
        String effectiveCommand = command;

        if (becomeContext != null && becomeContext.become()) {
            String method = becomeContext.becomeMethod();
            if ("sudo".equals(method)) {
                StringBuilder sb = new StringBuilder("sudo -H -S -n -p BECOME-PROMPT ");
                if (becomeContext.becomeUser() != null) {
                    sb.append("-u ").append(becomeContext.becomeUser()).append(" ");
                }
                if (becomeContext.becomeFlags() != null && !becomeContext.becomeFlags().isEmpty()) {
                    sb.append(becomeContext.becomeFlags()).append(" ");
                }
                sb.append("/bin/sh -c '").append(command.replace("'", "'\\''")).append("'");
                effectiveCommand = sb.toString();
            } else if ("su".equals(method)) {
                StringBuilder sb = new StringBuilder("su ");
                if (becomeContext.becomeUser() != null) {
                    sb.append(becomeContext.becomeUser()).append(" ");
                }
                sb.append("-c '").append(command.replace("'", "'\\''")).append("'");
                effectiveCommand = sb.toString();
            } else {
                // Native docker exec -u support
                if (becomeContext.becomeUser() != null) {
                    userToUse = becomeContext.becomeUser();
                } else {
                    userToUse = "root";
                }
            }
        }

        if (userToUse != null && !userToUse.isEmpty()) {
            commandList.add("-u");
            commandList.add(userToUse);
        }

        // Interactive mode if password needs to be prompted/supplied
        boolean interactive = becomeContext != null && becomeContext.become() && becomeContext.becomePassword() != null;
        if (interactive) {
            commandList.add("-i");
        }

        commandList.add(containerName);
        commandList.add("/bin/sh");
        commandList.add("-c");
        commandList.add(effectiveCommand);

        ProcessBuilder pb = new ProcessBuilder(commandList);
        try {
            Process process = startProcess(pb);

            if (interactive) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write((becomeContext.becomePassword() + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            // Read stdout and stderr concurrently to prevent buffer deadlocks
            CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

            int exitCode = process.waitFor();
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();

            return new ConnectionResult(stdout, stderr, exitCode);
        } catch (IOException e) {
            return new ConnectionResult("", e.getMessage(), 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionResult("", "Interrupted: " + e.getMessage(), 1);
        } catch (ExecutionException e) {
            return new ConnectionResult("", "Execution failed: " + e.getMessage(), 1);
        }
    }

    private CompletableFuture<String> readStreamAsync(InputStream is) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void putFile(Path localPath, String remotePath) {
        List<String> command = List.of("docker", "cp", localPath.toAbsolutePath().toString(), containerName + ":" + remotePath);
        executeDockerCommand(command, "putFile");
    }

    @Override
    public void fetchFile(String remotePath, Path localPath) {
        List<String> command = List.of("docker", "cp", containerName + ":" + remotePath, localPath.toAbsolutePath().toString());
        executeDockerCommand(command, "fetchFile");
    }

    private void executeDockerCommand(List<String> command, String operationName) {
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process process = startProcess(pb);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("Docker " + operationName + " failed with exit code " + exitCode + ": " + stderr);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to run Docker command for " + operationName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // No-op for Docker connection
    }
}
