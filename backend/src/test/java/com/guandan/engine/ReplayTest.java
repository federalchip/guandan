package com.guandan.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guandan.cli.SimulationRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void test_replay_reproduces_result_and_public_events() throws Exception {
        SimulationRunner.GameTrace original = SimulationRunner.run(17, 512);
        SimulationRunner.GameTrace saved = json.readValue(json.writeValueAsBytes(original), SimulationRunner.GameTrace.class);
        SimulationRunner.GameTrace replayed = SimulationRunner.replay(saved);
        assertEquals(json.valueToTree(original.result()), json.valueToTree(replayed.result()));
        assertEquals(json.valueToTree(original.steps()), json.valueToTree(replayed.steps()));
    }

    @Test
    void test_replay_rejects_tampered_actor_with_seed_and_step() throws Exception {
        ObjectNode saved = (ObjectNode) json.valueToTree(SimulationRunner.run(17, 512));
        ((ObjectNode) saved.withArray("steps").get(0)).put("actor", 3);
        SimulationRunner.GameTrace changed = json.treeToValue(saved, SimulationRunner.GameTrace.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> SimulationRunner.replay(changed));
        assertTrue(error.getMessage().contains("seed=17"));
        assertTrue(error.getMessage().contains("step=0"));
    }

    @Test
    void test_replay_rejects_tampered_action() throws Exception {
        ObjectNode saved = (ObjectNode) json.valueToTree(SimulationRunner.run(17, 512));
        ObjectNode action = (ObjectNode) saved.withArray("steps").get(0).get("action");
        action.put("interpretationKey", "tampered");
        assertRejectedAt(saved, 0);
    }

    @Test
    void test_replay_rejects_tampered_public_event() throws Exception {
        ObjectNode saved = (ObjectNode) json.valueToTree(SimulationRunner.run(17, 512));
        ObjectNode event = (ObjectNode) saved.withArray("steps").get(0).get("publicEvents").get(0);
        event.put("actorId", 3);
        assertRejectedAt(saved, 0);
    }

    @Test
    void test_replay_rejects_tampered_result() throws Exception {
        ObjectNode saved = (ObjectNode) json.valueToTree(SimulationRunner.run(17, 512));
        ((ObjectNode) saved.get("result")).put("winnerTeam", 3);
        assertRejectedAt(saved, saved.withArray("steps").size());
    }

    private void assertRejectedAt(ObjectNode saved, int step) throws Exception {
        SimulationRunner.GameTrace changed = json.treeToValue(saved, SimulationRunner.GameTrace.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> SimulationRunner.replay(changed));
        assertTrue(error.getMessage().contains("seed=17"));
        assertTrue(error.getMessage().contains("step=" + step));
    }
}
