package org.example.ansible.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OSHandlerTest {

    private String originalOsName;

    @BeforeEach
    void setUp() {
        originalOsName = System.getProperty("os.name");
    }

    @AfterEach
    void tearDown() {
        if (originalOsName != null) {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    void testMacOSHandler() {
        System.setProperty("os.name", "Mac OS X");
        OSHandler handler = OSHandlerFactory.getHandler();

        assertTrue(handler instanceof MacOSHandler);
        assertEquals("Darwin", handler.getOSFamily());
        assertEquals("/", handler.getSeparator());
    }

    @Test
    void testLinuxHandler() {
        System.setProperty("os.name", "Linux");
        OSHandler handler = OSHandlerFactory.getHandler();

        assertTrue(handler instanceof LinuxHandler);
        assertEquals("Linux", handler.getOSFamily());
    }

    @Test
    void testWindowsHandler() {
        System.setProperty("os.name", "Windows 10");
        OSHandler handler = OSHandlerFactory.getHandler();

        assertTrue(handler instanceof WindowsHandler);
        assertEquals("Windows", handler.getOSFamily());
    }
}
