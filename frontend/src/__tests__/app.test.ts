import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'

function jsonResp(payload: unknown): Response {
  return { ok: true, status: 200, statusText: 'OK', json: async () => payload } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('动画开关（页面提供可用的关闭入口）', () => {
  it('test_toggle_present_defaults_on_and_can_be_disabled', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, status: 200, json: async () => ({}) } as unknown as Response)),
    )
    const w = mount(App)
    await flushPromises()
    const cb = w.find('.anim-toggle input[type="checkbox"]')
    expect(cb.exists()).toBe(true)
    expect((cb.element as HTMLInputElement).checked).toBe(true) // 默认开启动画
    await cb.setValue(false) // 关闭
    await flushPromises()
    expect((cb.element as HTMLInputElement).checked).toBe(false) // 绑定回显 state.animate
  })
})

describe('四边牌桌与操作区', () => {
  it('恢复牌局后在桌面中央提供操作，并显示本轮各家的公开牌', async () => {
    sessionStorage.setItem('guandan.session', JSON.stringify({ game_id: 'g1', token: 't1' }))
    const snapshot = {
      game_id: 'g1', version: 3, phase: 'IN_PROGRESS', current_player: 0,
      remaining_counts: [27, 26, 26, 27], finish_order: [], own_hand: [], can_pass: true,
      round_events: [
        { seq: 1, actor_id: 1, event_type: 'PLAYED', payload: { cards: [8], played_cards: [{ card_id: 8, rank: 4, suit: 'S' }], kind: 'SINGLE', length: 1 } },
        { seq: 2, actor_id: 2, event_type: 'PASSED', payload: {} },
      ],
      trick: { owner_id: 1, move: { kind: 'SINGLE', length: 1 }, cards: [{ card_id: 8, rank: 4, suit: 'S' }] },
    }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResp(snapshot))))
    const w = mount(App)
    await flushPromises()
    expect(w.find('.table .center .action-bar').exists()).toBe(true)
    expect(w.find('[data-seat="1"] [data-card-id="8"]').exists()).toBe(true)
    expect(w.find('[data-seat="2"] .pass-marker').text()).toBe('不出')
  })
})

describe('断网恢复后仍有可用的重试入口', () => {
  it('test_recovery_transient_error_exposes_retry_when_not_started', async () => {
    sessionStorage.setItem('guandan.session', JSON.stringify({ game_id: 'g1', token: 't1' }))
    sessionStorage.setItem(
      'guandan.pending',
      JSON.stringify({ game_id: 'g1', command: { request_id: 'rP', expected_version: 0, action: { kind: 'PASS' } } }),
    )
    const posts: string[] = []
    const ackBody = {
      request_id: 'rP',
      accepted_version: 1,
      state: {
        game_id: 'g1',
        version: 1,
        phase: 'IN_PROGRESS',
        current_player: 0,
        remaining_counts: [27, 27, 27, 27],
        own_hand: [],
        can_pass: false,
      },
      events: [],
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((input: unknown, init?: { method?: string; body?: string }) => {
        const url = String(input)
        const method = (init?.method ?? 'GET').toUpperCase()
        if (method === 'POST' && url.endsWith('/actions')) {
          posts.push(String(init?.body))
          return Promise.resolve(jsonResp(ackBody))
        }
        return Promise.reject(new Error('network down')) // 恢复 GET 失败
      }),
    )
    const w = mount(App)
    await flushPromises()
    // 无展示快照 → 页面视为“未开始”，但仍必须给出重试入口（而非只让刷新/开始新局）
    const retry = w.findAll('button').find((b) => b.text() === '重试')
    expect(retry, '未开始但有 pending 恢复时必须显示重试').toBeTruthy()
    await retry!.trigger('click')
    await flushPromises()
    expect(posts.length).toBe(1)
    expect(JSON.parse(posts[0]).request_id).toBe('rP') // 用原 request_id 原样重试
  })
})
