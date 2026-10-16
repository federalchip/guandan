import { describe, it, expect } from 'vitest'
import { describeEvent } from '../state/eventLabel'
import type { components } from '../api/schema'

type EventView = components['schemas']['EventView']

function ev(partial: Partial<EventView>): EventView {
  return { seq: 1, state_version: 1, payload: {}, ...partial } as EventView
}

describe('事件叙述（动画逐步播放的可读文本）', () => {
  it('test_played_describes_actor_kind_length_rank', () => {
    const text = describeEvent(
      ev({ event_type: 'PLAYED', actor_id: 2, payload: { kind: 'STRAIGHT_FLUSH', length: 5, main_rank: 14 } as never }),
    )
    expect(text).toBe('座位 2 出牌 · 同花顺 · 5 张 · 主点 A')
  })

  it('test_played_by_human_uses_you', () => {
    const text = describeEvent(ev({ event_type: 'PLAYED', actor_id: 0, payload: { kind: 'SINGLE' } as never }))
    expect(text).toContain('你 出牌')
    expect(text).toContain('单张')
  })

  it('test_passed', () => {
    expect(describeEvent(ev({ event_type: 'PASSED', actor_id: 3 }))).toBe('座位 3 过牌')
  })

  it('test_trick_closed_names_next_leader', () => {
    expect(describeEvent(ev({ event_type: 'TRICK_CLOSED', payload: { next_leader: 1 } as never }))).toBe(
      '本墩结束 · 由座位 1领出',
    )
  })

  it('test_trick_closed_without_leader', () => {
    expect(describeEvent(ev({ event_type: 'TRICK_CLOSED', payload: { next_leader: -1 } as never }))).toBe('本墩结束')
  })

  it('test_player_finished_with_position', () => {
    expect(describeEvent(ev({ event_type: 'PLAYER_FINISHED', actor_id: 2, payload: { position: 1 } as never }))).toBe(
      '座位 2 完成 · 第 1 名',
    )
  })

  it('test_game_finished', () => {
    expect(describeEvent(ev({ event_type: 'GAME_FINISHED' }))).toBe('牌局结束')
  })

  it('test_unknown_event_type_falls_back', () => {
    expect(describeEvent(ev({ event_type: 'SOMETHING_ELSE' }))).toBe('SOMETHING_ELSE')
  })
})
