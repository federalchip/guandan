package com.guandan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.guandan.cli.GuandanCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {
    @TempDir Path temp;

    @Test
    void test_simulate_writes_replayable_records_without_web_server() throws Exception {
        Path output = temp.resolve("sim.json");
        assertEquals(0, GuandanCli.run(new String[] {"simulate", "--start-seed", "17", "--games", "2",
                "--max-actions", "512", "--output", output.toString()}));
        JsonNode report = new ObjectMapper().readTree(output.toFile());
        assertEquals(2, report.get("games_run").asInt());
        assertEquals(2, report.get("games").size());
        assertEquals(17, report.get("games").get(0).get("seed").asInt());
        assertTrue(report.get("games").get(0).get("steps").size() > 0);
        assertEquals(0, GuandanCli.run(new String[] {"replay", "--input", output.toString()}));
    }

    @Test
    void test_bad_cli_input_returns_nonzero() {
        assertEquals(2, GuandanCli.run(new String[] {"simulate", "--games", "0"}));
    }

    @Test
    void test_failure_replay_rejects_changed_prefix_and_reason() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path output = temp.resolve("failed-sim.json");
        assertEquals(1, GuandanCli.run(new String[] {"simulate", "--start-seed", "17", "--games", "1",
                "--max-actions", "1", "--output", output.toString()}));
        Path fixture = temp.resolve("failed-sim.json.failure-seed-17.json");
        ObjectNode saved = (ObjectNode) json.readTree(fixture.toFile());
        assertEquals(0, GuandanCli.run(new String[] {"replay", "--input", fixture.toString()}));

        ObjectNode changedHands = saved.deepCopy();
        ((ArrayNode) changedHands.withArray("initial_hands").get(0))
                .set(0, json.getNodeFactory().numberNode(999));
        Files.write(fixture, json.writeValueAsBytes(changedHands));
        assertEquals(1, GuandanCli.run(new String[] {"replay", "--input", fixture.toString()}));

        ObjectNode changedStep = saved.deepCopy();
        ((ObjectNode) changedStep.withArray("completed_steps").get(0)).put("actor", 3);
        Files.write(fixture, json.writeValueAsBytes(changedStep));
        assertEquals(1, GuandanCli.run(new String[] {"replay", "--input", fixture.toString()}));

        ObjectNode changedReason = saved.deepCopy();
        ((ObjectNode) changedReason.get("failure")).put("error", "different failure at same step");
        Files.write(fixture, json.writeValueAsBytes(changedReason));
        assertEquals(1, GuandanCli.run(new String[] {"replay", "--input", fixture.toString()}));
    }
}
