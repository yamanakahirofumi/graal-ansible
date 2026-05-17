package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pipe lookup plugin: executes external commands and returns their standard output.
 */
public class PipeLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        OSHandler osHandler = OSHandlerFactory.getHandler();
        List<String> shell = osHandler.getShellExecutable();

        for (Object termObj : terms) {
            String command = termObj != null ? termObj.toString() : "";
            List<String> fullCommand = new ArrayList<>(shell);
            fullCommand.add(command);

            try {
                Process process = new ProcessBuilder(fullCommand).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Pipe lookup command failed with exit code " + exitCode + ": " + command);
                }
                results.add(output);
            } catch (Exception e) {
                throw new RuntimeException("Pipe lookup failed for command: " + command, e);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "pipe";
    }
}
