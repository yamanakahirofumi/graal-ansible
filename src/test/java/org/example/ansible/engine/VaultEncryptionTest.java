package org.example.ansible.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultEncryptionTest {

    private VaultEncrypter encrypter;
    private VaultDecrypter decrypter;

    @BeforeEach
    void setUp() {
        encrypter = new VaultEncrypter();
        decrypter = new VaultDecrypter();
    }

    @Test
    void testEncryptDecryptFormat11RoundTrip() {
        String plainText = "SuperSecretPassword123!";
        String password = "my_vault_password";

        String encrypted = encrypter.encrypt(plainText, password);
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decryptedText);
    }

    @Test
    void testEncryptDecryptFormat12WithVaultIdRoundTrip() {
        String plainText = "database_password: topsecret";
        String password = "production_vault_pass";
        String vaultId = "prod_vault";

        String encrypted = encrypter.encrypt(plainText, password, vaultId);
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.2;AES256;prod_vault\n"));

        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decryptedText);
    }

    @Test
    void test80CharLineWrapping() {
        String plainText = "Test line wrapping for Ansible Vault format encryption";
        String password = "pass";

        String encrypted = encrypter.encrypt(plainText, password);
        String[] lines = encrypted.split("\\r?\\n");
        assertTrue(lines.length >= 2, "There should be header and at least one payload line");
        assertEquals("$ANSIBLE_VAULT;1.1;AES256", lines[0]);

        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 80, "Each payload line must not exceed 80 characters");
            if (i < lines.length - 1) {
                assertEquals(80, lines[i].length(), "All payload lines except the last one must be exactly 80 characters");
            }
        }
    }

    @Test
    void testEmptyAndShortAndLongPlaintextRoundTrip() {
        String password = "secret_key";

        // Empty string
        String emptyEncrypted = encrypter.encrypt("", password);
        assertEquals("", new String(decrypter.decrypt(emptyEncrypted, password), StandardCharsets.UTF_8));

        // Short string (less than block size)
        String shortText = "abc";
        String shortEncrypted = encrypter.encrypt(shortText, password);
        assertEquals(shortText, new String(decrypter.decrypt(shortEncrypted, password), StandardCharsets.UTF_8));

        // Exact block size (16 bytes)
        String block16 = "1234567890123456";
        String block16Encrypted = encrypter.encrypt(block16, password);
        assertEquals(block16, new String(decrypter.decrypt(block16Encrypted, password), StandardCharsets.UTF_8));

        // Multi-block string
        String longText = "This is a long test string that spans multiple AES-256 blocks and should verify correct padding and encryption.";
        String longEncrypted = encrypter.encrypt(longText, password);
        assertEquals(longText, new String(decrypter.decrypt(longEncrypted, password), StandardCharsets.UTF_8));
    }

    @Test
    void testUnicodePlainTextRoundTrip() {
        String password = "unicode_password";
        String unicodeText = "日本語テキスト 秘密鍵 🚀 特殊文字 !@#$%^&*()_+\n改行を含むテキスト";

        String encrypted = encrypter.encrypt(unicodeText, password);
        String decryptedText = new String(decrypter.decrypt(encrypted, password), StandardCharsets.UTF_8);
        assertEquals(unicodeText, decryptedText);
    }

    @Test
    void testDecryptionWithWrongPasswordFails() {
        String plainText = "confidential";
        String password = "correct_password";

        String encrypted = encrypter.encrypt(plainText, password);
        assertThrows(RuntimeException.class, () -> decrypter.decrypt(encrypted, "wrong_password"));
    }

    @Test
    void testArgumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(null, "pass"));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt("text", null));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt("text", ""));
    }
}
