package org.example.ansible.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OSHandlerTest {

    @Test
    void testLinuxHandler() {
        OSHandler handler = new LinuxHandler();
        assertEquals("/tmp", handler.getTempDir());
        assertEquals("/", handler.getSeparator());
        assertEquals("a/b/c", handler.getJoinPath("a", "b", "c"));
        assertEquals("Linux", handler.getOSFamily());
        assertEquals(List.of("/bin/sh", "-c"), handler.getShellExecutable());
        assertTrue(handler.supportsSudo());
    }

    @Test
    void testMacOSHandler() {
        OSHandler handler = new MacOSHandler();
        assertEquals("/tmp", handler.getTempDir());
        assertEquals("/", handler.getSeparator());
        assertEquals("Darwin", handler.getOSFamily());
        assertEquals(List.of("/bin/sh", "-c"), handler.getShellExecutable());
        assertTrue(handler.supportsSudo());
    }

    @Test
    void testWindowsHandler() {
        OSHandler handler = new WindowsHandler();
        assertEquals("C:\\Temp", handler.getTempDir());
        assertEquals("\\", handler.getSeparator());
        assertEquals("a\\b\\c", handler.getJoinPath("a", "b", "c"));
        assertEquals("Windows", handler.getOSFamily());
        assertEquals(List.of("cmd.exe", "/c"), handler.getShellExecutable());
        assertFalse(handler.supportsSudo());
    }

    @Test
    void testGetJoinPathEdgeCases() {
        OSHandler linuxHandler = new LinuxHandler();
        assertEquals("", linuxHandler.getJoinPath((String[]) null));
        assertEquals("", linuxHandler.getJoinPath());
        assertEquals("single", linuxHandler.getJoinPath("single"));
        assertEquals("part1/part2", linuxHandler.getJoinPath("part1", "part2"));

        OSHandler windowsHandler = new WindowsHandler();
        assertEquals("", windowsHandler.getJoinPath((String[]) null));
        assertEquals("", windowsHandler.getJoinPath());
        assertEquals("single", windowsHandler.getJoinPath("single"));
        assertEquals("part1\\part2", windowsHandler.getJoinPath("part1", "part2"));
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
        } else if (osName.contains("mac")) {
            assertTrue(handler instanceof MacOSHandler);
            assertEquals("Darwin", handler.getOSFamily());
        }
    }
}
