package com.guandan.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试辅助：把人工 fixture 中的牌张符号解析为实体 CardId。
 *
 * <p>符号格式：{@code <SUIT><RANK>@<DECK>}，SUIT 为 S/H/D/C，RANK 为 2-10/J/Q/K/A，DECK 为 0/1；
 * 王为 {@code JOKER_SMALL@<DECK>}、{@code JOKER_BIG@<DECK>}。例如 {@code S3@0} 为第一副黑桃 3，
 * {@code H2@0} 为红桃 2（ID 1）。{@link #parse(String)} 以空白分隔多张牌。
 */
public final class CardIds {

    private static final int CARDS_PER_DECK = 54;
    private static final int SMALL_JOKER_OFFSET = 52;
    private static final int BIG_JOKER_OFFSET = 53;

    private CardIds() {
    }

    public static List<Integer> parse(String symbols) {
        List<Integer> ids = new ArrayList<>();
        for (String token : symbols.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                ids.add(parseOne(token));
            }
        }
        return List.copyOf(ids);
    }

    public static int parseOne(String symbol) {
        int at = symbol.lastIndexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("missing @deck in card symbol: " + symbol);
        }
        int deck = Integer.parseInt(symbol.substring(at + 1));
        String face = symbol.substring(0, at);
        if (face.equals("JOKER_SMALL")) {
            return deck * CARDS_PER_DECK + SMALL_JOKER_OFFSET;
        }
        if (face.equals("JOKER_BIG")) {
            return deck * CARDS_PER_DECK + BIG_JOKER_OFFSET;
        }
        int suitIndex = switch (face.charAt(0)) {
            case 'S' -> 0;
            case 'H' -> 1;
            case 'D' -> 2;
            case 'C' -> 3;
            default -> throw new IllegalArgumentException("bad suit in card symbol: " + symbol);
        };
        int rank = switch (face.substring(1)) {
            case "J" -> 11;
            case "Q" -> 12;
            case "K" -> 13;
            case "A" -> 14;
            default -> Integer.parseInt(face.substring(1));
        };
        return deck * CARDS_PER_DECK + (rank - 2) * 4 + suitIndex;
    }
}
