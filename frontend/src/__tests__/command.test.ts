import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createGameState, generateRequestId, type GameStore, type PendingCommand } from '../state/game'
import type { GameApi } from '../api/game'
import { ApiError } from '../api/game'
import type { components } from '../api/schema'

type CreatedGame = components['schemas']['CreatedGame']
type CommandRequest = components['schemas']['CommandRequest']
type CommandResponse = components['schemas']['CommandResponse']
type ResolveResponse = components['schemas']['ResolveResponse']
type LegalActionsResponse = components['schemas']['LegalActionsResponse']
type GameView = components['schemas']['GameView']
type EventView = components['schemas']['EventView']

const SESSION_KEY = 'guandan.session'
const PENDING_KEY = 'guandan.pending'

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const flush = () => new Promise<void>((r) => setTimeout(r, 0))

function memStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
    raw: m,
  }
}

function viewOf(gameId: string, version: number, extra: Partial<GameView> = {}): GameView {
  return {
    game_id: gameId,
    version,
    phase: 'IN_PROGRESS',
    current_player: 0,
    is_lead: true,
    can_pass: false,
    remaining_counts: [27, 27, 27, 27],
    own_hand: [],
    ...extra,
  }
}

function ack(command: CommandRequest, finalVersion: number, gameId = 'g1'): CommandResponse {
  return {
    request_id: command.request_id,
    accepted_version: (command.expected_version ?? 0) + 1,
    state: viewOf(gameId, finalVersion),
    events: [],
  }
}

function makeFake() {
  const calls = {
    resolve: [] as { expectedVersion: number; cardIds: number[] }[],
    submit: [] as CommandRequest[],
    legal: 0,
    create: 0,
    get: [] as string[],
  }
  const resp = {
    resolve: async (ev: number): Promise<ResolveResponse> => ({ version: ev, choices: [] }),
    submit: async (cmd: CommandRequest): Promise<CommandResponse> => ack(cmd, (cmd.expected_version ?? 0) + 1),
    legal: async (): Promise<LegalActionsResponse> => ({ version: 0, moves: [], can_pass: false }),
    create: async (): Promise<CreatedGame> => ({ game_id: 'gNew', session_token: 'tNew', state: viewOf('gNew', 0) }),
    get: async (gid: string): Promise<GameView> => viewOf(gid, 0),
  }
  const api: GameApi = {
    createGame: () => {
      calls.create++
      return resp.create()
    },
    getGame: (gid) => {
      calls.get.push(gid)
      return resp.get(gid)
    },
    resolveSelection: (_g, _t, ev, ids) => {
      calls.resolve.push({ expectedVersion: ev, cardIds: ids })
      return resp.resolve(ev)
    },
    getLegalActions: () => {
      calls.legal++
      return resp.legal()
    },
    submitCommand: (_g, _t, cmd) => {
      calls.submit.push(cmd)
      return resp.submit(cmd)
    },
  }
  return { api, calls, resp }
}

const single = (key: string, ids: number[]): ResolveResponse => ({
  version: 0,
  choices: [{ kind: 'SINGLE', card_ids: ids, interpretation_key: key }],
})

function ev(seq: number, stateVersion: number, eventType = 'PLAYED'): EventView {
  return { seq, state_version: stateVersion, event_type: eventType, actor_id: 0, payload: {} }
}

function respWith(command: CommandRequest, finalVersion: number, events: EventView[], gameId = 'g1'): CommandResponse {
  return {
    request_id: command.request_id,
    accepted_version: (command.expected_version ?? 0) + 1,
    state: viewOf(gameId, finalVersion),
    events,
  }
}

let idSeq = 0
beforeEach(() => {
  idSeq = 0
})

function activeStore(api: GameApi, storage: ReturnType<typeof memStorage>): GameStore {
  const store = createGameState({ api, storage, newRequestId: () => `r${++idSeq}` })
  store.setSession('g1', 't1')
  store.applySnapshot('g1', viewOf('g1', 0))
  return store
}

// 动画开启 + 手动步进调度 + onEvent 观察，用于事件播放/取消用例。
function animFixture(version = 0) {
  const fake = makeFake()
  const storage = memStorage()
  const steps: (() => void)[] = []
  const played: EventView[] = []
  const store = createGameState({
    api: fake.api,
    storage,
    newRequestId: () => `r${++idSeq}`,
    animate: true,
    scheduleStep: (fn) => void steps.push(fn),
    onEvent: (e) => void played.push(e),
  })
  store.setSession('g1', 't1')
  store.applySnapshot('g1', viewOf('g1', version))
  const step = () => steps.shift()?.()
  const drain = () => {
    let guard = 0
    while (steps.length && guard++ < 200) steps.shift()!()
  }
  return { fake, storage, steps, played, store, step, drain }
}

