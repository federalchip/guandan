import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'

const STORAGE_KEY = 'guandan.session'

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

function jsonResp(payload: unknown): Response {
  return { ok: true, status: 200, statusText: 'OK', json: async () => payload } as unknown as Response
}
function errResp(status: number, code: string, message: string): Response {
  return { ok: false, status, statusText: message, json: async () => ({ code, message }) } as unknown as Response
}

// 旧局与新局用不同的 own_hand（card_id 0 vs 48）作为页面可观察差异。
const OLD_VIEW = {
  game_id: 'old',
  version: 5,
  phase: 'IN_PROGRESS',
  current_player: 1,
  remaining_counts: [27, 27, 27, 27],
  own_hand: [{ card_id: 0, deck_index: 0, rank: 2, suit: 'S' }],
}
const NEW_VIEW = {
  game_id: 'new',
  version: 0,
  phase: 'IN_PROGRESS',
  current_player: 0,
  remaining_counts: [27, 27, 27, 27],
  own_hand: [{ card_id: 48, deck_index: 0, rank: 14, suit: 'S' }],
}
const CREATED = { game_id: 'new', session_token: 'tnew', state: NEW_VIEW }

function seedOldSession() {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ game_id: 'old', token: 'told' }))
}

// GET /games/{id} 由调用方控制完成时机；POST /games 立即返回新局。
function installFetch(getPromise: Promise<Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: unknown, init?: { method?: string }) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      if (method === 'POST' && url.endsWith('/games')) return Promise.resolve(jsonResp(CREATED))
      return getPromise
    }),
  )
}

function storedGameId(): string | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  return raw ? (JSON.parse(raw) as { game_id?: string }).game_id ?? null : null
}

async function clickStart(wrapper: ReturnType<typeof mount>) {
  const btn = wrapper.findAll('button').find((b) => b.text() === '开始一局')
  expect(btn, '开始一局 按钮应存在').toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('T8 旧局恢复竞态（迟到的旧局响应不得替换新局）', () => {
  it('test_late_old_recovery_success_does_not_replace_new_game', async () => {
    seedOldSession()
    const get = deferred<Response>()
    installFetch(get.promise)

    const wrapper = mount(App)
    await flushPromises() // recover() 已发起，GET 仍挂起

    await clickStart(wrapper) // 新局先创建成功
    expect(storedGameId()).toBe('new')

    get.resolve(jsonResp(OLD_VIEW)) // 旧局 GET 迟到成功
    await flushPromises()

    expect(wrapper.find('[data-card-id="48"]').exists()).toBe(true) // 页面仍为新局
    expect(wrapper.find('[data-card-id="0"]').exists()).toBe(false) // 不出现旧局牌
    expect(storedGameId()).toBe('new') // 存储仍指向新局
    expect(wrapper.find('.notice').exists()).toBe(false)
  })

  it('test_late_old_recovery_error_does_not_clear_new_game', async () => {
    seedOldSession()
    const get = deferred<Response>()
    installFetch(get.promise)

    const wrapper = mount(App)
    await flushPromises()

    await clickStart(wrapper)
    expect(storedGameId()).toBe('new')

    get.resolve(errResp(404, 'GAME_NOT_FOUND', '游戏不存在')) // 旧局 GET 迟到 404
    await flushPromises()

    expect(wrapper.find('[data-card-id="48"]').exists()).toBe(true) // 新局未被清除
    expect(storedGameId()).toBe('new') // 存储未被删除
    expect(wrapper.find('.notice').exists()).toBe(false) // 不显示旧请求错误
  })

  it('test_normal_recovery_restores_old_game_without_concurrent_start', async () => {
    seedOldSession()
    const get = deferred<Response>()
    installFetch(get.promise)

    const wrapper = mount(App)
    await flushPromises()

    get.resolve(jsonResp(OLD_VIEW)) // 无并发新建，正常恢复
    await flushPromises()

    expect(wrapper.find('[data-card-id="0"]').exists()).toBe(true)
    expect(storedGameId()).toBe('old')
  })

  it('test_recovery_of_invalid_old_game_shows_restart_prompt', async () => {
    seedOldSession()
    const get = deferred<Response>()
    installFetch(get.promise)

    const wrapper = mount(App)
    await flushPromises()

    get.resolve(errResp(404, 'GAME_NOT_FOUND', '游戏不存在')) // 旧局确已失效
    await flushPromises()

    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull() // 清理旧会话
    expect(wrapper.find('.notice').text()).toContain('本局已失效')
  })

  it('test_new_game_success_clears_stale_recovery_notice', async () => {
    seedOldSession()
    const get = deferred<Response>()
    const post = deferred<Response>()
    vi.stubGlobal(
      'fetch',
      vi.fn((input: unknown, init?: { method?: string }) => {
        const url = String(input)
        const method = (init?.method ?? 'GET').toUpperCase()
        if (method === 'POST' && url.endsWith('/games')) return post.promise
        return get.promise
      }),
    )

    const wrapper = mount(App)
    await flushPromises() // recover GET 在途

    // 点击“开始一局”，POST 在途；此时 sessionGen 仍为 0（成功才自增）
    await wrapper.findAll('button').find((b) => b.text() === '开始一局')!.trigger('click')

    // 旧局 GET 先返回 404 → 写入“本局已失效”提示
    get.resolve(errResp(404, 'GAME_NOT_FOUND', '游戏不存在'))
    await flushPromises()
    expect(wrapper.find('.notice').text()).toContain('本局已失效')

    // 新局 POST 随后成功 → 必须清除旧提示，只留新局牌桌
    post.resolve(jsonResp(CREATED))
    await flushPromises()
    expect(wrapper.find('[data-card-id="48"]').exists()).toBe(true)
    expect(wrapper.find('.notice').exists()).toBe(false)
    expect(storedGameId()).toBe('new')
  })

  it('test_rapid_double_click_sends_only_one_create', async () => {
    const post = deferred<Response>()
    let postCount = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((input: unknown, init?: { method?: string }) => {
        const url = String(input)
        const method = (init?.method ?? 'GET').toUpperCase()
        if (method === 'POST' && url.endsWith('/games')) {
          postCount++
          return post.promise
        }
        return Promise.resolve(jsonResp(OLD_VIEW))
      }),
    )

    const wrapper = mount(App)
    await flushPromises()

    const findStart = () => wrapper.findAll('button').find((b) => b.text() === '开始一局')!
    await findStart().trigger('click') // 第一次：POST 在途
    await findStart().trigger('click') // 第二次：应被在途限制忽略
    expect(postCount).toBe(1)

    post.resolve(jsonResp(CREATED))
    await flushPromises()
    expect(wrapper.find('[data-card-id="48"]').exists()).toBe(true)
    expect(storedGameId()).toBe('new')
  })
})
