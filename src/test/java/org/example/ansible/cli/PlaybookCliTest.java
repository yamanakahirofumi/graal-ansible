package org.example.ansible.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookCliTest {

    @Test
    void testParseBasicArguments() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "-i", "hosts", "-v");

        assertEquals("playbook.yml", app.getPlaybook().getName());
        assertEquals("hosts", app.getInventory());
        assertEquals(1, app.getVerbose());
    }

    @Test
    void testParseMultipleExtraVars() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "-e", "key1=val1", "--extra-vars", "key2=val2");

        assertEquals(2, app.getExtraVars().size());
        assertTrue(app.getExtraVars().contains("key1=val1"));
        assertTrue(app.getExtraVars().contains("key2=val2"));
    }

    @Test
    void testParseVerboseLevels() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "-vvv");

        assertEquals(3, app.getVerbose());
    }

    @Test
    void testParseCheckAndLimit() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--check", "--limit", "webservers");

        assertTrue(app.isCheck());
        assertEquals("webservers", app.getLimit());
    }

    @Test
    void testParseTags() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--tags", "tag1,tag2");

        assertEquals(1, app.getTags().size());
        assertEquals("tag1,tag2", app.getTags().get(0));
    }

    @Test
    void testVaultPasswordFileOption(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path passFile = tempDir.resolve("vault_pass.txt");
        java.nio.file.Files.writeString(passFile, "secret123\n");

        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--vault-password-file", passFile.toString());

        assertEquals("secret123", app.getVaultPassword());
    }

    @Test
    void testVaultIdOptionWithLabel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path passFile = tempDir.resolve("vault_id_pass.txt");
        java.nio.file.Files.writeString(passFile, "vaultpass456\n");

        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--vault-id", "dev@" + passFile.toString());

        assertEquals("vaultpass456", app.getVaultPassword());
    }

    @Test
    void testVaultIdOptionWithoutLabel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path passFile = tempDir.resolve("vault_raw_pass.txt");
        java.nio.file.Files.writeString(passFile, "rawpass789\n");

        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--vault-id", passFile.toString());

        assertEquals("rawpass789", app.getVaultPassword());
    }

    @Test
    void testVaultPasswordFileNotFound() {
        PlaybookCli app = new PlaybookCli();
        CommandLine cmd = new CommandLine(app);

        cmd.parseArgs("playbook.yml", "--vault-password-file", "/non/existent/vault_pass_file.txt");

        RuntimeException ex = assertThrows(RuntimeException.class, app::getVaultPassword);
        assertTrue(ex.getMessage().contains("Vault password file not found"));
    }
}