describe('T9 声明流程', () => {
  it('test_resolve_zero_choices_does_not_submit', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async (ev) => ({ version: ev, choices: [] })
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    store.toggleSelected(9)
    await store.playSelection()
    expect(fake.calls.submit.length).toBe(0)
    expect(store.state.busy).toBe(false)
    expect(store.state.notice).toContain('不可出')
    expect(store.state.selected.size).toBe(2) // 保留可修正选牌
  })

  it('test_resolve_single_choice_submits_with_key', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].action?.interpretation_key).toBe('K1')
    expect(fake.calls.submit[0].action?.card_ids).toEqual([4])
    expect(store.state.pending).toBeNull()
    expect(store.state.busy).toBe(false)
  })

  it('test_resolve_multi_choices_waits_for_pick', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => ({
      version: 0,
      choices: [
        { kind: 'STRAIGHT', card_ids: [1, 2, 3, 4, 5], interpretation_key: 'K1' },
        { kind: 'STRAIGHT_FLUSH', card_ids: [1, 2, 3, 4, 5], interpretation_key: 'K2' },
      ],
    })
    const store = activeStore(fake.api, storage)
    ;[1, 2, 3, 4, 5].forEach((id) => store.toggleSelected(id))
    await store.playSelection()
    expect(fake.calls.submit.length).toBe(0) // 未选定不提交
    expect(store.state.choices?.length).toBe(2)
    expect(store.state.busy).toBe(true) // 弹窗期间锁定
    await store.pickDeclaration('K2')
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].action?.interpretation_key).toBe('K2')
    expect(store.state.choices).toBeNull()
  })
})

describe('T9 pending 幂等/重试/跨局', () => {
  it('test_timeout_retry_reuses_exact_command', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    let n = 0
    fake.resp.submit = (cmd) => (n++ === 0 ? Promise.reject(new Error('network timeout')) : Promise.resolve(ack(cmd, 1)))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection() // 提交#1 结果不明
    expect(store.state.pending).not.toBeNull()
    expect(store.state.busy).toBe(true) // 锁住新出牌
    const first = fake.calls.submit[0]
    await store.retryPendingCommand() // 提交#2 原样重试
    expect(fake.calls.submit.length).toBe(2)
    expect(fake.calls.submit[1]).toEqual(first) // 完全相同的命令（含 request_id）
    expect(fake.calls.submit[1].request_id).toBe(first.request_id)
    expect(store.state.pending).toBeNull()
    expect(store.state.busy).toBe(false)
  })

  it('test_double_click_only_sends_one_command', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const d = deferred<CommandResponse>()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p1 = store.playSelection()
    const p2 = store.playSelection() // 应被 busy 锁忽略
    await flush()
    expect(fake.calls.submit.length).toBe(1)
    d.resolve(ack(fake.calls.submit[0], 1))
    await flush()
    await Promise.all([p1, p2])
  })

  it('test_refresh_recovers_pending_command', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    const persisted: PendingCommand = {
      game_id: 'g1',
      command: { request_id: 'rPersisted', expected_version: 0, action: { kind: 'PASS' } },
    }
    storage.setItem(PENDING_KEY, JSON.stringify(persisted))
    fake.resp.get = async (gid) => viewOf(gid, 3)
    fake.resp.submit = async (cmd) => ack(cmd, 4)
    const store = createGameState({ api: fake.api, storage, newRequestId: () => `r${++idSeq}` })
    await store.recover()
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].request_id).toBe('rPersisted') // 原样重试
    expect(fake.calls.submit[0].action?.kind).toBe('PASS')
    expect(store.state.pending).toBeNull() // 成功后清除
  })

  it('test_delayed_response_does_not_regress_version', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const d = deferred<CommandResponse>()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p = store.playSelection()
    await flush() // 提交在途，expected_version=0
    store.applySnapshot('g1', viewOf('g1', 8)) // 期间采用更新快照 v8
    expect(store.state.view?.version).toBe(8)
    d.resolve(ack(fake.calls.submit[0], 4)) // 迟到响应 final v4
    await flush()
    await p
    expect(store.state.view?.version).toBe(8) // 不倒退
  })

  it('test_pending_command_is_bound_to_game', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new Error('timeout')) // 保留 pending
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(store.state.pending?.game_id).toBe('g1')
    // 切到另一局
    store.setSession('g2', 't2')
    store.applySnapshot('g2', viewOf('g2', 0))
    const before = fake.calls.submit.length
    await store.retryPendingCommand()
    expect(fake.calls.submit.length).toBe(before) // 跨局 pending 不提交
    expect(store.state.pending).toBeNull() // 丢弃
  })

  it('test_old_ack_cannot_clear_new_pending', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const dA = deferred<CommandResponse>()
    const dB = deferred<CommandResponse>()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => dA.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const pA = store.playSelection()
    await flush()
    const rA = fake.calls.submit[0].request_id
    expect(store.state.pending?.command.request_id).toBe(rA)
    // A 在途时另开新局（generation++，清 pending）
    await store.createGame()
    expect(store.state.pending).toBeNull()
    // 在 gNew 提交 B（在途）
    fake.resp.submit = () => dB.promise
    store.toggleSelected(48)
    const pB = store.playSelection()
    await flush()
    const rB = fake.calls.submit[1].request_id
    expect(rB).not.toBe(rA)
    expect(store.state.pending?.command.request_id).toBe(rB)
    // A 的迟到 ack 到达，不得清除 B
    dA.resolve({ request_id: rA, accepted_version: 1, state: viewOf('g1', 1), events: [] })
    await flush()
    expect(store.state.pending?.command.request_id).toBe(rB)
    dB.resolve(ack(fake.calls.submit[1], 1, 'gNew'))
    await flush()
    await Promise.all([pA, pB])
  })

  it('test_successful_new_game_clears_old_pending', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new Error('timeout'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(store.state.pending).not.toBeNull()
    expect(storage.raw.has(PENDING_KEY)).toBe(true)
    await store.createGame()
    expect(store.state.pending).toBeNull()
    expect(storage.raw.has(PENDING_KEY)).toBe(false)
  })

  it('test_failed_new_game_keeps_pending', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new Error('timeout'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(store.state.pending).not.toBeNull()
    fake.resp.create = () => Promise.reject(new Error('create failed'))
    await store.createGame()
    expect(store.state.pending).not.toBeNull() // 保留旧局 pending
    expect(store.state.gameId).toBe('g1') // 保留旧局
  })
})

