package com.risksentinel.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void checkFlagShouldListRegisteredTools_andExitCleanly() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true));
            Main.main(new String[]{"--check"});
        } finally {
            System.setOut(original);
        }

        String output = captured.toString();
        assertThat(output)
                .contains("get_snapshot")
                .contains("list_positions")
                .contains("get_instrument")
                .contains("list_recent_decisions")
                .contains("submit_proposal")
                .contains("engage_kill_switch")
                .contains("disengage_kill_switch");
    }

    @Test
    void buildRegistryShouldExposeAllSevenTools() {
        ToolRegistry registry = Main.buildRegistry(true);

        assertThat(registry.list())
                .extracting(Tool::name)
                .containsExactlyInAnyOrder(
                        "get_snapshot",
                        "list_positions",
                        "get_instrument",
                        "list_recent_decisions",
                        "submit_proposal",
                        "engage_kill_switch",
                        "disengage_kill_switch");
    }

    @Test
    void helpFlagShouldExitCleanly_andMentionOperatorFlag() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true));
            Main.main(new String[]{"--help"});
        } finally {
            System.setOut(original);
        }
        String output = captured.toString();
        assertThat(output).contains("--operator");
        assertThat(output).contains("ADMIN");
    }
}
