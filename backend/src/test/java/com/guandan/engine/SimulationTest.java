package com.guandan.engine;

import com.guandan.cli.SimulationRunner;
import com.guandan.domain.model.Phase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationTest {
    @Test
    void test_100_seeded_deals_terminate() {
        for (long seed = 0; seed < 100; seed++) {
            SimulationRunner.GameTrace trace = SimulationRunner.run(seed, 512);
            assertEquals(Phase.FINISHED, trace.finalPhase(), "seed=" + seed);
            assertNotNull(trace.result(), "seed=" + seed);
            assertFalse(trace.steps().isEmpty(), "seed=" + seed);
            assertTrue(trace.steps().size() <= 512, "seed=" + seed);
            assertEquals(4, trace.initialHands().size(), "seed=" + seed);
        }
    }
}
