package org.example.ansible.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultEncryptionTest {

    private static final String PASSWORD = "secretpassword";
    private static final String PLAINTEXT = "SuperSecret123!";

    @Test
    void testEncryptDecryptFormat11RoundTrip() {
        VaultEncrypter encrypter = new VaultEncrypter();
        String encrypted = encrypter.encrypt(PLAINTEXT, PASSWORD);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        VaultDecrypter decrypter = new VaultDecrypter();
        byte[] decryptedBytes = decrypter.decrypt(encrypted, PASSWORD);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

        assertEquals(PLAINTEXT, decryptedText);
    }

    @Test
    void testEncryptDecryptFormat12RoundTrip() {
        VaultEncrypter encrypter = new VaultEncrypter();
        String vaultId = "prod_vault";
        String encrypted = encrypter.encrypt(PLAINTEXT, PASSWORD, vaultId);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.2;AES256;" + vaultId + "\n"));

        VaultDecrypter decrypter = new VaultDecrypter();
        byte[] decryptedBytes = decrypter.decrypt(encrypted, PASSWORD);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

        assertEquals(PLAINTEXT, decryptedText);
    }

    @Test
    void testLineWrapping() {
        VaultEncrypter encrypter = new VaultEncrypter();
        String encrypted = encrypter.encrypt(PLAINTEXT, PASSWORD);

        String[] lines = encrypted.split("\\r?\\n");
        assertTrue(lines.length > 1, "Encrypted output should be multiline");
        assertEquals("$ANSIBLE_VAULT;1.1;AES256", lines[0]);

        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 80, "Each hex line must not exceed 80 characters");
        }
    }

    @Test
    void testDecryptionWithWrongPasswordFails() {
        VaultEncrypter encrypter = new VaultEncrypter();
        String encrypted = encrypter.encrypt(PLAINTEXT, PASSWORD);

        VaultDecrypter decrypter = new VaultDecrypter();
        assertThrows(RuntimeException.class, () -> decrypter.decrypt(encrypted, "wrongpassword"));
    }

    @Test
    void testNullArguments() {
        VaultEncrypter encrypter = new VaultEncrypter();

        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(null, PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(PLAINTEXT, null));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(null, PASSWORD, "label"));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(PLAINTEXT, null, "label"));
    }

    @Test
    void testVariousPlaintextSizes() {
        VaultEncrypter encrypter = new VaultEncrypter();
        VaultDecrypter decrypter = new VaultDecrypter();

        // Empty string
        String emptyEncrypted = encrypter.encrypt("", PASSWORD);
        assertEquals("", new String(decrypter.decrypt(emptyEncrypted, PASSWORD), StandardCharsets.UTF_8));

        // Exact 16-byte string
        String block16 = "1234567890123456";
        String block16Encrypted = encrypter.encrypt(block16, PASSWORD);
        assertEquals(block16, new String(decrypter.decrypt(block16Encrypted, PASSWORD), StandardCharsets.UTF_8));

        // Exact 32-byte string
        String block32 = "12345678901234561234567890123456";
        String block32Encrypted = encrypter.encrypt(block32, PASSWORD);
        assertEquals(block32, new String(decrypter.decrypt(block32Encrypted, PASSWORD), StandardCharsets.UTF_8));

        // Long string (> 1000 chars)
        String longText = "AnsibleVaultEncryptionTest".repeat(50);
        String longEncrypted = encrypter.encrypt(longText, PASSWORD);
        assertEquals(longText, new String(decrypter.decrypt(longEncrypted, PASSWORD), StandardCharsets.UTF_8));
    }
}
