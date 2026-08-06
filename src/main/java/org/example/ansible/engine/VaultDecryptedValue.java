package org.example.ansible.engine;

/**
 * A wrapper class that holds raw Ansible Vault encrypted content parsed from YAML.
 */
public class VaultDecryptedValue {
    private final String encryptedRawText;

    public VaultDecryptedValue(String encryptedRawText) {
        this.encryptedRawText = encryptedRawText;
    }

    public String getEncryptedRawText() {
        return encryptedRawText;
    }

    @Override
    public String toString() {
        return encryptedRawText;
    }
}
