package com.guandan.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.bots.BotSeeds;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameResult;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/** Four policy players exercising the same public-view boundary as the service. */
public final class SimulationRunner {
    public static final String POLICY_SEED_VERSION = "sha256-game-seed-version-seat-v1";
    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final ObjectMapper JSON = new ObjectMapper();

    private SimulationRunner() {
    }

    public record StepTrace(int actor, long versionBefore, Action action, List<DomainEvent> publicEvents) {
        public StepTrace {
            publicEvents = List.copyOf(publicEvents);
        }
    }

    public record GameTrace(long seed, String profileId, String policySeedVersion,
                            List<List<Integer>> initialHands, List<StepTrace> steps,
                            Phase finalPhase, GameResult result, double computeMs) {
        public GameTrace {
            initialHands = initialHands.stream().map(List::copyOf).toList();
            steps = List.copyOf(steps);
        }
    }

    public static final class SimulationFailure extends IllegalStateException {
        private final long seed;
        private final int step;
        private final List<List<Integer>> initialHands;
        private final List<StepTrace> completedSteps;

        private SimulationFailure(long seed, int step, String reason, Throwable cause,
                                  List<List<Integer>> initialHands, List<StepTrace> completedSteps) {
            super("seed=" + seed + " step=" + step + ": " + reason, cause);
            this.seed = seed;
            this.step = step;
            this.initialHands = List.copyOf(initialHands);
            this.completedSteps = List.copyOf(completedSteps);
        }

        public long seed() { return seed; }
        public int step() { return step; }
        public List<List<Integer>> initialHands() { return initialHands; }
        public List<StepTrace> completedSteps() { return completedSteps; }
    }

    public static GameTrace run(long seed, int maxActions) {
        if (maxActions <= 0) throw new IllegalArgumentException("maxActions must be positive");
        GameState state = GameEngine.newGame(seed, PROFILE);
        List<List<Integer>> initial = state.hands();
        List<StepTrace> steps = new ArrayList<>();
        long computeNanos = 0;
        try {
            checkState(state);
            while (state.phase() == Phase.PLAYING) {
                if (steps.size() >= maxActions) throw new IllegalStateException("max action guard " + maxActions);
                int actor = state.currentPlayer();
                long start = System.nanoTime();
                List<Action> legal = GameEngine.getLegalActions(state, actor);
                Action chosen = RandomLegalPolicy.chooseAction(
                        PlayerViewFactory.getPlayerView(state, actor), legal,
                        new SplittableRandom(BotSeeds.derive(seed, state.version(), actor)));
                if (!legal.contains(chosen)) throw new IllegalStateException("policy chose illegal action");
                EngineOutcome outcome = GameEngine.applyAction(state, actor, chosen);
                computeNanos += System.nanoTime() - start;
                checkTransition(state, outcome.state());
                steps.add(new StepTrace(actor, state.version(), chosen, outcome.events()));
                state = outcome.state();
            }
            if (state.result() == null) throw new IllegalStateException("finished game has no result");
            return new GameTrace(seed, PROFILE.profileId(), POLICY_SEED_VERSION, initial, steps,
                    state.phase(), state.result(), computeNanos / 1e6);
        } catch (RuntimeException error) {
            throw new SimulationFailure(seed, steps.size(), error.getMessage(), error, initial, steps);
        }
    }

    /** Replay exact recorded actions and compare every public event and the final result. */
    public static GameTrace replay(GameTrace recorded) {
        long seed = recorded.seed();
        if (!PROFILE.profileId().equals(recorded.profileId())
                || !POLICY_SEED_VERSION.equals(recorded.policySeedVersion())) {
            throw new IllegalStateException("seed=" + seed + " step=0: rules or policy seed version changed");
        }
        GameState state = GameEngine.newGame(seed, PROFILE);
        if (!state.hands().equals(recorded.initialHands())) {
            throw new IllegalStateException("seed=" + seed + " step=0: initial hands differ");
        }
        List<StepTrace> replayed = new ArrayList<>();
        for (int i = 0; i < recorded.steps().size(); i++) {
            StepTrace step = recorded.steps().get(i);
            try {
                if (state.phase() != Phase.PLAYING || state.currentPlayer() != step.actor()
                        || state.version() != step.versionBefore()) {
                    throw new IllegalStateException("actor, phase or version differs");
                }
                List<Action> legal = GameEngine.getLegalActions(state, step.actor());
                Action policyAction = RandomLegalPolicy.chooseAction(
                        PlayerViewFactory.getPlayerView(state, step.actor()), legal,
                        new SplittableRandom(BotSeeds.derive(seed, state.version(), step.actor())));
                if (!legal.contains(step.action()) || !policyAction.equals(step.action())) {
                    throw new IllegalStateException("recorded action differs from seeded policy");
                }
                EngineOutcome outcome = GameEngine.applyAction(state, step.actor(), step.action());
                if (!JSON.valueToTree(outcome.events()).equals(JSON.valueToTree(step.publicEvents()))) {
                    throw new IllegalStateException("public events differ");
                }
                checkTransition(state, outcome.state());
                replayed.add(new StepTrace(step.actor(), step.versionBefore(), step.action(), outcome.events()));
                state = outcome.state();
            } catch (RuntimeException error) {
                throw new IllegalStateException("seed=" + seed + " step=" + i + ": " + error.getMessage(), error);
            }
        }
        if (state.phase() != Phase.FINISHED || state.phase() != recorded.finalPhase()
                || !JSON.valueToTree(state.result()).equals(JSON.valueToTree(recorded.result()))) {
            throw new IllegalStateException("seed=" + seed + " step=" + recorded.steps().size()
                    + ": final phase or result differs");
        }
        return new GameTrace(seed, PROFILE.profileId(), POLICY_SEED_VERSION, stateFromSeed(seed).hands(),
                replayed, state.phase(), state.result(), recorded.computeMs());
    }

    private static GameState stateFromSeed(long seed) {
        return GameEngine.newGame(seed, PROFILE);
    }

    private static void checkTransition(GameState before, GameState after) {
        if (after.version() != before.version() + 1) throw new IllegalStateException("version did not increase by one");
        if (after.discarded().size() < before.discarded().size()
                || !after.discarded().containsAll(before.discarded())) {
            throw new IllegalStateException("discarded cards regressed");
        }
        checkState(after);
    }

    private static void checkState(GameState state) {
        if (state.hands().size() != 4) throw new IllegalStateException("not four hands");
        Set<Integer> seen = new HashSet<>();
        for (List<Integer> hand : state.hands()) {
            for (int id : hand) {
                if (id < 0 || id >= Deck.TOTAL_CARDS || !seen.add(id)) {
                    throw new IllegalStateException("invalid or duplicate hand card " + id);
                }
            }
        }
        for (int id : state.discarded()) {
            if (id < 0 || id >= Deck.TOTAL_CARDS || !seen.add(id)) {
                throw new IllegalStateException("invalid or overlapping discarded card " + id);
            }
        }
        if (seen.size() != Deck.TOTAL_CARDS) throw new IllegalStateException("card conservation failed");
        if (state.trick() != null && !state.discarded().containsAll(state.trick().move().cardIds())) {
            throw new IllegalStateException("trick is not in discarded");
        }
        if (state.phase() == Phase.PLAYING) {
            Integer actor = state.currentPlayer();
            if (actor == null || actor < 0 || actor >= 4 || state.hands().get(actor).isEmpty()
                    || state.finishOrder().contains(actor)) {
                throw new IllegalStateException("current player cannot act");
            }
        } else if (state.currentPlayer() != null) {
            throw new IllegalStateException("finished game has current player");
        }
    }
}
