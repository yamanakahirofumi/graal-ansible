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

    private static final String HEADER_PREFIX = "$ANSIBLE_VAULT;";
    private static final String CIPHER_NAME = "AES256";
    private static final int SALT_BYTES = 32;
    private static final int ITERATIONS = 10000;
    private static final int KEY_LENGTH_BITS = 640; // 80 bytes
    private static final int AES_BLOCK_SIZE = 16;
    private static final int LINE_WRAP_LENGTH = 80;

    private final SecureRandom secureRandom;

    public VaultEncrypter() {
        this(new SecureRandom());
    }

    public VaultEncrypter(SecureRandom secureRandom) {
        this.secureRandom = secureRandom != null ? secureRandom : new SecureRandom();
    }

    /**
     * Encrypts plaintext using Vault 1.1 format with the specified password.
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault password.
     * @return Formatted Ansible Vault string including header.
     */
    public String encrypt(String plainText, String password) {
        return encrypt(plainText, password, null);
    }

    /**
     * Encrypts plaintext using Vault 1.1 or 1.2 format (with vaultId) using the specified password.
     *
     * @param plainText The plaintext to encrypt.
     * @param password  The vault password.
     * @param vaultId   Optional vault ID label for 1.2 format (null or empty string results in 1.1 format).
     * @return Formatted Ansible Vault string including header.
     */
    public String encrypt(String plainText, String password, String vaultId) {
        if (plainText == null) {
            throw new IllegalArgumentException("Plain text cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Vault password is required for encryption");
        }

        // 1. Generate 32-byte salt
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);

        // 2. Derive keys via PBKDF2WithHmacSHA256 (80 bytes)
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

        byte[] aesKey = Arrays.copyOfRange(derivedKey, 0, 32);
        byte[] hmacKey = Arrays.copyOfRange(derivedKey, 32, 64);
        byte[] iv = Arrays.copyOfRange(derivedKey, 64, 80);

        // 3. PKCS#7 pad the plaintext bytes
        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] paddedBytes = padPKCS7(plainBytes, AES_BLOCK_SIZE);

        // 4. AES-256-CTR encrypt
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKeySpec, ivSpec);
            ciphertext = cipher.doFinal(paddedBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt ciphertext: " + e.getMessage(), e);
        }

        // 5. Compute HMAC-SHA256 of ciphertext
        byte[] computedHmac;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacKey, "HmacSHA256");
            mac.init(hmacKeySpec);
            computedHmac = mac.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC: " + e.getMessage(), e);
        }

        // 6. Build inner text: salt_hex \n hmac_hex \n ciphertext_hex
        String saltHex = hexEncode(salt);
        String hmacHex = hexEncode(computedHmac);
        String ciphertextHex = hexEncode(ciphertext);
        String innerText = saltHex + "\n" + hmacHex + "\n" + ciphertextHex;

        // 7. Outer hex encode inner text (UTF-8 bytes to hex) and wrap at 80 columns
        byte[] innerBytes = innerText.getBytes(StandardCharsets.UTF_8);
        String outerHex = hexEncode(innerBytes);
        String wrappedPayload = wrapString(outerHex, LINE_WRAP_LENGTH);

        // 8. Build header
        String header;
        if (vaultId != null && !vaultId.trim().isEmpty()) {
            header = HEADER_PREFIX + "1.2;" + CIPHER_NAME + ";" + vaultId.trim();
        } else {
            header = HEADER_PREFIX + "1.1;" + CIPHER_NAME;
        }

        return header + "\n" + wrappedPayload;
    }

    private byte[] padPKCS7(byte[] data, int blockSize) {
        int padLen = blockSize - (data.length % blockSize);
        byte[] padded = Arrays.copyOf(data, data.length + padLen);
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = (byte) padLen;
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

    private String wrapString(String str, int lineLength) {
        StringBuilder sb = new StringBuilder(str.length() + (str.length() / lineLength) * 2);
        for (int i = 0; i < str.length(); i += lineLength) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(str, i, Math.min(i + lineLength, str.length()));
        }
        return sb.toString();
    }
}
