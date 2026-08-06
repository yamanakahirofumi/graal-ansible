package org.example.ansible.engine;

import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaultDecryptionTest {

    private static final String ENCRYPTED_VAULT_STRING =
            "$ANSIBLE_VAULT;1.1;AES256\n" +
            "38386132666665666561306464623762303366316538303833326438663638656631393434303161\n" +
            "3161373038303133636432653534653433366236633663610a633238343135633862366534363835\n" +
            "39303635306536333161363137343033656334353038343062366433333031393564303261373639\n" +
            "6337613163303661380a373066633038373638623531343362316532656663643632363434626534\n" +
            "6461";

    private static final String PASSWORD = "secretpass";
    private static final String PLAINTEXT = "SuperSecret123!";

    @Test
    void testDirectDecryption() {
        VaultDecrypter decrypter = new VaultDecrypter();
        byte[] decryptedBytes = decrypter.decrypt(ENCRYPTED_VAULT_STRING, PASSWORD);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(PLAINTEXT, decryptedText);
    }

    @Test
    void testDirectDecryptionWithWrongPassword() {
        VaultDecrypter decrypter = new VaultDecrypter();
        assertThrows(RuntimeException.class, () -> {
            decrypter.decrypt(ENCRYPTED_VAULT_STRING, "wrongpassword");
        }, "HMAC validation should fail and throw an exception");
    }

    @Test
    void testYamlParsingToVaultDecryptedValue() {
        String yaml = """
                - name: play with vault
                  hosts: localhost
                  vars:
                    my_secret: !vault |
                      $ANSIBLE_VAULT;1.1;AES256
                      38386132666665666561306464623762303366316538303833326438663638656631393434303161
                      3161373038303133636432653534653433366236633663610a633238343135633862366534363835
                      39303635306536333161363137343033656334353038343062366433333031393564303261373639
                      6337613163303661380a373066633038373638623531343362316532656663643632363434626534
                      6461
                """;
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertNotNull(playbook);
        Object mySecretObj = playbook.plays().get(0).vars().get("my_secret");
        assertTrue(mySecretObj instanceof VaultDecryptedValue, "Parsed value should be an instance of VaultDecryptedValue");

        VaultDecryptedValue vaultValue = (VaultDecryptedValue) mySecretObj;
        assertTrue(vaultValue.getEncryptedRawText().contains("$ANSIBLE_VAULT;1.1;AES256"));
    }

    @Test
    void testVariableResolutionWithPassword() {
        VaultDecryptedValue vaultValue = new VaultDecryptedValue(ENCRYPTED_VAULT_STRING);
        VariableResolver resolver = new VariableResolver(PASSWORD);

        Object resolved = resolver.resolveValue(vaultValue, Map.of());
        assertEquals(PLAINTEXT, resolved);
    }

    @Test
    void testVariableResolutionMissingPassword() {
        VaultDecryptedValue vaultValue = new VaultDecryptedValue(ENCRYPTED_VAULT_STRING);
        VariableResolver resolver = new VariableResolver(); // No password provided

        assertThrows(RuntimeException.class, () -> {
            resolver.resolveValue(vaultValue, Map.of());
        }, "Should throw an exception if vault password is not provided");
    }

    @Test
    void testRecursiveResolutionWithVault(@TempDir Path tempDir) throws Exception {
        String yaml = """
                - name: test playbook
                  hosts: localhost
                  vars:
                    my_secret: !vault |
                      $ANSIBLE_VAULT;1.1;AES256
                      38386132666665666561306464623762303366316538303833326438663638656631393434303161
                      3161373038303133636432653534653433366236633663610a633238343135633862366534363835
                      39303635306536333161363137343033656334353038343062366433333031393564303261373639
                      6337613163303661380a373066633038373638623531343362316532656663643632363434626534
                      6461
                  tasks:
                    - name: use secret
                      debug:
                        msg: "secret is {{ my_secret }}"
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        VariableResolver resolver = new VariableResolver(PASSWORD);
        Map<String, Object> resolvedVars = resolver.resolve(
                playbook.plays().get(0).vars(),
                Map.of()
        );

        Object resolvedSecret = resolvedVars.get("my_secret");
        assertEquals(PLAINTEXT, resolvedSecret);

        Map<String, Object> taskArgs = playbook.plays().get(0).tasks().get(0).args();
        Map<String, Object> resolvedTaskArgs = resolver.resolve(taskArgs, resolvedVars);
        assertEquals("secret is SuperSecret123!", resolvedTaskArgs.get("msg"));
    }
}
