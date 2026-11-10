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
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH_BITS = 640; // 80 bytes
    private static final int BLOCK_SIZE = 16;
    private static final int LINE_WRAP = 80;

    private final SecureRandom secureRandom;

    public VaultEncrypter() {
        this(new SecureRandom());
    }

    public VaultEncrypter(SecureRandom secureRandom) {
        this.secureRandom = secureRandom != null ? secureRandom : new SecureRandom();
    }

    /**
     * Encrypts plaintext using Ansible Vault format 1.1 (AES256).
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault encryption password.
     * @return The formatted vault string including the $ANSIBLE_VAULT header.
     */
    public String encrypt(String plainText, String password) {
        return encrypt(plainText, password, null);
    }

    /**
     * Encrypts plaintext using Ansible Vault format 1.1 or 1.2 (with vaultId).
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault encryption password.
     * @param vaultId   The vault ID label (if null, format 1.1 is used; if non-null, format 1.2 is used).
     * @return The formatted vault string including the $ANSIBLE_VAULT header.
     */
    public String encrypt(String plainText, String password, String vaultId) {
        if (plainText == null) {
            throw new IllegalArgumentException("Plaintext cannot be null");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Vault password is required and cannot be empty");
        }

        // 1. Generate 32-byte salt
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        // 2. Derive key using PBKDF2WithHmacSHA256
        byte[] derivedKey;
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    salt,
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

        // 3. Apply PKCS#7 padding to plaintext
        byte[] plainTextBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] paddedBytes = padPKCS7(plainTextBytes);

        // 4. Encrypt with AES/CTR/NoPadding
        byte[] ciphertextBytes;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKeyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, ivSpec);
            ciphertextBytes = cipher.doFinal(paddedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt ciphertext: " + e.getMessage(), e);
        }

        // 5. Compute HMAC-SHA256 over raw ciphertext
        byte[] hmacBytes;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");
            mac.init(hmacKeySpec);
            hmacBytes = mac.doFinal(ciphertextBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC: " + e.getMessage(), e);
        }

        // 6. Construct inner text (salt_hex \n hmac_hex \n ciphertext_hex)
        String saltHex = hexEncode(salt);
        String hmacHex = hexEncode(hmacBytes);
        String ciphertextHex = hexEncode(ciphertextBytes);

        String innerText = saltHex + "\n" + hmacHex + "\n" + ciphertextHex;
        byte[] innerTextBytes = innerText.getBytes(StandardCharsets.UTF_8);

        // 7. Outer hex encode and line wrap to 80 chars
        String outerHex = hexEncode(innerTextBytes);
        StringBuilder wrappedBuilder = new StringBuilder();
        for (int i = 0; i < outerHex.length(); i += LINE_WRAP) {
            int end = Math.min(i + LINE_WRAP, outerHex.length());
            wrappedBuilder.append(outerHex, i, end).append("\n");
        }

        // 8. Construct full header
        String header;
        if (vaultId == null || vaultId.isBlank()) {
            header = "$ANSIBLE_VAULT;1.1;AES256";
        } else {
            header = "$ANSIBLE_VAULT;1.2;AES256;" + vaultId.trim();
        }

        return header + "\n" + wrappedBuilder.toString().trim();
    }

    private byte[] padPKCS7(byte[] data) {
        int padLen = BLOCK_SIZE - (data.length % BLOCK_SIZE);
        byte[] padded = Arrays.copyOf(data, data.length + padLen);
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = (byte) padLen;
        }
        return padded;
    }

    private String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
