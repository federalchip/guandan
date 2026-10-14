package com.guandan.rules;

import com.guandan.domain.Deck;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.Move;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 测试专用：把 Move 规范化为语义签名 (牌型, 张数, 主比较点数, 消耗实体牌面多重集)，
 * 用于生成器与独立参照的双向集合对照。牌面 = rank+suit（同面不同副的副本签名相同）。
 */
final class MoveSigs {

    private static final Map<Integer, Card> CAT;

    static {
        Map<Integer, Card> m = new HashMap<>();
        for (Card c : Deck.buildDeck()) {
            m.put(c.cardId(), c);
        }
        CAT = Map.copyOf(m);
    }

    private MoveSigs() {
    }

    static String sig(Move m) {
        List<String> faces = new ArrayList<>();
        for (int id : m.cardIds()) {
            Card c = CAT.get(id);
            faces.add(c.rank() + "" + c.suit().name());
        }
        Collections.sort(faces);
        return m.kind().name() + "|" + m.length() + "|" + m.mainRank() + "|" + String.join(",", faces);
    }

    static Set<String> sigSet(List<Move> moves) {
        Set<String> s = new TreeSet<>();
        for (Move m : moves) {
            s.add(sig(m));
        }
        return s;
    }
}
