package org.example.ansible.engine;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Handles encryption of Ansible Vault data (Format 1.1 / 1.2).
 */
public class VaultEncrypter {

    private static final int SALT_LENGTH = 32;
    private static final int KEY_LENGTH_BITS = 640; // 80 bytes
    private static final int ITERATIONS = 10000;
    private static final int BLOCK_SIZE = 16;
    private static final int LINE_WRAP_WIDTH = 80;

    private final SecureRandom secureRandom;

    public VaultEncrypter() {
        this.secureRandom = new SecureRandom();
    }

    public VaultEncrypter(SecureRandom secureRandom) {
        this.secureRandom = secureRandom != null ? secureRandom : new SecureRandom();
    }

    /**
     * Encrypts the plain text using the given password into Ansible Vault 1.1 format.
     *
     * @param plainText The plain text to encrypt.
     * @param password  The vault encryption password.
     * @return The formatted multiline vault-encrypted string.
     */
    public String encrypt(String plainText, String password) {
        return encrypt(plainText, password, null);
    }

    /**
     * Encrypts the plain text using the given password and vault ID into Ansible Vault 1.1 or 1.2 format.
     *
     * @param plainText The plain text to encrypt.
     * @param password  The vault encryption password.
     * @param vaultId   The vault ID label (if null or blank, format 1.1 is used; otherwise format 1.2).
     * @return The formatted multiline vault-encrypted string.
     */
    public String encrypt(String plainText, String password, String vaultId) {
        if (plainText == null) {
            throw new IllegalArgumentException("Plain text cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Vault password is required for encryption");
        }

        // 1. Generate 32-byte salt
        byte[] saltBytes = new byte[SALT_LENGTH];
        secureRandom.nextBytes(saltBytes);

        // 2. Derive 80-byte key using PBKDF2WithHmacSHA256
        byte[] derivedKey;
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    saltBytes,
                    ITERATIONS,
                    KEY_LENGTH_BITS
            );
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            derivedKey = skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive key using PBKDF2: " + e.getMessage(), e);
        }

        byte[] aesKeyBytes = Arrays.copyOfRange(derivedKey, 0, 32);
        byte[] hmacKeyBytes = Arrays.copyOfRange(derivedKey, 32, 64);
        byte[] ivBytes = Arrays.copyOfRange(derivedKey, 64, 80);

        // 3. PKCS#7 padding on plain text bytes
        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] paddedBytes = padPKCS7(plainBytes);

        // 4. Encrypt with AES/CTR/NoPadding
        byte[] ciphertextBytes;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKeyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, ivSpec);
            ciphertextBytes = cipher.doFinal(paddedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt plain text: " + e.getMessage(), e);
        }

        // 5. Calculate HMAC-SHA256 over raw ciphertext bytes
        byte[] hmacBytes;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");
            mac.init(hmacKeySpec);
            hmacBytes = mac.doFinal(ciphertextBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC: " + e.getMessage(), e);
        }

        // 6. Build inner text: salt_hex \n hmac_hex \n ciphertext_hex
        String saltHex = hexEncode(saltBytes);
        String hmacHex = hexEncode(hmacBytes);
        String ciphertextHex = hexEncode(ciphertextBytes);
        String innerPayload = saltHex + "\n" + hmacHex + "\n" + ciphertextHex;

        // 7. Outer hex encode inner payload bytes and wrap lines at 80 characters
        byte[] innerBytes = innerPayload.getBytes(StandardCharsets.UTF_8);
        String outerHex = hexEncode(innerBytes);
        String wrappedPayload = wrapLine(outerHex, LINE_WRAP_WIDTH);

        // 8. Construct header: $ANSIBLE_VAULT;1.1;AES256 or $ANSIBLE_VAULT;1.2;AES256;<vaultId>
        String header;
        if (vaultId != null && !vaultId.isBlank()) {
            header = "$ANSIBLE_VAULT;1.2;AES256;" + vaultId.trim();
        } else {
            header = "$ANSIBLE_VAULT;1.1;AES256";
        }

        return header + "\n" + wrappedPayload;
    }

    private byte[] padPKCS7(byte[] data) {
        int padLen = BLOCK_SIZE - (data.length % BLOCK_SIZE);
        byte[] padded = Arrays.copyOf(data, data.length + padLen);
        Arrays.fill(padded, data.length, padded.length, (byte) padLen);
        return padded;
    }

    private String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private String wrapLine(String text, int width) {
        StringBuilder sb = new StringBuilder(text.length() + (text.length() / width) + 1);
        for (int i = 0; i < text.length(); i += width) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(text, i, Math.min(i + width, text.length()));
        }
        return sb.toString();
    }
}