describe('T9 事件播放/权威-展示分离/动画代次', () => {
  it('test_same_version_events_all_play_once', async () => {
    const fx = animFixture(0)
    fx.fake.resp.resolve = async () => single('K1', [4])
    const events = [ev(1, 1), ev(2, 1), ev(3, 1)] // 共享 state_version=1
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 1, events)
    fx.store.toggleSelected(4)
    await fx.store.playSelection()
    fx.drain()
    expect(fx.played.map((e) => e.seq)).toEqual([1, 2, 3]) // 不按版本跳过，全部各播一次
    expect(fx.store.state.display?.version).toBe(1)
    expect(fx.store.state.animating).toBe(false)
    expect(fx.store.state.busy).toBe(false)
  })

  it('test_duplicate_response_does_not_replay_events', async () => {
    const fx = animFixture(0)
    fx.fake.resp.resolve = async () => single('K1', [4])
    const events = [ev(1, 1), ev(2, 2)]
    const d1 = deferred<CommandResponse>()
    const d2 = deferred<CommandResponse>()
    let n = 0
    fx.fake.resp.submit = () => (n++ === 0 ? d1.promise : d2.promise)
    fx.store.toggleSelected(4)
    const p1 = fx.store.playSelection()
    await flush() // 提交#1 在途，pending rA
    const p2 = fx.store.retryPendingCommand() // 提交#2（同 request_id）在途
    await flush()
    const R = respWith(fx.fake.calls.submit[0], 2, events)
    d1.resolve(R)
    await flush()
    fx.drain()
    expect(fx.played.length).toBe(2)
    d2.resolve(R) // 重复（幂等缓存）响应
    await flush()
    fx.drain()
    expect(fx.played.length).toBe(2) // 不重播
    await Promise.all([p1, p2])
  })

  it('test_recovery_snapshot_suppresses_cached_events', async () => {
    const fx = animFixture(0)
    fx.fake.resp.resolve = async () => single('K1', [4])
    const d = deferred<CommandResponse>()
    fx.fake.resp.submit = () => d.promise
    fx.store.toggleSelected(4)
    const p = fx.store.playSelection()
    await flush() // 提交在途，expected_version=0
    fx.store.applySnapshot('g1', viewOf('g1', 8)) // GET 恢复到 v8（服务端已推进）
    expect(fx.store.state.display?.version).toBe(8)
    d.resolve(respWith(fx.fake.calls.submit[0], 8, [ev(5, 8)])) // 缓存响应 final v8
    await flush()
    fx.drain()
    expect(fx.played.length).toBe(0) // 已被快照覆盖，不重播
    expect(fx.store.state.display?.version).toBe(8) // 不倒退
    expect(fx.store.state.pending).toBeNull() // 仅确认 pending
    await p
  })

  it('test_new_snapshot_cancels_animation', async () => {
    const fx = animFixture(8)
    fx.fake.resp.resolve = async () => single('K1', [4])
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 12, [ev(9, 9), ev(10, 10), ev(11, 11), ev(12, 12)])
    fx.store.toggleSelected(4)
    await fx.store.playSelection()
    expect(fx.store.state.animating).toBe(true)
    expect(fx.store.state.display?.version).toBe(8) // 播放中仍为基准
    fx.step() // 播放 seq9
    expect(fx.played.map((e) => e.seq)).toEqual([9])
    fx.store.applySnapshot('g1', viewOf('g1', 12)) // 更新快照到达 → 取消动画
    expect(fx.store.state.display?.version).toBe(12)
    expect(fx.store.state.animating).toBe(false)
    fx.drain() // 剩余步为陈旧回调
    expect(fx.played.map((e) => e.seq)).toEqual([9]) // 未再播放
    expect(fx.store.state.display?.version).toBe(12)
  })

  it('test_missing_animation_base_applies_snapshot', async () => {
    const fx = animFixture(5)
    fx.fake.resp.resolve = async () => single('K1', [4])
    const d = deferred<CommandResponse>()
    fx.fake.resp.submit = () => d.promise
    fx.store.toggleSelected(4)
    const p = fx.store.playSelection() // expected_version=5
    await flush()
    fx.store.applySnapshot('g1', viewOf('g1', 6)) // 展示基准前移到 6
    d.resolve(respWith(fx.fake.calls.submit[0], 7, [ev(6, 6), ev(7, 7)])) // 基准 6≠expected 5
    await flush()
    fx.drain()
    expect(fx.played.length).toBe(0) // 无匹配起点不播放残缺序列
    expect(fx.store.state.display?.version).toBe(7) // 直接采用较新最终快照
    await p
  })

  it('test_cancelled_callback_cannot_mutate_view', async () => {
    const fx = animFixture(8)
    fx.fake.resp.resolve = async () => single('K1', [4])
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 12, [ev(9, 9), ev(10, 10), ev(11, 11), ev(12, 12)])
    fx.store.toggleSelected(4)
    await fx.store.playSelection() // 已调度首步，display 仍 v8
    fx.store.applySnapshot('g1', viewOf('g1', 13)) // 取消动画，采用 v13
    expect(fx.store.state.display?.version).toBe(13)
    fx.step() // 触发一个陈旧回调
    expect(fx.store.state.display?.version).toBe(13) // 不得回写
    expect(fx.played.length).toBe(0)
  })

  it('test_animation_blocks_new_command', async () => {
    const fx = animFixture(0)
    fx.fake.resp.resolve = async () => single('K1', [4])
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 2, [ev(1, 1), ev(2, 2)])
    fx.store.toggleSelected(4)
    await fx.store.playSelection()
    expect(fx.store.state.animating).toBe(true)
    expect(fx.store.state.busy).toBe(true)
    const resolvesBefore = fx.fake.calls.resolve.length
    const submitsBefore = fx.fake.calls.submit.length
    fx.store.toggleSelected(8)
    await fx.store.playSelection() // 播放期间禁止
    await fx.store.passTurn() // 播放期间禁止
    expect(fx.fake.calls.resolve.length).toBe(resolvesBefore)
    expect(fx.fake.calls.submit.length).toBe(submitsBefore)
    fx.drain()
    expect(fx.store.state.busy).toBe(false)
  })
})

