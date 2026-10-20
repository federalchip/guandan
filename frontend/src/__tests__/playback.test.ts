import { describe, it, expect } from 'vitest'
import { projectEvent } from '../state/playback'
import type { components } from '../api/schema'

type GameView = components['schemas']['GameView']
type EventView = components['schemas']['EventView']

function base(extra: Partial<GameView> = {}): GameView {
  return {
    game_id: 'g1',
    version: 3,
    phase: 'IN_PROGRESS',
    current_player: 0,
    is_lead: false,
    can_pass: true,
    remaining_counts: [10, 12, 14, 16],
    finish_order: [],
    own_hand: [
      { card_id: 4, deck_index: 0, rank: 3, suit: 'S' },
      { card_id: 5, deck_index: 0, rank: 3, suit: 'H' },
    ],
    ...extra,
  }
}

function ev(partial: Partial<EventView>): EventView {
  return { seq: 1, state_version: 4, payload: {}, ...partial } as EventView
}

describe('事件渐进投影（把权威 payload 投到展示态，牌桌逐步推进）', () => {
  it('test_opponent_play_updates_remaining_trick_and_turn', () => {
    const e = ev({
      event_type: 'PLAYED',
      actor_id: 1,
      state_version: 4,
      payload: { kind: 'PAIR', length: 2, main_rank: 9, remaining: 10, cards: [60, 61],
        played_cards: [
          { card_id: 60, deck_index: 1, rank: 3, suit: 'S' },
          { card_id: 61, deck_index: 1, rank: 3, suit: 'H' },
        ] } as never,
    })
    const next = projectEvent(base(), e, 2)
    expect(next.remaining_counts?.[1]).toBe(10) // 该家余牌递减
    expect(next.trick?.owner_id).toBe(1) // 桌面易主到出牌者
    expect(next.trick?.move?.kind).toBe('PAIR')
    expect(next.trick?.move?.length).toBe(2)
    expect(next.trick?.cards?.map((c) => c.card_id)).toEqual([60, 61]) // 对手公开牌面随事件显示
    expect(next.current_player).toBe(2) // 回合推进到下一动作座位
    expect(next.version).toBe(4) // 展示版本跟随事件
    expect(next.own_hand?.length).toBe(2) // 自己手牌不变
  })

  it('test_human_play_shrinks_own_hand_and_shows_faces', () => {
    const e = ev({
      event_type: 'PLAYED',
      actor_id: 0,
      payload: { kind: 'SINGLE', length: 1, remaining: 1, cards: [4] } as never,
    })
    const next = projectEvent(base(), e, 1)
    expect(next.own_hand?.map((c) => c.card_id)).toEqual([5]) // 打出的牌从手牌移除
    expect(next.trick?.cards?.map((c) => c.card_id)).toEqual([4]) // 自己的牌面可还原
    expect(next.remaining_counts?.[0]).toBe(1)
  })

  it('test_pass_advances_turn_without_touching_table', () => {
    const e = ev({ event_type: 'PASSED', actor_id: 2 })
    const b = base({ trick: { owner_id: 1, move: { kind: 'PAIR' }, cards: [] } })
    const next = projectEvent(b, e, 3)
    expect(next.current_player).toBe(3)
    expect(next.trick?.owner_id).toBe(1) // 桌面保持
    expect(next.remaining_counts).toEqual([10, 12, 14, 16])
  })

  it('test_trick_closed_clears_table_and_sets_leader', () => {
    const e = ev({ event_type: 'TRICK_CLOSED', payload: { next_leader: 3 } as never })
    const next = projectEvent(base({ trick: { owner_id: 1, cards: [] } }), e, undefined)
    expect(next.trick).toBeFalsy()
    expect(next.current_player).toBe(3) // 由 next_leader 领出
  })

  it('test_player_finished_appends_finish_order', () => {
    const e = ev({ event_type: 'PLAYER_FINISHED', actor_id: 2, payload: { position: 1 } as never })
    const next = projectEvent(base(), e, 0)
    expect(next.finish_order).toEqual([2])
  })

  it('test_game_finished_sets_phase', () => {
    const e = ev({ event_type: 'GAME_FINISHED' })
    const next = projectEvent(base(), e, undefined)
    expect(next.phase).toBe('FINISHED')
  })

  it('test_projection_does_not_mutate_base', () => {
    const b = base()
    const snapshot = JSON.stringify(b)
    projectEvent(b, ev({ event_type: 'PLAYED', actor_id: 1, payload: { remaining: 3, cards: [] } as never }), 2)
    expect(JSON.stringify(b)).toBe(snapshot) // 入参不被改写
  })
})
