package org.example.ansible.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MacOSHandlerTest {

    @Test
    void testMacOSHandler() {
        OSHandler handler = new MacOSHandler();
        assertEquals("/tmp", handler.getTempDir());
        assertEquals("/", handler.getSeparator());
        assertEquals("a/b/c", handler.getJoinPath("a", "b", "c"));
        assertEquals("Darwin", handler.getOSFamily());
    }
}