describe('T9 提示/过牌/断网/非法动作', () => {
  it('test_hint_selects_only_backend_entity_ids', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.legal = async () => ({
      version: 0,
      moves: [
        { kind: 'SINGLE', card_ids: [4], interpretation_key: 'h1' },
        { kind: 'PAIR', card_ids: [10, 11], interpretation_key: 'h2' },
      ],
      can_pass: true,
    })
    const store = activeStore(fake.api, storage)
    store.toggleSelected(99) // 无关选择应被替换
    await store.cycleHint()
    expect([...store.state.selected]).toEqual([4]) // 只用后端给出的实体 ID
    await store.cycleHint()
    expect([...store.state.selected].sort((a, b) => a - b)).toEqual([10, 11]) // 循环到下一个
    expect(store.state.canPass).toBe(true)
  })

  it('test_hint_cycles_back_to_first', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.legal = async () => ({
      version: 0,
      moves: [
        { kind: 'SINGLE', card_ids: [4], interpretation_key: 'h1' },
        { kind: 'SINGLE', card_ids: [5], interpretation_key: 'h2' },
      ],
      can_pass: false,
    })
    const store = activeStore(fake.api, storage)
    await store.cycleHint()
    expect([...store.state.selected]).toEqual([4])
    await store.cycleHint()
    expect([...store.state.selected]).toEqual([5])
    await store.cycleHint()
    expect([...store.state.selected]).toEqual([4]) // 再次提示回到第一个
  })

  it('test_lead_disables_pass', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const store = activeStore(fake.api, storage) // viewOf 默认 can_pass:false（领出）
    expect(store.state.canPass).toBe(false)
    await store.passTurn()
    expect(fake.calls.submit.length).toBe(0)
    expect(store.state.notice).toContain('过牌')
  })

  it('test_pass_submits_when_allowed', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const store = createGameState({ api: fake.api, storage, newRequestId: () => `r${++idSeq}` })
    store.setSession('g1', 't1')
    store.applySnapshot('g1', viewOf('g1', 0, { can_pass: true, is_lead: false, current_player: 0 }))
    expect(store.state.canPass).toBe(true)
    await store.passTurn()
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].action?.kind).toBe('PASS')
  })

  it('test_invalid_move_keeps_fixable_selection', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(400, 'INVALID_MOVE', '压不过桌面'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(store.state.selected.has(4)).toBe(true) // 保留可修正选牌
    expect(store.state.pending).toBeNull() // 业务拒绝清除对应 pending
    expect(store.state.busy).toBe(false)
    expect(store.state.notice).toContain('INVALID_MOVE')
  })

  it('test_offline_keeps_view_and_pending', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new Error('network down'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const before = store.state.display
    await store.playSelection()
    expect(store.state.display).toBe(before) // 断网保留视图
    expect(store.state.view).not.toBeNull()
    expect(store.state.pending).not.toBeNull() // 保留 pending 供重试
    expect(store.state.busy).toBe(true) // 锁住新出牌/过牌
    expect(store.state.notice).toContain('网络异常')
  })
})

