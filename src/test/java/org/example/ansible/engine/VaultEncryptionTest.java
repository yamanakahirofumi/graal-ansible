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
    void testBasicEncryptionAndDecryptionRoundTrip() {
        String plainText = "MySuperSecretValue! 12345";
        String password = "testpassword123";

        String encryptedVault = encrypter.encrypt(plainText, password);

        assertNotNull(encryptedVault);
        assertTrue(encryptedVault.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        byte[] decryptedBytes = decrypter.decrypt(encryptedVault, password);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

        assertEquals(plainText, decryptedText);
    }

    @Test
    void testVault12FormatWithVaultId() {
        String plainText = "Vault 1.2 Secret Payload";
        String password = "complexVaultPassword!";
        String vaultId = "dev_vault";

        String encryptedVault = encrypter.encrypt(plainText, password, vaultId);

        assertNotNull(encryptedVault);
        assertTrue(encryptedVault.startsWith("$ANSIBLE_VAULT;1.2;AES256;dev_vault\n"),
                "Header should match 1.2 format with vault ID");

        byte[] decryptedBytes = decrypter.decrypt(encryptedVault, password);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

        assertEquals(plainText, decryptedText);
    }

    @Test
    void testVault11WhenVaultIdIsNullorEmpty() {
        String plainText = "Secret Data";
        String password = "pw";

        String v1 = encrypter.encrypt(plainText, password, null);
        assertTrue(v1.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        String v2 = encrypter.encrypt(plainText, password, "");
        assertTrue(v2.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));

        String v3 = encrypter.encrypt(plainText, password, "   ");
        assertTrue(v3.startsWith("$ANSIBLE_VAULT;1.1;AES256\n"));
    }

    @Test
    void testWrongPasswordFailsDecryption() {
        String plainText = "Secret message";
        String password = "correct_password";
        String wrongPassword = "wrong_password";

        String encryptedVault = encrypter.encrypt(plainText, password);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                decrypter.decrypt(encryptedVault, wrongPassword)
        );
        assertTrue(ex.getMessage().contains("HMAC mismatch"));
    }

    @Test
    void testVariousPayloadSizes() {
        int[] sizes = {0, 1, 15, 16, 17, 31, 32, 33, 64, 100, 256, 1024};
        String password = "size_test_password";

        for (int size : sizes) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < size; i++) {
                sb.append((char) ('a' + (i % 26)));
            }
            String plainText = sb.toString();

            String encrypted = encrypter.encrypt(plainText, password);
            byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
            String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

            assertEquals(plainText, decryptedText, "Failed round-trip for size: " + size);
        }
    }

    @Test
    void testUnicodePayload() {
        String plainText = "こんにちは、世界！Ansible Vault 鍵暗号化テスト 🔐 🚀";
        String password = "日本語パスワード";

        String encrypted = encrypter.encrypt(plainText, password, "prod_env");
        byte[] decryptedBytes = decrypter.decrypt(encrypted, password);
        String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);

        assertEquals(plainText, decryptedText);
    }

    @Test
    void testLineWrapFormatting() {
        String plainText = "Short";
        String password = "pw";

        String encrypted = encrypter.encrypt(plainText, password);
        String[] lines = encrypted.split("\n");

        assertTrue(lines.length >= 2, "There should be a header line and at least one payload line");
        assertEquals("$ANSIBLE_VAULT;1.1;AES256", lines[0]);

        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 80, "Each payload line must not exceed 80 characters");
            if (i < lines.length - 1) {
                assertEquals(80, lines[i].length(), "All intermediate payload lines must be exactly 80 characters");
            }
        }
    }

    @Test
    void testNullInputsValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                encrypter.encrypt((String) null, "pass")
        );
        assertThrows(IllegalArgumentException.class, () ->
                encrypter.encrypt("secret", null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                encrypter.encrypt((byte[]) null, "pass", null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                encrypter.encrypt(new byte[0], null, null)
        );
    }
}
