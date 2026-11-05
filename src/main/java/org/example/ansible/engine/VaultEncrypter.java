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
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int KEY_LENGTH_BITS = 640; // 80 bytes
    private static final int LINE_WRAP_LENGTH = 80;

    private final SecureRandom secureRandom;

    public VaultEncrypter() {
        this.secureRandom = new SecureRandom();
    }

    public VaultEncrypter(SecureRandom secureRandom) {
        this.secureRandom = secureRandom != null ? secureRandom : new SecureRandom();
    }

    /**
     * Encrypts the plaintext using the given password in Ansible Vault 1.1 format.
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault encryption password.
     * @return The formatted vault string including the header.
     */
    public String encrypt(String plainText, String password) {
        return encrypt(plainText, password, null);
    }

    /**
     * Encrypts the plaintext using the given password and optional vault ID in Ansible Vault 1.1 or 1.2 format.
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault encryption password.
     * @param vaultId   The vault ID label (if null or blank, format 1.1 is used; otherwise 1.2).
     * @return The formatted vault string including the header.
     */
    public String encrypt(String plainText, String password, String vaultId) {
        if (plainText == null) {
            throw new IllegalArgumentException("Plaintext cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Vault password is required for encryption");
        }

        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        return encrypt(plainBytes, password, vaultId);
    }

    /**
     * Encrypts the plaintext bytes using the given password and optional vault ID in Ansible Vault 1.1 or 1.2 format.
     *
     * @param plainBytes The plaintext bytes to encrypt.
     * @param password   The vault encryption password.
     * @param vaultId    The vault ID label (if null or blank, format 1.1 is used; otherwise 1.2).
     * @return The formatted vault string including the header.
     */
    public String encrypt(byte[] plainBytes, String password, String vaultId) {
        if (plainBytes == null) {
            throw new IllegalArgumentException("Plaintext bytes cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Vault password is required for encryption");
        }

        // 1. Generate 32-byte random salt
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        // 2. Derive 80-byte key using PBKDF2WithHmacSHA256
        byte[] derivedKey;
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    salt,
                    PBKDF2_ITERATIONS,
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

        // 3. PKCS#7 Padding on plain bytes
        byte[] paddedPlainBytes = padPKCS7(plainBytes);

        // 4. Encrypt with AES-256-CTR
        byte[] cipherBytes;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKeyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, ivSpec);
            cipherBytes = cipher.doFinal(paddedPlainBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt data with AES-CTR: " + e.getMessage(), e);
        }

        // 5. Compute HMAC-SHA256 over ciphertext
        byte[] hmacBytes;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");
            mac.init(hmacKeySpec);
            hmacBytes = mac.doFinal(cipherBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC: " + e.getMessage(), e);
        }

        // 6. Construct inner text: salt_hex \n hmac_hex \n ciphertext_hex
        String innerText = hexEncode(salt) + "\n" + hexEncode(hmacBytes) + "\n" + hexEncode(cipherBytes);
        byte[] innerBytes = innerText.getBytes(StandardCharsets.UTF_8);

        // 7. Outer hex encode and 80-char wrap
        String outerHex = hexEncode(innerBytes);
        String wrappedPayload = wrap80(outerHex);

        // 8. Header construction
        String header;
        if (vaultId != null && !vaultId.trim().isEmpty()) {
            header = "$ANSIBLE_VAULT;1.2;AES256;" + vaultId.trim();
        } else {
            header = "$ANSIBLE_VAULT;1.1;AES256";
        }

        return header + "\n" + wrappedPayload;
    }

    private byte[] padPKCS7(byte[] data) {
        int padLen = 16 - (data.length % 16);
        byte[] padded = new byte[data.length + padLen];
        System.arraycopy(data, 0, padded, 0, data.length);
        byte padByte = (byte) padLen;
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = padByte;
        }
        return padded;
    }

    private String hexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private String wrap80(String text) {
        StringBuilder sb = new StringBuilder();
        int len = text.length();
        for (int i = 0; i < len; i += LINE_WRAP_LENGTH) {
            int end = Math.min(i + LINE_WRAP_LENGTH, len);
            sb.append(text, i, end);
            if (end < len) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
