package org.example.ansible.engine;

import java.util.Map;
import java.util.Scanner;

/**
 * A PromptProvider that reads input from the console.
 */
public class ConsolePromptProvider implements PromptProvider {
    private final Scanner scanner = new Scanner(System.in);

    @Override
    public String prompt(Map<String, Object> promptDef) {
        String name = (String) promptDef.get("name");
        String promptMsg = (String) promptDef.get("prompt");
        if (promptMsg == null) {
            promptMsg = "Enter value for " + name + ": ";
        }

        boolean isPrivate = !Boolean.FALSE.equals(promptDef.get("private")); // Default to true if not specified

        System.out.print(promptMsg);
        if (isPrivate && System.console() != null) {
            char[] password = System.console().readPassword();
            return new String(password);
        } else {
            return scanner.nextLine();
        }
    }
}
