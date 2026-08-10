package org.example.ansible;

import org.example.ansible.cli.PlaybookCli;
import picocli.CommandLine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Entry point for the graal-ansible application.
 */
public class Main {
    public static void main(String[] args) {
        checkOllamaConnection();
        int exitCode = new CommandLine(new PlaybookCli()).execute(args);
        System.exit(exitCode);
    }

    public static boolean checkOllamaConnection() {
        String ollamaHost = System.getenv("OLLAMA_HOST");
        if (ollamaHost == null || ollamaHost.isBlank()) {
            ollamaHost = "http://localhost:11434";
        } else {
            if (!ollamaHost.startsWith("http://") && !ollamaHost.startsWith("https://")) {
                ollamaHost = "http://" + ollamaHost;
            }
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaHost))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            System.out.println("[Ollama] Checking connection to: " + ollamaHost);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("[Ollama] Connection check: Successful");
                return true;
            } else {
                System.out.println("[Ollama] Connection check: Warning (HTTP Status: " + response.statusCode() + ")");
                return true; // We received a response from the host
            }
        } catch (Exception e) {
            System.out.println("[Ollama] Connection check: Failed (Ollama may not be running on " + ollamaHost + ")");
            return false;
        }
    }
}
