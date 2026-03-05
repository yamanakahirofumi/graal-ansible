package org.example.ansible.connection;

import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Implementation of Connection for local execution.
 */
public class LocalConnection implements Connection {

    private final OSHandler osHandler;

    public LocalConnection() {
        this(OSHandlerFactory.getHandler());
    }

    public LocalConnection(OSHandler osHandler) {
        this.osHandler = osHandler;
    }

    @Override
    public void connect() {
        // No-op for local connection
    }

    @Override
    public ConnectionResult execCommand(String command, BecomeContext becomeContext) {
        List<String> commandList = new ArrayList<>();

        if (becomeContext != null && becomeContext.become() && osHandler.supportsSudo()) {
            String method = becomeContext.becomeMethod();
            if (method == null || "sudo".equals(method)) {
                commandList.add("sudo");
                commandList.add("-p");
                commandList.add("BECOME-PROMPT");
                if (becomeContext.becomeUser() != null) {
                    commandList.add("-u");
                    commandList.add(becomeContext.becomeUser());
                }
                if (becomeContext.becomeFlags() != null && !becomeContext.becomeFlags().isEmpty()) {
                    for (String flag : becomeContext.becomeFlags().split("\\s+")) {
                        if (!flag.isEmpty()) commandList.add(flag);
                    }
                }
            } else if ("su".equals(method)) {
                commandList.add("su");
                if (becomeContext.becomeUser() != null) {
                    commandList.add(becomeContext.becomeUser());
                }
                commandList.add("-c");
            }
        }

        commandList.addAll(osHandler.getShellExecutable());
        commandList.add(command);

        ProcessBuilder pb = new ProcessBuilder(commandList);
        try {
            Process process = pb.start();

            // Read stdout and stderr concurrently to avoid deadlock
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
    public void putFile(Path localPath, Path remotePath) {
        try {
            Files.copy(localPath, remotePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file locally (putFile): " + e.getMessage(), e);
        }
    }

    @Override
    public void fetchFile(Path remotePath, Path localPath) {
        try {
            Files.copy(remotePath, localPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file locally (fetchFile): " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // No-op for local connection
    }
}
