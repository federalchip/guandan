package com.guandan.service;

import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;
import com.guandan.rules.MoveClassifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only present on the test classpath, never packaged in the production JAR. */
@Configuration
@Profile("e2e")
public class E2eFixtureConfiguration {
    private static final RuleProfile PROFILE = RuleProfile.v1();

    @Bean
    CommandLineRunner seedBrowserGames(InMemoryGameStore store) {
        return args -> {
            store.create("e2e-short", "e2e-short-token", 42L, state(
                    List.of(CardIds.parse("S3@0"), CardIds.parse("S4@0"),
                            CardIds.parse("S5@0"), CardIds.parse("S6@0")), null));
            store.create("e2e-mobile-short", "e2e-mobile-short-token", 42L, state(
                    List.of(CardIds.parse("S3@0"), CardIds.parse("S4@0"),
                            CardIds.parse("S5@0"), CardIds.parse("S6@0")), null));
            store.create("e2e-playback", "e2e-playback-token", 42L, state(
                    List.of(CardIds.parse("S3@0"), CardIds.parse("S4@0"),
                            CardIds.parse("S5@0"), CardIds.parse("S6@0")), null));
            store.create("e2e-declare", "e2e-declare-token", 43L, state(
                    List.of(CardIds.parse("S3@0 H3@0 S4@0 H2@0 H2@1"), CardIds.parse("S5@0"),
                            CardIds.parse("S6@0"), CardIds.parse("S7@0")), null));
            store.create("e2e-mobile-declare", "e2e-mobile-declare-token", 43L, state(
                    List.of(CardIds.parse("S3@0 H3@0 S4@0 H2@0 H2@1"), CardIds.parse("S5@0"),
                            CardIds.parse("S6@0"), CardIds.parse("S7@0")), null));
            store.create("e2e-hand", "e2e-hand-token", 45L, state(
                    List.of(CardIds.parse("S3@0 H3@0 D3@0"), CardIds.parse("S4@0"),
                            CardIds.parse("S5@0"), CardIds.parse("S6@0")), null));
            var previous = MoveClassifier.classify(CardIds.parse("S5@1"), PROFILE).get(0);
            store.create("e2e-pass", "e2e-pass-token", 44L, state(
                    List.of(CardIds.parse("S3@1 H4@0"), CardIds.parse("H6@0"),
                            CardIds.parse("D7@0"), CardIds.parse("C8@0")),
                    new Trick(1, previous, Set.of(0, 2, 3))));
            store.create("e2e-mobile-pass", "e2e-mobile-pass-token", 44L, state(
                    List.of(CardIds.parse("S3@1 H4@0"), CardIds.parse("H6@0"),
                            CardIds.parse("D7@0"), CardIds.parse("C8@0")),
                    new Trick(1, previous, Set.of(0, 2, 3))));
            System.out.println("[e2e] fixed browser fixtures seeded");
        };
    }

    private static GameState state(List<List<Integer>> hands, Trick trick) {
        Set<Integer> held = new HashSet<>();
        hands.forEach(held::addAll);
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!held.contains(id)) discarded.add(id);
        }
        return new GameState(PROFILE, hands, discarded, 0, trick, List.of(), Phase.PLAYING, null, 0);
    }
}
