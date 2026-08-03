package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Filter that generates a deterministic UUID v5 from a string, using Ansible's default namespace or a custom one.
 */
public class ToUuidFilter implements Filter {
    private static final UUID ANSIBLE_NAMESPACE = UUID.fromString("361e6d51-faec-444a-9079-341386da8e2e");

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        String name = (var == null) ? "" : var.toString();

        UUID namespace = ANSIBLE_NAMESPACE;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            try {
                namespace = UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                // Keep default if invalid custom namespace is passed
            }
        }

        try {
            return generateUuidV5(namespace, name).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available for UUID v5 generation", e);
        }
    }

    private UUID generateUuidV5(UUID namespace, String name) throws NoSuchAlgorithmException {
        byte[] namespaceBytes = uuidToBytes(namespace);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

        byte[] concat = new byte[namespaceBytes.length + nameBytes.length];
        System.arraycopy(namespaceBytes, 0, concat, 0, namespaceBytes.length);
        System.arraycopy(nameBytes, 0, concat, namespaceBytes.length, nameBytes.length);

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(concat);

        byte[] uuidBytes = new byte[16];
        System.arraycopy(digest, 0, uuidBytes, 0, 16);

        // Set version to 5 (SHA-1)
        uuidBytes[6] &= 0x0f;
        uuidBytes[6] |= 0x50;

        // Set variant to RFC 4122
        uuidBytes[8] &= 0x3f;
        uuidBytes[8] |= 0x80;

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (uuidBytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (uuidBytes[i] & 0xff);
        }

        return new UUID(msb, lsb);
    }

    private byte[] uuidToBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] buffer = new byte[16];
        for (int i = 0; i < 8; i++) {
            buffer[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 8; i < 16; i++) {
            buffer[i] = (byte) (lsb >>> (8 * (7 - (i - 8))));
        }
        return buffer;
    }

    @Override
    public String getName() {
        return "to_uuid";
    }
}
