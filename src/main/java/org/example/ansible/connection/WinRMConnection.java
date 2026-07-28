package org.example.ansible.connection;

import io.cloudsoft.winrm4j.client.WinRmClientContext;
import io.cloudsoft.winrm4j.winrm.WinRmTool;
import io.cloudsoft.winrm4j.winrm.WinRmToolResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Connection plugin for Windows targets using WinRM and PowerShell.
 */
public class WinRMConnection implements Connection {
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean useHttps;
    private final boolean disableCertificateChecks;

    private WinRmClientContext context;
    private WinRmTool tool;

    public WinRMConnection(String host, int port, String user, String password) {
        this(host, port, user, password, false, true);
    }

    public WinRMConnection(String host, int port, String user, String password, boolean useHttps, boolean disableCertificateChecks) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.useHttps = useHttps;
        this.disableCertificateChecks = disableCertificateChecks;
    }

    /**
     * Protected helper to construct a WinRmTool instance.
     * Can be overridden or targeted during mock tests.
     */
    protected WinRmTool buildTool(String user, String password) {
        if (context == null) {
            context = WinRmClientContext.newInstance();
        }
        return WinRmTool.Builder.builder(host, user, password)
                .port(port)
                .useHttps(useHttps)
                .disableCertificateChecks(disableCertificateChecks)
                .context(context)
                .build();
    }

    protected synchronized WinRmTool getTool() {
        if (tool == null) {
            tool = buildTool(user, password);
        }
        return tool;
    }

    @Override
    public void connect() {
        try {
            WinRmToolResponse response = getTool().executePs("Write-Output 'connected'");
            if (response.getStatusCode() != 0) {
                throw new UnreachableException("Failed to execute test command: " + response.getStdErr());
            }
        } catch (Exception e) {
            throw new UnreachableException("WinRM connection failed to " + host + ":" + port + " - " + e.getMessage(), e);
        }
    }

    @Override
    public ConnectionResult execCommand(String command, BecomeContext becomeContext, Map<String, String> environment) {
        WinRmTool executionTool = getTool();

        if (becomeContext != null && becomeContext.become()) {
            String method = becomeContext.becomeMethod();
            if ("runas".equalsIgnoreCase(method) || becomeContext.becomeUser() != null) {
                String becomeUser = becomeContext.becomeUser() != null ? becomeContext.becomeUser() : "Administrator";
                String becomePassword = becomeContext.becomePassword() != null ? becomeContext.becomePassword() : "";
                // Recreate the tool dynamically with the become credentials as per spec 10.4
                executionTool = buildTool(becomeUser, becomePassword);
            }
        }

        // Apply environment variables as powershell environment variables ($env:KEY = "VALUE")
        StringBuilder sb = new StringBuilder();
        if (environment != null && !environment.isEmpty()) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                String escapedVal = entry.getValue().replace("`", "``").replace("\"", "`\"");
                sb.append("$env:").append(entry.getKey()).append(" = \"").append(escapedVal).append("\"; ");
            }
        }
        sb.append(command);

        try {
            WinRmToolResponse response = executionTool.executePs(sb.toString());
            return new ConnectionResult(response.getStdOut(), response.getStdErr(), response.getStatusCode());
        } catch (Exception e) {
            return new ConnectionResult("", e.getMessage(), 1);
        }
    }

    @Override
    public void putFile(Path localPath, String remotePath) {
        try {
            byte[] fileBytes = Files.readAllBytes(localPath);
            String base64Str = Base64.getEncoder().encodeToString(fileBytes);

            int chunkLength = 8000; // ~8KB chunks as per spec
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < base64Str.length(); i += chunkLength) {
                chunks.add(base64Str.substring(i, Math.min(base64Str.length(), i + chunkLength)));
            }

            // Generate a safe unique temp file name
            String tempFileName = "winrm-put-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000) + ".tmp";

            WinRmTool currentTool = getTool();

            // Clear any pre-existing temp file just in case, then append chunks
            String clearCmd = String.format(
                    "$tempFile = Join-Path $env:TEMP '%s';\n" +
                    "if (Test-Path $tempFile) { Remove-Item -Path $tempFile -Force }",
                    tempFileName
            );
            WinRmToolResponse clearResponse = currentTool.executePs(clearCmd);
            if (clearResponse.getStatusCode() != 0) {
                throw new UnreachableException("Failed to prepare remote temp file: " + clearResponse.getStdErr());
            }

            for (String chunk : chunks) {
                String appendCmd = String.format(
                        "$tempFile = Join-Path $env:TEMP '%s';\n" +
                        "[System.IO.File]::AppendAllText($tempFile, '%s')",
                        tempFileName, chunk
                );
                WinRmToolResponse response = currentTool.executePs(appendCmd);
                if (response.getStatusCode() != 0) {
                    throw new UnreachableException("Failed to upload file chunk: " + response.getStdErr());
                }
            }

            // Decode from Base64 to final remote path
            String decodeCmd = String.format(
                    "$tempFile = Join-Path $env:TEMP '%s';\n" +
                    "$base64 = Get-Content -Raw -Path $tempFile;\n" +
                    "$bytes = [System.Convert]::FromBase64String($base64);\n" +
                    "[System.IO.File]::WriteAllBytes('%s', $bytes);\n" +
                    "Remove-Item -Path $tempFile -Force;",
                    tempFileName, remotePath
            );
            WinRmToolResponse decodeResponse = currentTool.executePs(decodeCmd);
            if (decodeResponse.getStatusCode() != 0) {
                throw new UnreachableException("Failed to decode remote file: " + decodeResponse.getStdErr());
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read local file for upload: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UnreachableException("Failed to upload file to remote path " + remotePath + " over WinRM - " + e.getMessage(), e);
        }
    }

    @Override
    public void fetchFile(String remotePath, Path localPath) {
        try {
            String fetchCmd = String.format(
                    "$bytes = [System.IO.File]::ReadAllBytes('%s');\n" +
                    "$base64 = [System.Convert]::ToBase64String($bytes);\n" +
                    "Write-Output $base64;",
                    remotePath
            );

            WinRmToolResponse response = getTool().executePs(fetchCmd);
            if (response.getStatusCode() != 0) {
                throw new UnreachableException("Failed to fetch remote file: " + response.getStdErr());
            }

            String base64Output = response.getStdOut().replaceAll("\\s+", "");
            byte[] decodedBytes = Base64.getDecoder().decode(base64Output);

            Path target = localPath;
            if (Files.isDirectory(target)) {
                // Extract filename from remotePath
                String fileName = remotePath;
                int lastSlash = Math.max(remotePath.lastIndexOf('/'), remotePath.lastIndexOf('\\'));
                if (lastSlash >= 0) {
                    fileName = remotePath.substring(lastSlash + 1);
                }
                target = target.resolve(fileName);
            }

            // Ensure parent directory exists locally
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.write(target, decodedBytes);

        } catch (IOException e) {
            throw new RuntimeException("Failed to write downloaded file locally: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UnreachableException("Failed to download file from remote path " + remotePath + " over WinRM - " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (context != null) {
            context.shutdown();
            context = null;
        }
        tool = null;
    }
}
