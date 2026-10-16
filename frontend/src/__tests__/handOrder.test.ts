import { describe, it, expect } from 'vitest'
import { sortHandForDisplay, LEVEL_RANK } from '../state/handOrder'
import type { components } from '../api/schema'

type CardView = components['schemas']['CardView']

// CardId 公式（见 domain/model/Card.java）：普通牌 deckIndex*54+(rank-2)*4+suitIndex；每副 52/53 为小/大王。
function card(cardId: number, rank: number, suit: string, deckIndex = 0): CardView {
  return { card_id: cardId, rank, suit, deck_index: deckIndex }
}

const BIG_JOKER = card(53, 16, 'J')
const SMALL_JOKER = card(52, 15, 'J')
const TWO_S = card(0, 2, 'S') // 级牌
const TWO_H = card(1, 2, 'H') // 级牌
const ACE_S_D0 = card(48, 14, 'S')
const ACE_S_D1 = card(102, 14, 'S', 1)
const ACE_C = card(51, 14, 'C')
const KING_D = card(46, 13, 'D')
const THREE_S = card(4, 3, 'S')

describe('T8 手牌展示排序（级牌序）', () => {
  it('test_hand_sorts_level_rank_above_ace_and_groups_descending', () => {
    // 乱序输入
    const hand = [THREE_S, ACE_C, SMALL_JOKER, TWO_H, KING_D, BIG_JOKER, ACE_S_D1, TWO_S, ACE_S_D0]
    const sorted = sortHandForDisplay(hand, LEVEL_RANK)
    // 大王 > 小王 > 级牌2(♠再♥) > A(♠ 两副按 CardId 再 ♥... 此处 ♠ 后 ♣) > K > 3
    expect(sorted.map((c) => c.card_id)).toEqual([53, 52, 0, 1, 48, 102, 51, 46, 4])
  })

  it('test_same_rank_grouped_by_suit_spade_heart_diamond_club', () => {
    const hand = [card(3, 2, 'C'), card(2, 2, 'D'), card(0, 2, 'S'), card(1, 2, 'H')]
    const sorted = sortHandForDisplay(hand, LEVEL_RANK)
    expect(sorted.map((c) => c.suit)).toEqual(['S', 'H', 'D', 'C'])
  })

  it('test_duplicate_faces_are_adjacent_and_ordered_by_card_id', () => {
    const hand = [ACE_S_D1, ACE_S_D0] // 同点同花不同副
    const sorted = sortHandForDisplay(hand, LEVEL_RANK)
    expect(sorted.map((c) => c.card_id)).toEqual([48, 102])
  })

  it('test_sort_does_not_mutate_input_array', () => {
    const hand = [THREE_S, BIG_JOKER, TWO_S]
    const before = hand.map((c) => c.card_id)
    sortHandForDisplay(hand, LEVEL_RANK)
    expect(hand.map((c) => c.card_id)).toEqual(before)
  })
})
