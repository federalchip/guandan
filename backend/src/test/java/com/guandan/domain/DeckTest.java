package com.guandan.domain;

import com.guandan.domain.model.Card;
import com.guandan.domain.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeckTest {

    @Test
    void deckHas108UniqueIds() {
        List<Card> deck = Deck.buildDeck();
        assertEquals(108, deck.size(), "two decks total 108 cards");
        Set<Integer> ids = new HashSet<>();
        for (Card c : deck) {
            assertTrue(c.cardId() >= 0 && c.cardId() <= 107, "card_id in 0..107: " + c.cardId());
            ids.add(c.cardId());
        }
        assertEquals(108, ids.size(), "all 108 card_ids are unique");
    }

    @Test
    void eachFaceHasTwoCopies() {
        List<Card> deck = Deck.buildDeck();
        Map<String, Integer> faceCount = new HashMap<>();
        for (Card c : deck) {
            faceCount.merge(c.rank() + c.suit().name(), 1, Integer::sum);
        }
        for (int rank = 2; rank <= 14; rank++) {
            for (Suit s : new Suit[] {Suit.S, Suit.H, Suit.D, Suit.C}) {
                assertEquals(2, faceCount.get(rank + s.name()),
                        "normal face has two copies: " + rank + s);
            }
        }
        assertEquals(2, faceCount.get(15 + Suit.J.name()), "two small jokers");
        assertEquals(2, faceCount.get(16 + Suit.J.name()), "two big jokers");
        assertEquals(54, faceCount.size(), "52 normal faces + 2 joker faces");
    }

    @Test
    void levelHeartsAreIds1And55() {
        List<Card> deck = Deck.buildDeck();
        List<Integer> heartTwos = new ArrayList<>();
        for (Card c : deck) {
            if (c.rank() == 2 && c.suit() == Suit.H) {
                heartTwos.add(c.cardId());
            }
        }
        Collections.sort(heartTwos);
        assertEquals(List.of(1, 55), heartTwos, "the two heart-2 wildcards are ids 1 and 55");
    }

    @Test
    void dealIsPartitionAndReproducible() {
        long seed = 12345L;
        List<List<Integer>> first = Deck.deal(seed);
        List<List<Integer>> again = Deck.deal(seed);

        assertEquals(4, first.size(), "four hands");
        List<Integer> flat = new ArrayList<>();
        for (List<Integer> hand : first) {
            assertEquals(27, hand.size(), "each hand has 27 cards");
            flat.addAll(hand);
        }
        Set<Integer> unique = new HashSet<>(flat);
        assertEquals(108, unique.size(), "hands partition 108 distinct ids");
        for (int id = 0; id < 108; id++) {
            assertTrue(unique.contains(id), "id present exactly once: " + id);
        }
        assertEquals(first, again, "same seed reproduces the same deal");

        List<List<Integer>> other = Deck.deal(seed + 1);
        assertNotEquals(first, other, "different seed yields a different deal");
    }

    @Test
    void dealDoesNotCorruptCanonicalDeck() {
        Deck.deal(999L);
        List<Card> deck = Deck.buildDeck();
        for (int i = 0; i < 108; i++) {
            assertEquals(i, deck.get(i).cardId(), "buildDeck stays in ascending id order after a deal");
        }
        List<Card> copyA = Deck.buildDeck();
        List<Card> copyB = Deck.buildDeck();
        assertEquals(copyA, copyB, "buildDeck returns independent equal copies");
    }
}
