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
}