describe('T9 评审修复：重试锁/重复提交/404/UUID', () => {
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

  it('test_request_id_fallback_is_valid_uuid', () => {
    const orig = globalThis.crypto
    try {
      // 降级一：有 getRandomValues 但无 randomUUID
      vi.stubGlobal('crypto', { getRandomValues: orig.getRandomValues.bind(orig) })
      expect(generateRequestId()).toMatch(UUID_RE)
      // 降级二：crypto 无 getRandomValues → Math.random 回退，仍须是合法 UUID
      vi.stubGlobal('crypto', {})
      expect(generateRequestId()).toMatch(UUID_RE)
      // 默认路径（有 randomUUID）
      vi.unstubAllGlobals()
      expect(generateRequestId()).toMatch(UUID_RE)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('test_multi_declaration_double_pick_submits_once', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => ({
      version: 0,
      choices: [
        { kind: 'STRAIGHT', card_ids: [1, 2, 3, 4, 5], interpretation_key: 'K1' },
        { kind: 'STRAIGHT_FLUSH', card_ids: [1, 2, 3, 4, 5], interpretation_key: 'K2' },
      ],
    })
    const store = activeStore(fake.api, storage)
    ;[1, 2, 3, 4, 5].forEach((id) => store.toggleSelected(id))
    await store.playSelection()
    expect(store.state.choices?.length).toBe(2)
    // 连续触发选定：第二次必须被忽略，不得为同一选牌生成第二个 request_id
    const p1 = store.pickDeclaration('K1')
    const p2 = store.pickDeclaration('K2')
    await Promise.all([p1, p2])
    await flush()
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].action?.interpretation_key).toBe('K1')
  })

  it('test_resolve_404_invalidates_session', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    fake.resp.resolve = () => Promise.reject(new ApiError(404, 'GAME_NOT_FOUND', 'missing'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    expect(store.state.notice).toBe('本局已失效，请重新开始') // 非普通错误文本
    expect(store.state.gameId).toBeNull() // 旧局已清除
    expect(storage.raw.has(SESSION_KEY)).toBe(false) // 会话已清理
    expect(store.state.busy).toBe(false)
  })

  it('test_hint_404_invalidates_session', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    fake.resp.legal = () => Promise.reject(new ApiError(404, 'GAME_NOT_FOUND', 'missing'))
    const store = activeStore(fake.api, storage)
    await store.cycleHint()
    expect(store.state.notice).toBe('本局已失效，请重新开始')
    expect(store.state.gameId).toBeNull()
    expect(storage.raw.has(SESSION_KEY)).toBe(false)
  })

  it('test_awaiting_retry_flag_only_after_unknown_outcome', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    const d = deferred<CommandResponse>()
    fake.resp.submit = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p = store.playSelection()
    await flush()
    expect(store.state.pending).not.toBeNull()
    expect(store.state.awaitingRetry).toBe(false) // 正常在途请求不暴露重试
    d.reject(new Error('network'))
    await flush()
    await p
    expect(store.state.awaitingRetry).toBe(true) // 结果不明后才提示重试
  })

  it('test_stale_error_after_concurrent_success_does_not_relock', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    const d1 = deferred<CommandResponse>()
    const d2 = deferred<CommandResponse>()
    let n = 0
    fake.resp.submit = () => (n++ === 0 ? d1.promise : d2.promise)
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p1 = store.playSelection() // 提交#1（rA）在途
    await flush()
    const p2 = store.retryPendingCommand() // 提交#2（同 rA）在途，两条并存
    await flush()
    expect(fake.calls.submit.length).toBe(2)
    // 重试先成功：确认并清除 pending、解锁
    d2.resolve(ack(fake.calls.submit[1], 1))
    await flush()
    await p2
    expect(store.state.pending).toBeNull()
    expect(store.state.busy).toBe(false)
    expect(store.state.awaitingRetry).toBe(false)
    // 原请求随后网络错误：陈旧错误不得把 busy 重新置真、不得复活重试
    d1.reject(new Error('network late'))
    await flush()
    await p1
    expect(store.state.busy).toBe(false)
    expect(store.state.awaitingRetry).toBe(false)
    expect(store.state.pending).toBeNull()
    expect(store.state.notice ?? '').not.toContain('网络异常')
  })
})

