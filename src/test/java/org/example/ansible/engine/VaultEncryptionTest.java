package org.example.ansible.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VaultEncryptionTest {

    private final VaultEncrypter encrypter = new VaultEncrypter();
    private final VaultDecrypter decrypter = new VaultDecrypter();

    @Test
    void testEncryptDecryptRoundTripFormat1_1() {
        String plainText = "Hello, Ansible Vault 1.1 Secret!";
        String password = "mySecurePassword123";

        String encrypted = encrypter.encrypt(plainText, password);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        // Verify line wrap length (80 columns max for payload lines)
        String[] lines = encrypted.split("\\r?\\n");
        assertTrue(lines.length > 1);
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 80, "Line " + i + " length exceeds 80: " + lines[i].length());
        }

        // Decrypt using VaultDecrypter
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptDecryptRoundTripFormat1_2WithVaultId() {
        String plainText = "Database credentials: db_user=root, db_pass=toor";
        String password = "stagingPassword";
        String vaultId = "staging_vault";

        String encrypted = encrypter.encrypt(plainText, password, vaultId);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("$ANSIBLE_VAULT;1.2;AES256;staging_vault\n"));

        // Verify decryption with VaultDecrypter
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptWithWrongPasswordFailsDecryption() {
        String plainText = "Secret message";
        String correctPassword = "correct";
        String wrongPassword = "wrong";

        String encrypted = encrypter.encrypt(plainText, correctPassword);

        assertThrows(RuntimeException.class, () -> {
            decrypter.decrypt(encrypted, wrongPassword);
        }, "HMAC validation should fail and throw an exception");
    }

    @Test
    void testEncryptEmptyString() {
        String plainText = "";
        String password = "pass";

        String encrypted = encrypter.encrypt(plainText, password);
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptExactBlockBoundary() {
        // Exactly 16 bytes: "1234567890123456"
        String plainText = "1234567890123456";
        String password = "boundaryPassword";

        String encrypted = encrypter.encrypt(plainText, password);
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);

        // Exactly 32 bytes
        String plainText32 = "12345678901234561234567890123456";
        String encrypted32 = encrypter.encrypt(plainText32, password);
        assertEquals(plainText32, new String(decrypter.decrypt(encrypted32, password), StandardCharsets.UTF_8));
    }

    @Test
    void testEncryptMultibyteUnicode() {
        String plainText = "暗号化テスト: 日本語の秘密情報と絵文字 🔐🚀";
        String password = "パスワード123";

        String encrypted = encrypter.encrypt(plainText, password);
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptLargePayload() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Line ").append(i).append(": some sensitive configuration data\n");
        }
        String plainText = sb.toString();
        String password = "superLargePayloadPassword";

        String encrypted = encrypter.encrypt(plainText, password);
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testNullInputsValidation() {
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(null, "pass"));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt("text", null));
        assertThrows(IllegalArgumentException.class, () -> encrypter.encrypt(null, null));
    }
}
