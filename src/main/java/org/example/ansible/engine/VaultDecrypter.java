package org.example.ansible.engine;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Handles decryption of Ansible Vault data (Format 1.1 / 1.2).
 */
public class VaultDecrypter {

    /**
     * Decrypts the raw encrypted Ansible Vault text using the given password.
     *
     * @param encryptedRawText The raw multiline vault-encrypted string (including the header).
     * @param password         The vault decryption password.
     * @return The decrypted bytes.
     */
    public byte[] decrypt(String encryptedRawText, String password) {
        if (encryptedRawText == null) {
            throw new IllegalArgumentException("Encrypted raw text cannot be null");
        }
        if (password == null) {
            throw new IllegalArgumentException("Vault password is required for decryption");
        }

        String[] lines = encryptedRawText.split("\\r?\\n");
        if (lines.length == 0) {
            throw new RuntimeException("Invalid vault format: empty input");
        }

        String header = lines[0].trim();
        if (!header.startsWith("$ANSIBLE_VAULT;")) {
            throw new RuntimeException("Invalid vault format: missing $ANSIBLE_VAULT header");
        }

        // Join the remaining lines to get the full hex-encoded vault text
        StringBuilder hexBuilder = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            hexBuilder.append(lines[i].trim());
        }

        byte[] vaultTextBytes = hexDecode(hexBuilder.toString());
        String vaultTextStr = new String(vaultTextBytes, StandardCharsets.UTF_8);

        // The content string consists of: salt_hex \n hmac_hex \n ciphertext_hex
        String[] parts = vaultTextStr.split("\\r?\\n");
        if (parts.length < 3) {
            throw new RuntimeException("Invalid vault payload: expected salt, hmac, and ciphertext");
        }

        String saltHex = parts[0].trim();
        String hmacHex = parts[1].trim();
        String ciphertextHex = parts[2].trim();

        byte[] saltBytes = hexDecode(saltHex);
        byte[] hmacBytes = hexDecode(hmacHex);
        byte[] ciphertextBytes = hexDecode(ciphertextHex);

        // Derive keys using PBKDF2WithHmacSHA256
        // Iterations: 10000
        // Total Key Length: 80 bytes (640 bits)
        byte[] derivedKey;
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    saltBytes,
                    10000,
                    640
            );
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            derivedKey = skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive key using PBKDF2: " + e.getMessage(), e);
        }

        byte[] aesKeyBytes = Arrays.copyOfRange(derivedKey, 0, 32);
        byte[] hmacKeyBytes = Arrays.copyOfRange(derivedKey, 32, 64);
        byte[] ivBytes = Arrays.copyOfRange(derivedKey, 64, 80);

        // Verify HMAC-SHA256
        byte[] computedHmac;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");
            mac.init(hmacKeySpec);
            computedHmac = mac.doFinal(ciphertextBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC: " + e.getMessage(), e);
        }

        if (!MessageDigest.isEqual(computedHmac, hmacBytes)) {
            throw new RuntimeException("Decryption failed: HMAC mismatch (wrong password or data corrupted)");
        }

        // Decrypt with AES/CTR/NoPadding
        byte[] decryptedBytes;
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKeyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, ivSpec);
            decryptedBytes = cipher.doFinal(ciphertextBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt ciphertext: " + e.getMessage(), e);
        }

        return unpadPKCS7(decryptedBytes);
    }

    private byte[] unpadPKCS7(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        int padVal = data[data.length - 1] & 0xFF;
        if (padVal < 1 || padVal > 16 || padVal > data.length) {
            return data;
        }
        for (int i = data.length - padVal; i < data.length; i++) {
            if ((data[i] & 0xFF) != padVal) {
                return data;
            }
        }
        return Arrays.copyOf(data, data.length - padVal);
    }

    private byte[] hexDecode(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have an even length");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