describe('T9 复审修复：恢复/重同步/陈旧响应/动画开关', () => {
  it('test_recovery_transient_error_keeps_session_and_pending', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    const persisted: PendingCommand = {
      game_id: 'g1',
      command: { request_id: 'rP', expected_version: 0, action: { kind: 'PASS' } },
    }
    storage.setItem(PENDING_KEY, JSON.stringify(persisted))
    fake.resp.get = () => Promise.reject(new Error('network down')) // 恢复 GET 瞬时失败
    const store = createGameState({ api: fake.api, storage, newRequestId: () => `r${++idSeq}` })
    await store.recover()
    // 不得删除会话或 pending：网络恢复后仍能用原 request_id 确认
    expect(storage.raw.has(SESSION_KEY)).toBe(true)
    expect(store.state.gameId).toBe('g1')
    expect(store.state.pending?.command.request_id).toBe('rP')
    expect(store.state.awaitingRetry).toBe(true)
    expect(store.state.busy).toBe(false)
    // 网络恢复后原样重试
    fake.resp.submit = async (cmd) => ack(cmd, 1)
    await store.retryPendingCommand()
    expect(fake.calls.submit.length).toBe(1)
    expect(fake.calls.submit[0].request_id).toBe('rP')
    expect(store.state.pending).toBeNull()
  })

  it('test_recovery_404_invalidates_session', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    fake.resp.get = () => Promise.reject(new ApiError(404, 'GAME_NOT_FOUND', 'gone'))
    const store = createGameState({ api: fake.api, storage, newRequestId: () => `r${++idSeq}` })
    await store.recover()
    expect(storage.raw.has(SESSION_KEY)).toBe(false)
    expect(store.state.gameId).toBeNull()
    expect(store.state.notice).toBe('本局已失效，请重新开始')
  })

  it('test_version_conflict_locks_until_resync', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '版本冲突'))
    const resyncD = deferred<GameView>()
    fake.resp.get = () => resyncD.promise // 重同步 GET 在途
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p = store.playSelection()
    await flush()
    await p
    // 重同步在途：必须仍锁定，禁止基于旧快照再出牌
    expect(store.state.busy).toBe(true)
    const resolvesBefore = fake.calls.resolve.length
    await store.playSelection()
    expect(fake.calls.resolve.length).toBe(resolvesBefore) // 被 busy 拦下
    // 重同步返回权威快照 → 解锁并采用
    resyncD.resolve(viewOf('g1', 5))
    await flush()
    expect(store.state.view?.version).toBe(5)
    expect(store.state.busy).toBe(false)
  })

  it('test_resync_404_invalidates_session', async () => {
    const fake = makeFake()
    const storage = memStorage()
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '版本冲突'))
    fake.resp.get = () => Promise.reject(new ApiError(404, 'GAME_NOT_FOUND', 'gone'))
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    await flush()
    expect(store.state.gameId).toBeNull()
    expect(storage.raw.has(SESSION_KEY)).toBe(false)
    expect(store.state.notice).toBe('本局已失效，请重新开始')
    expect(store.state.busy).toBe(false)
  })

  it('test_stale_response_does_not_clear_new_selection', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    const d = deferred<CommandResponse>()
    fake.resp.submit = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    const p = store.playSelection() // 提交在途 expected v0
    await flush()
    store.applySnapshot('g1', viewOf('g1', 8)) // 较新快照已采用
    store.resetSelection()
    store.toggleSelected(20) // 用户基于新快照重新选牌
    store.toggleSelected(21)
    d.resolve(ack(fake.calls.submit[0], 4)) // 陈旧响应 final v4 < 8
    await flush()
    await p
    expect(store.state.view?.version).toBe(8) // 不倒退
    expect(store.state.selected.has(20)).toBe(true) // 新选牌未被陈旧响应清掉
    expect(store.state.selected.has(21)).toBe(true)
    expect(store.state.busy).toBe(false) // 陈旧响应确认后释放锁
  })

  it('test_animate_toggle_disables_playback', async () => {
    const fx = animFixture(0) // 构造时 animate:true
    fx.store.setAnimate(false) // 运行时关闭动画
    fx.fake.resp.resolve = async () => single('K1', [4])
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 2, [ev(1, 1), ev(2, 2)])
    fx.store.toggleSelected(4)
    await fx.store.playSelection()
    expect(fx.played.length).toBe(0) // 关闭后不逐事件播放
    expect(fx.store.state.display?.version).toBe(2) // 直接采用最终快照
  })

  it('test_playback_projects_progressive_display', async () => {
    const fx = animFixture(0)
    fx.fake.resp.resolve = async () => single('K1', [4])
    const events: EventView[] = [
      { seq: 1, state_version: 1, event_type: 'PLAYED', actor_id: 1, payload: { kind: 'SINGLE', length: 1, remaining: 26, cards: [60] } as never },
      { seq: 2, state_version: 2, event_type: 'PASSED', actor_id: 2, payload: {} as never },
    ]
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 2, events)
    fx.store.toggleSelected(4)
    await fx.store.playSelection()
    fx.step() // 播放座位1出牌
    expect(fx.store.state.display?.remaining_counts?.[1]).toBe(26) // 余牌递减
    expect(fx.store.state.display?.trick?.owner_id).toBe(1) // 桌面易主
    expect(fx.store.state.display?.current_player).toBe(2) // 回合推进
    fx.drain()
    expect(fx.store.state.display?.version).toBe(2) // 收敛到最终快照
  })
})

