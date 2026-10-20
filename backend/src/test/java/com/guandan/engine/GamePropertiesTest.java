package com.guandan.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.cli.SimulationRunner;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.Move;
import com.guandan.rules.LegalMoveGenerator;
import com.guandan.rules.MoveClassifier;
import com.guandan.rules.MoveComparator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;

/** Fixed QuickTheories seed makes generated deal/hand failures repeatable. */
class GamePropertiesTest {
    private static final long PROPERTY_SEED = 20261020L;
    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final List<Integer> TABLE = List.of(35, 89); // C10@0 C10@1

    @Test
    void test_seeded_games_preserve_cards_turns_and_versions() {
        qt().withFixedSeed(PROPERTY_SEED).withExamples(100)
                .forAll(integers().between(0, 999_999))
                .checkAssert(seed -> {
                    try {
                        SimulationRunner.run(seed, 512);
                    } catch (SimulationRunner.SimulationFailure error) {
                        saveFailure("deal", seed, error.step(), error.initialHands(), error.completedSteps());
                        throw error;
                    }
                });
    }

    @Test
    void test_generated_moves_use_owned_cards_and_valid_declarations() {
        Move previous = MoveClassifier.classify(TABLE, PROFILE).stream()
                .filter(move -> move.kind().name().equals("PAIR")).findFirst().orElseThrow();
        qt().withFixedSeed(PROPERTY_SEED).withExamples(150)
                .forAll(integers().between(0, 999_999))
                .checkAssert(seed -> {
                    List<Integer> hand = sampleHand(seed);
                    try {
                        for (Move target : new Move[] {null, previous}) {
                            List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, target, PROFILE);
                            for (Move move : moves) {
                                assertTrue(new HashSet<>(hand).containsAll(move.cardIds()), context(seed, hand, move));
                                assertTrue(new HashSet<>(move.cardIds()).size() == move.cardIds().size(),
                                        context(seed, hand, move));
                                assertTrue(MoveClassifier.classify(move.cardIds(), PROFILE).stream()
                                                .anyMatch(decl -> decl.interpretationKey().equals(move.interpretationKey())),
                                        context(seed, hand, move));
                                if (target != null) assertTrue(MoveComparator.canBeat(move, target, PROFILE),
                                        context(seed, hand, move));
                            }
                        }
                    } catch (AssertionError | RuntimeException error) {
                        saveFailure("hand", seed, -1, hand, error.getMessage());
                        throw error;
                    }
                });
    }

    private static List<Integer> sampleHand(int seed) {
        List<Integer> ids = new ArrayList<>();
        for (Card card : Deck.buildDeck()) if (!TABLE.contains(card.cardId())) ids.add(card.cardId());
        Collections.shuffle(ids, new java.util.Random(new SplittableRandom(seed).nextLong()));
        return List.copyOf(ids.subList(0, 1 + Math.floorMod(seed, 10)));
    }

    private static String context(int seed, List<Integer> hand, Move move) {
        return "property_seed=" + PROPERTY_SEED + " seed=" + seed + " hand=" + hand + " move=" + move;
    }

    private static void saveFailure(String kind, int seed, int step, Object input, Object detail) {
        try {
            Path dir = Path.of("artifacts");
            Files.createDirectories(dir);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    dir.resolve("property-failure-" + kind + "-seed-" + seed + ".json").toFile(),
                    Map.of("property_seed", PROPERTY_SEED, "seed", seed, "step", step,
                            "input", input, "detail", detail));
        } catch (Exception ignored) {
            // Keep the original assertion as the primary test failure.
        }
    }
}
