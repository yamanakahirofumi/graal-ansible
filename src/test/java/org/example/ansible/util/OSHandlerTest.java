package org.example.ansible.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OSHandlerTest {

    @Test
    void testLinuxHandler() {
        OSHandler handler = new LinuxHandler();
        assertEquals("/tmp", handler.getTempDir());
        assertEquals("/", handler.getSeparator());
        assertEquals("a/b/c", handler.getJoinPath("a", "b", "c"));
        assertEquals("Linux", handler.getOSFamily());
    }

    @Test
    void testWindowsHandler() {
        OSHandler handler = new WindowsHandler();
        assertEquals("C:\\Temp", handler.getTempDir());
        assertEquals("\\", handler.getSeparator());
        assertEquals("a\\b\\c", handler.getJoinPath("a", "b", "c"));
        assertEquals("Windows", handler.getOSFamily());
    }

    @Test
    void testOSHandlerFactory() {
        OSHandler handler = OSHandlerFactory.getHandler();
        assertNotNull(handler);

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            assertTrue(handler instanceof LinuxHandler);
            assertEquals("Linux", handler.getOSFamily());
        } else if (osName.contains("win")) {
            assertTrue(handler instanceof WindowsHandler);
            assertEquals("Windows", handler.getOSFamily());
        }
    }
}