describe('T9 复审第三轮：重同步断网保持锁定', () => {
  it('test_resync_network_error_keeps_locked_until_retry', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '冲突'))
    const resyncOk = deferred<GameView>()
    let getN = 0
    fake.resp.get = () => (getN++ === 0 ? Promise.reject(new Error('network')) : resyncOk.promise)
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    await flush()
    // 重同步 GET 网络失败：必须仍锁定，不得基于未同步的旧快照出牌
    expect(store.state.busy).toBe(true)
    expect(store.state.needsResync).toBe(true)
    const resolvesBefore = fake.calls.resolve.length
    await store.playSelection() // 被 busy 拦下
    expect(fake.calls.resolve.length).toBe(resolvesBefore)
    // 用户点重试：无 pending 时走重同步，再次 GET
    const p = store.retryPendingCommand()
    await flush()
    resyncOk.resolve(viewOf('g1', 9))
    await flush()
    await p
    expect(store.state.view?.version).toBe(9)
    expect(store.state.busy).toBe(false)
    expect(store.state.needsResync).toBe(false)
  })
})

describe('T9 复审第四轮：重同步防竞态 + 播放需匹配展示起点', () => {
  it('test_consecutive_retry_issues_single_resync_get', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '冲突'))
    fake.resp.get = () => Promise.reject(new Error('network')) // 首次重同步失败 → needsResync
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection()
    await flush()
    expect(store.state.needsResync).toBe(true)
    expect(store.state.busy).toBe(true)
    // 第二次重同步用挂起 GET；连续触发只应产生一个在途 GET
    const d = deferred<GameView>()
    fake.resp.get = () => d.promise
    const before = fake.calls.get.length
    const r1 = store.retryPendingCommand()
    await flush()
    const r2 = store.retryPendingCommand() // 在途期间的重复触发
    await flush()
    expect(fake.calls.get.length).toBe(before + 1) // 状态层守卫：只多一个 GET
    d.resolve(viewOf('g1', 7))
    await flush()
    await Promise.all([r1, r2])
    expect(store.state.view?.version).toBe(7)
    expect(store.state.busy).toBe(false)
    expect(store.state.needsResync).toBe(false)
  })

  it('test_stale_resync_failure_after_newer_snapshot_does_not_relock', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '冲突'))
    const d = deferred<GameView>()
    fake.resp.get = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection() // 409 → 重同步 GET 在途
    await flush()
    expect(store.state.busy).toBe(true)
    // 较新结果：另一路径采用更新快照（恢复/命令响应），解锁并前移展示代次
    store.applySnapshot('g1', viewOf('g1', 9))
    expect(store.state.busy).toBe(false)
    // 旧 GET 随后失败：过期请求不得重新上锁或改写状态
    d.reject(new Error('network late'))
    await flush()
    expect(store.state.busy).toBe(false)
    expect(store.state.needsResync).toBe(false)
    expect(store.state.view?.version).toBe(9)
  })

  it('test_resync_returning_after_new_game_does_not_write_back', async () => {
    const fake = makeFake()
    const storage = memStorage()
    fake.resp.resolve = async () => single('K1', [4])
    fake.resp.submit = () => Promise.reject(new ApiError(409, 'VERSION_CONFLICT', '冲突'))
    const d = deferred<GameView>()
    fake.resp.get = () => d.promise
    const store = activeStore(fake.api, storage)
    store.toggleSelected(4)
    await store.playSelection() // g1 的重同步 GET 在途
    await flush()
    await store.createGame() // 切换新局 gNew（generation++）
    expect(store.state.gameId).toBe('gNew')
    d.resolve(viewOf('g1', 99)) // 旧局 GET 迟到成功
    await flush()
    expect(store.state.view?.game_id).toBe('gNew') // 不回写旧局
    expect(store.state.view?.version).not.toBe(99)
  })

  it('test_retry_with_null_display_shows_final_snapshot_without_playback', async () => {
    const fake = makeFake()
    const storage = memStorage()
    const steps: (() => void)[] = []
    const played: EventView[] = []
    const store = createGameState({
      api: fake.api,
      storage,
      newRequestId: () => `r${++idSeq}`,
      animate: true,
      scheduleStep: (fn) => void steps.push(fn),
      onEvent: (e) => void played.push(e),
    })
    // 断网刷新：会话+pending 保留，恢复 GET 失败 → display=null
    storage.setItem(SESSION_KEY, JSON.stringify({ game_id: 'g1', token: 't1' }))
    const persisted: PendingCommand = {
      game_id: 'g1',
      command: { request_id: 'rP', expected_version: 0, action: { kind: 'PASS' } },
    }
    storage.setItem(PENDING_KEY, JSON.stringify(persisted))
    fake.resp.get = () => Promise.reject(new Error('network'))
    await store.recover()
    expect(store.state.display).toBeNull()
    expect(store.state.pending?.command.request_id).toBe('rP')
    // 重试成功，响应含多个事件；display=null 不能当播放起点（不得用版本 0 代替）
    fake.resp.submit = async (cmd) => respWith(cmd, 3, [ev(1, 1), ev(2, 2), ev(3, 3)])
    await store.retryPendingCommand()
    expect(store.state.display?.version).toBe(3) // 直接展示最终快照
    expect(store.state.animating).toBe(false)
    expect(played.length).toBe(0) // 未触发事件回调
    expect(steps.length).toBe(0) // 未调度播放
    expect(store.state.pending).toBeNull() // pending 已按流程确认
  })

  it('test_normal_playback_still_plays_in_order_with_display_base', async () => {
    const fx = animFixture(0) // display 存在且 version=0
    fx.fake.resp.resolve = async () => single('K1', [4])
    fx.fake.resp.submit = async (cmd) => respWith(cmd, 3, [ev(1, 1), ev(2, 2), ev(3, 3)])
    fx.store.toggleSelected(4)
    await fx.store.playSelection() // expected_version=0，display.version=0 匹配
    fx.drain()
    expect(fx.played.map((e) => e.seq)).toEqual([1, 2, 3]) // 有匹配起点 → 按序播放
    expect(fx.store.state.display?.version).toBe(3)
  })
})
