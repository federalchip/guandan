import { describe, expect, it } from 'vitest'
import { applyTableEvent, emptyTable, tableFromSnapshot } from '../state/tablePresentation'
import type { components } from '../api/schema'

type EventView = components['schemas']['EventView']
type GameView = components['schemas']['GameView']

const card = (cardId: number, rank: number, suit: string) => ({ card_id: cardId, deck_index: 0, rank, suit })
function event(seq: number, type: string, actor: number | undefined, payload: Record<string, unknown> = {}): EventView {
  return { seq, event_type: type, actor_id: actor, payload: payload as never }
}

describe('本轮四座位展示', () => {
  it('保留被压过的两家公开牌，过牌不清牌，新出牌重置旧过牌提示', () => {
    const seat0 = event(1, 'PLAYED', 0, { cards: [4], played_cards: [card(4, 3, 'S')], kind: 'SINGLE', length: 1 })
    const seat1 = event(2, 'PLAYED', 1, { cards: [8], played_cards: [card(8, 4, 'S')], kind: 'SINGLE', length: 1 })
    const passed = event(3, 'PASSED', 0)
    const seat2 = event(4, 'PLAYED', 2, { cards: [12], played_cards: [card(12, 5, 'S')], kind: 'SINGLE', length: 1 })
    let table = applyTableEvent(emptyTable(), seat0)
    table = applyTableEvent(table, seat1)
    expect(table[0]?.cards.map((c) => c.card_id)).toEqual([4])
    expect(table[1]?.cards.map((c) => c.card_id)).toEqual([8])
    table = applyTableEvent(table, passed)
    expect(table[0]?.passed).toBe(true)
    expect(table[0]?.cards.map((c) => c.card_id)).toEqual([4])
    table = applyTableEvent(table, seat2)
    expect(table[0]?.passed).toBe(false)
    expect(table[0]?.cards.map((c) => c.card_id)).toEqual([4])
    expect(table[2]?.cards.map((c) => c.card_id)).toEqual([12])
    expect(applyTableEvent(table, event(5, 'TRICK_CLOSED', undefined)).every((seat) => !seat.passed && !seat.cards.length)).toBe(true)
  })

  it('刷新后从公开事件还原本轮各座位牌与过牌，旧快照仍能显示当前有效牌', () => {
    const roundEvents = [
      event(1, 'PLAYED', 1, { cards: [4], played_cards: [card(4, 3, 'S')], kind: 'SINGLE', length: 1 }),
      event(2, 'PASSED', 2),
    ]
    const view = { round_events: roundEvents } as GameView
    const table = tableFromSnapshot(view)
    expect(table[1]?.cards[0]?.card_id).toBe(4)
    expect(table[2]?.passed).toBe(true)

    const legacy = tableFromSnapshot({ trick: { owner_id: 3, move: { kind: 'PAIR', length: 2 }, cards: [] } } as GameView)
    expect(legacy[3]?.move?.kind).toBe('PAIR')
    expect(legacy[3]?.cards).toEqual([])
  })
})
