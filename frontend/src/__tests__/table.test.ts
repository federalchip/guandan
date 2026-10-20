import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Table from '../components/Table.vue'
import { emptyTable } from '../state/tablePresentation'
import type { components } from '../api/schema'

type GameView = components['schemas']['GameView']

function view(trick: GameView['trick'], extra: Partial<GameView> = {}): GameView {
  return {
    game_id: 'g1',
    version: 5,
    phase: 'IN_PROGRESS',
    current_player: 1,
    remaining_counts: [10, 26, 14, 16],
    finish_order: [],
    own_hand: [],
    trick,
    ...extra,
  } as GameView
}

describe('牌桌渲染渐进投影态', () => {
  it('四个座位分占上下左右，各家公开牌和过牌提示留在所属座位', () => {
    const actions = emptyTable()
    actions[0] = { cards: [{ card_id: 4, rank: 3, suit: 'S' }], move: { kind: 'SINGLE', length: 1 }, passed: false }
    actions[1] = { cards: [{ card_id: 8, rank: 4, suit: 'S' }], move: { kind: 'SINGLE', length: 1 }, passed: false }
    actions[2] = { cards: [], move: null, passed: true }
    const w = mount(Table, { props: { view: view({ owner_id: 1, move: { kind: 'SINGLE', length: 1 }, cards: [] }), seatActions: actions } })
    for (const seat of [0, 1, 2, 3]) expect(w.find(`[data-seat="${seat}"]`).exists()).toBe(true)
    expect(w.find('[data-seat="0"]').classes()).toContain('seat-bottom')
    expect(w.find('[data-seat="1"]').classes()).toContain('seat-left')
    expect(w.find('[data-seat="2"]').classes()).toContain('seat-top')
    expect(w.find('[data-seat="3"]').classes()).toContain('seat-right')
    expect(w.find('[data-seat="0"] [data-card-id="4"]').exists()).toBe(true)
    expect(w.find('[data-seat="1"] [data-card-id="8"]').exists()).toBe(true)
    expect(w.find('[data-seat="2"] .pass-marker').text()).toBe('不出')
  })

  it('test_renders_trick_label_and_backs_for_hidden_faces', () => {
    const w = mount(Table, {
      props: { view: view({ owner_id: 1, move: { kind: 'PAIR', length: 2, main_rank: 9 }, cards: [] }) },
    })
    expect(w.text()).toContain('出牌者座位 1')
    expect(w.text()).toContain('对子 · 2 张 · 主点 9') // 牌型/张数/主点可见
    expect(w.findAll('.card-back').length).toBe(2) // 对手牌面未知 → 两张牌背
    expect(w.text()).toContain('26 张') // 座位1余牌递减可见
  })

  it('test_renders_real_faces_without_backs', () => {
    const w = mount(Table, {
      props: {
        view: view({ owner_id: 0, move: { kind: 'SINGLE', length: 1 }, cards: [{ card_id: 4, rank: 3, suit: 'S' }] }),
      },
    })
    expect(w.findAll('.card-back').length).toBe(0)
    expect(w.find('[data-card-id="4"]').exists()).toBe(true) // 真牌面渲染
  })

  it('test_empty_trick_waits_for_lead', () => {
    const w = mount(Table, { props: { view: view(undefined) } })
    expect(w.text()).toContain('等待领出')
  })
})
