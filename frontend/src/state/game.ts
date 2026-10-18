import { reactive } from 'vue'
import type { components } from '../api/schema'
import type { GameApi } from '../api/game'
import { ApiError } from '../api/game'
import { projectEvent } from './playback'

type GameView = components['schemas']['GameView']
type MoveView = components['schemas']['MoveView']
type ActionRequest = components['schemas']['ActionRequest']
type CommandRequest = components['schemas']['CommandRequest']
type CommandResponse = components['schemas']['CommandResponse']
type EventView = components['schemas']['EventView']

/** 浏览器持久化的待确认命令封装；HTTP 请求体仍为 command（CommandRequest）。 */
export interface PendingCommand {
  game_id: string
  command: CommandRequest
}

export interface GameStoreOptions {
  api?: GameApi
  storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
  newRequestId?: () => string
  /** 动画步进调度；默认 setTimeout。测试注入手动队列以完全控制播放。 */
  scheduleStep?: (fn: () => void) => void
  /** 关闭时收到响应直接采用最终快照，不逐事件播放。默认关闭。 */
  animate?: boolean
  /** 每播放一个事件的回调（驱动 UI 动画/音效）；关闭动画时不触发。 */
  onEvent?: (event: EventView) => void
}

const SESSION_KEY = 'guandan.session'
const PENDING_KEY = 'guandan.pending'
const HUMAN_SEAT = 0
const ANIMATION_STEP_MS = 350

/**
 * 前端会话控制器（单一真源）：拥有会话生命周期（创建/恢复）、权威快照与动画展示状态、
 * 选中牌、待确认命令、声明选择、提示与交互锁。所有异步回调都校验 game_id 与本地展示代次，
 * 旧局或已取消代次的回调不得写入当前状态（主计划 §7 line295）。
 */
export interface GameStore {
  state: {
    gameId: string | null
    token: string | null
    view: GameView | null // 权威：最新已知服务端快照
    display: GameView | null // 展示：动画收敛目标，空闲时等于 view
    selected: Set<number>
    pending: PendingCommand | null
    busy: boolean // 解析/弹窗/提交/结果不明/恢复/播放期间为真，禁止另起出牌/过牌
    choices: MoveView[] | null // 多声明待选
    hintMoves: MoveView[]
    hintIndex: number
    canPass: boolean
    animating: boolean
    animate: boolean // 动画开关：关闭时命令响应直接采用最终快照，不逐事件播放
    awaitingRetry: boolean // 结果不明（5xx/网络）且 pending 仍在，向用户暴露“重试”
    needsResync: boolean // 版本冲突后重同步失败：锁定操作，暴露“重试”以重新拉取权威快照
    notice: string | null
  }
  toggleSelected(cardId: number): void
  resetSelection(): void
  setAnimate(animate: boolean): void
  applySnapshot(gameId: string, view: GameView): boolean
  setSession(gameId: string, token: string): void
  clearGame(): void
  createGame(): Promise<void>
  recover(): Promise<void>
  playSelection(): Promise<void>
  pickDeclaration(interpretationKey: string): Promise<void>
  dismissDeclaration(): void
  passTurn(): Promise<void>
  retryPendingCommand(): Promise<void>
  cycleHint(): Promise<void>
}

function defaultStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | undefined {
  return typeof sessionStorage !== 'undefined' ? sessionStorage : undefined
}

/**
 * 生成 UUID v4 字符串。后端 CommandRequest 以 UUID.fromString 校验 request_id，
 * 故任何环境下都必须产出合法 UUID；无 crypto.randomUUID 时依次降级到
 * crypto.getRandomValues、Math.random，但形态始终是 8-4-4-4-12 且版本/变体位正确。
 */
export function uuidV4(): string {
  const bytes = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8]! & 0x3f) | 0x80 // variant 10xx
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function generateRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return uuidV4()
}

export function createGameState(options: GameStoreOptions = {}): GameStore {
  const storage = options.storage ?? defaultStorage()
  const api = options.api
  const newRequestId = options.newRequestId ?? generateRequestId
  const scheduleStep = options.scheduleStep ?? ((fn: () => void) => void setTimeout(fn, ANIMATION_STEP_MS))
  const onEvent = options.onEvent

  const state = reactive({
    gameId: null as string | null,
    token: null as string | null,
    view: null as GameView | null,
    display: null as GameView | null,
    selected: new Set<number>(),
    pending: null as PendingCommand | null,
    busy: false,
    choices: null as MoveView[] | null,
    hintMoves: [] as MoveView[],
    hintIndex: -1,
    canPass: false,
    animating: false,
    animate: options.animate ?? false,
    awaitingRetry: false,
    needsResync: false,
    notice: null as string | null,
  })

  // 会话代次：每次成功建立一个新会话自增，作废所有在途旧回调。
  let generation = 0
  // 创建在途标志：连续点击“开始一局”只发一次创建。
  let creating = false
  // 多声明解析上下文：等待用户在 DeclarationPicker 选定后据此构造命令。
  let resolveContext: { gameId: string; token: string; cardIds: number[]; expectedVersion: number; gen: number } | null = null
  // 本地展示代次：每次开始播放/直接采用快照/取消动画时自增；动画步进回调据此判定是否已被取消。
  let displayGen = 0
  // 重同步防竞态：同一局只允许一个 GET 在途；resyncSeq 标识当前有效请求，过期响应一律不回写。
  let resyncInFlight = false
  let resyncSeq = 0
  // 事件去重：已完整处理（播放或被快照覆盖）的批次 request_id 与事件 seq。
  const processedRequests = new Set<string>()
  const processedSeq = new Set<number>()

  function messageOf(e: unknown): string {
    if (e instanceof ApiError) return `${e.code}：${e.message}`
    return e instanceof Error ? e.message : String(e)
  }

  function requireApi(): GameApi {
    if (!api) throw new Error('GameStore 未注入 api，无法执行会话/命令操作')
    return api
  }

  function toggleSelected(cardId: number): void {
    if (state.selected.has(cardId)) {
      state.selected.delete(cardId)
    } else {
      state.selected.add(cardId)
    }
  }

  function resetSelection(): void {
    state.selected.clear()
  }

  function setAnimate(animate: boolean): void {
    state.animate = animate
  }

  function isHumanTurn(view: GameView | null): boolean {
    return !!view && view.phase !== 'FINISHED' && view.current_player === HUMAN_SEAT
  }

  /** 直接采用快照到权威+展示，并取消任何在途动画（代次自增使旧步进回调失效）。 */
  function adopt(view: GameView): void {
    state.view = view
    state.canPass = view.can_pass ?? false
    displayGen++
    state.display = view
    state.animating = false
    state.busy = state.pending !== null
  }

  /** 采用快照（版本守卫）：仅当同局且版本不回退时更新权威与展示。 */
  function adoptIfNewer(gameId: string, snap: GameView): boolean {
    if (gameId !== state.gameId) return false
    if ((snap.version ?? 0) < (state.view?.version ?? 0)) return false
    adopt(snap)
    return true
  }

  /** 直接采用快照（创建/恢复/更新快照取消动画）：权威与展示同时落地。 */
  function applySnapshot(gameId: string, view: GameView): boolean {
    if (state.gameId === null) {
      state.gameId = gameId
      state.selected.clear()
      adopt(view)
      return true
    }
    if (gameId !== state.gameId) return false
    if ((view.version ?? 0) < (state.view?.version ?? 0)) return false
    adopt(view)
    return true
  }

  function setSession(gameId: string, token: string): void {
    state.gameId = gameId
    state.token = token
  }

  function persistSession(): void {
    if (storage && state.gameId && state.token) {
      storage.setItem(SESSION_KEY, JSON.stringify({ game_id: state.gameId, token: state.token }))
    }
  }

  function persistPending(pending: PendingCommand): void {
    state.pending = pending
    if (storage) storage.setItem(PENDING_KEY, JSON.stringify(pending))
  }

  function loadPending(): PendingCommand | null {
    if (!storage) return null
    const raw = storage.getItem(PENDING_KEY)
    if (!raw) return null
    try {
      const parsed = JSON.parse(raw) as PendingCommand
      return parsed && parsed.game_id && parsed.command ? parsed : null
    } catch {
      storage.removeItem(PENDING_KEY)
      return null
    }
  }

  /** 仅当 game_id 与 request_id 均匹配时清除 pending；旧请求迟到响应不得清除另一条待确认命令。 */
  function confirmPending(gameId: string, requestId: string | undefined): void {
    const p = state.pending
    if (p && p.game_id === gameId && p.command.request_id === requestId) {
      state.pending = null
      state.awaitingRetry = false
      if (storage) storage.removeItem(PENDING_KEY)
    }
  }

  /** 当前 pending 是否仍对应这条命令（game_id + request_id）；用于区分陈旧错误与真正结果不明。 */
  function pendingMatches(gameId: string, requestId: string | undefined): boolean {
    const p = state.pending
    return !!p && p.game_id === gameId && p.command.request_id === requestId
  }

  function dropPending(): void {
    state.pending = null
    state.awaitingRetry = false
    if (storage) storage.removeItem(PENDING_KEY)
  }

  /** 服务重启/本局失效（404）：清理会话与 pending，退回“请重新开始”。 */
  function invalidateSession(): void {
    if (storage) storage.removeItem(SESSION_KEY)
    clearGame() // 内部 dropPending → awaitingRetry=false、busy=false
    state.notice = '本局已失效，请重新开始'
  }

  /** 清除所有跨局上下文：pending、声明弹窗、提示候选、动画与事件去重状态。 */
  function resetSessionContext(): void {
    dropPending()
    state.choices = null
    resolveContext = null
    state.hintMoves = []
    state.hintIndex = -1
    state.canPass = false
    state.busy = false
    state.animating = false
    state.needsResync = false
    state.selected.clear()
    processedRequests.clear()
    processedSeq.clear()
    resyncInFlight = false // 作废任何在途重同步，避免跨局误解锁/误拦
    resyncSeq++
    displayGen++ // 取消任何在途动画回调
  }

  function clearGame(): void {
    state.gameId = null
    state.token = null
    state.view = null
    state.display = null
    resetSessionContext()
  }

  async function createGame(): Promise<void> {
    if (creating) return // 在途限制：忽略连续点击
    creating = true
    state.notice = null
    try {
      const client = requireApi()
      const created = await client.createGame()
      const { game_id: gameId, session_token: token, state: snapshot } = created
      if (!gameId || !token || !snapshot) throw new Error('创建响应缺少字段')
      generation++ // 新局已建立，作废所有在途旧局回调
      clearGame()
      setSession(gameId, token)
      applySnapshot(gameId, snapshot)
      persistSession()
      state.notice = null // 新局权威建立，清除旧局恢复在途期间写入的提示
    } catch (e) {
      state.notice = messageOf(e) // 创建失败：保留旧局及其 pending
    } finally {
      creating = false
    }
  }

  async function recover(): Promise<void> {
    if (!storage) return
    const raw = storage.getItem(SESSION_KEY)
    if (!raw) return
    let gameId = ''
    let token = ''
    try {
      const parsed = JSON.parse(raw) as { game_id?: string; token?: string }
      gameId = parsed.game_id ?? ''
      token = parsed.token ?? ''
    } catch {
      storage.removeItem(SESSION_KEY)
      return
    }
    if (!gameId || !token) return
    const savedPending = loadPending() // 刷新前可能存在的同局待确认命令
    const gen = generation
    state.busy = true
    try {
      const client = requireApi()
      const fresh = await client.getGame(gameId, token)
      if (gen !== generation) return // 期间已另开新局，丢弃迟到快照
      clearGame()
      setSession(gameId, token)
      applySnapshot(gameId, fresh)
      // 刷新恢复后：若有同局 pending，原样重试以确认是否已执行（GET 本身不能确认 request_id）。
      if (savedPending && savedPending.game_id === gameId) {
        persistPending(savedPending)
        await retryPendingCommand()
      } else {
        dropPending() // 不属于当前局的 pending 丢弃且不得提交
        state.busy = false
      }
    } catch (e) {
      if (gen !== generation) return
      if (e instanceof ApiError && e.status === 404) {
        invalidateSession() // 本局确已失效：清会话+pending
        return
      }
      // 瞬时错误（网络/5xx）：不得删除会话或 pending，保留原 request_id 供网络恢复后确认。
      setSession(gameId, token) // 建立会话以便原样重试（GET 失败时尚未采用快照）
      state.busy = false
      if (savedPending && savedPending.game_id === gameId) {
        persistPending(savedPending)
        state.awaitingRetry = true
        state.notice = '恢复失败，结果不明，请重试'
      } else {
        state.notice = messageOf(e)
      }
    }
  }

  function buildCommand(action: ActionRequest, expectedVersion: number): CommandRequest {
    return { request_id: newRequestId(), expected_version: expectedVersion, action }
  }

  /** 提交命令：发送前持久化 pending；成功仅清除匹配 pending；结果不明保留 pending 并锁定。 */
  async function submit(command: CommandRequest, gameId: string, token: string, gen: number): Promise<void> {
    persistPending({ game_id: gameId, command })
    state.busy = true
    state.awaitingRetry = false // 新一次尝试在途，撤回“重试”提示，待结果不明再置位
    try {
      const client = requireApi()
      const response: CommandResponse = await client.submitCommand(gameId, token, command)
      if (gen !== generation || gameId !== state.gameId) return // 迟到/跨局响应整体忽略
      applyCommandResponse(gameId, command, response)
    } catch (e) {
      if (gen !== generation || gameId !== state.gameId) return
      handleCommandError(gameId, command, e)
    }
  }

  function applyCommandResponse(gameId: string, command: CommandRequest, response: CommandResponse): void {
    const requestId = command.request_id
    confirmPending(gameId, requestId)
    const snap = response.state
    const events = response.events ?? []
    const expected = command.expected_version ?? 0
    const base = state.display // 动画起点：必须真实存在，display=null 不能用版本 0 代替
    const baseVersion = base?.version
    const authBefore = state.view?.version ?? 0
    const finalVersion = snap?.version ?? authBefore

    // 权威快照：版本守卫更新，绝不回退。
    if (snap && gameId === state.gameId && finalVersion >= authBefore) {
      state.view = snap
      state.canPass = snap.can_pass ?? false
    }

    // finalVersion<=authBefore 说明更新的快照已先行采用（恢复/重同步），本响应属陈旧或重复：
    // 按计划“旧响应只确认请求”，只确认 pending 并释放其持有的锁，绝不清选牌/不动展示。
    if (!(finalVersion > authBefore)) {
      if (requestId != null) processedRequests.add(requestId)
      for (const e of events) if (e.seq != null) processedSeq.add(e.seq)
      state.busy = state.animating || state.pending !== null
      return
    }

    // 播放门控（§7 line291）：开启动画、非重复批次、展示起点存在且其版本==命令 expected_version、
    // 确有可推进版本、有事件。display=null（如断网刷新后重试）无匹配起点 → 直接展示最终快照，不播放。
    const duplicate = requestId != null && processedRequests.has(requestId)
    const canPlay =
      state.animate && !!snap && gameId === state.gameId && !duplicate &&
      base != null && baseVersion === expected && finalVersion > expected && events.length > 0

    if (canPlay) {
      playBatch(gameId, requestId as string, events, snap as GameView)
    } else {
      // 不播放（关闭动画/重复/基准不匹配）：标记已处理并直接采用最终快照。
      if (requestId != null) processedRequests.add(requestId)
      for (const e of events) if (e.seq != null) processedSeq.add(e.seq)
      directAdopt()
    }

    state.selected.clear()
    state.choices = null
    resolveContext = null
    state.hintMoves = []
    state.hintIndex = -1
    state.needsResync = false // 命令已被权威接受，恢复同步态
    state.notice = null
  }

  /** 展示直接落地到权威快照，取消在途动画。 */
  function directAdopt(): void {
    displayGen++
    state.display = state.view
    state.animating = false
    state.busy = state.pending !== null
  }

  /** 逐事件播放一批（按 seq 升序、seq 去重），播完应用最终快照；步进回调按展示代次判定是否已取消。 */
  function playBatch(gameId: string, requestId: string, events: EventView[], finalSnapshot: GameView): void {
    processedRequests.add(requestId)
    const myGen = ++displayGen
    const queue = events
      .filter((e) => e.seq == null || !processedSeq.has(e.seq))
      .slice()
      .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
    state.animating = true
    state.busy = true
    let i = 0
    const step = (): void => {
      if (myGen !== displayGen) return // 已被更新快照/新局取消
      if (gameId !== state.gameId) return
      if (i < queue.length) {
        const e = queue[i++]
        if (e.seq != null) processedSeq.add(e.seq)
        // 渐进投影：把这一事件的权威字段投到展示态，牌桌随余牌/回合/桌面/手牌逐步推进
        const upcoming = queue[i]
        const nextCurrent = upcoming
          ? typeof upcoming.actor_id === 'number'
            ? upcoming.actor_id
            : undefined
          : finalSnapshot.current_player
        state.display = projectEvent(state.display ?? finalSnapshot, e, nextCurrent)
        onEvent?.(e)
        scheduleStep(step)
      } else {
        state.display = finalSnapshot // 播放完直接应用最终快照
        state.animating = false
        state.busy = state.pending !== null
      }
    }
    scheduleStep(step)
  }

  async function resync(): Promise<void> {
    const gameId = state.gameId
    const token = state.token
    if (!gameId || !token) {
      state.busy = false
      return
    }
    if (resyncInFlight) return // 同一局只允许一个 GET 在途，状态层防重复调用
    resyncInFlight = true
    const seq = ++resyncSeq
    const gen = generation
    const startDisplayGen = displayGen
    // 过期判定：已有更新的重同步、切换会话/新局、或期间采用了更新的快照（展示代次前移）。
    const stale = () => seq !== resyncSeq || gen !== generation || gameId !== state.gameId || displayGen !== startDisplayGen
    try {
      const client = requireApi()
      const fresh = await client.getGame(gameId, token)
      if (stale()) return // 过期请求的成功不得回写
      adoptIfNewer(gameId, fresh)
      state.needsResync = false
      state.busy = state.animating || state.pending !== null // 取得权威快照，解除重同步锁
    } catch (e) {
      if (stale()) return // 过期请求的失败/404 均不得改写状态（尤其不得重新上锁）
      if (e instanceof ApiError && e.status === 404) {
        invalidateSession() // 重同步发现本局已失效
        return
      }
      // 网络/5xx：保留锁并允许重试，绝不解锁到未同步的旧快照
      state.needsResync = true
      state.busy = true
      state.notice = '重同步失败，请重试'
    } finally {
      if (seq === resyncSeq) resyncInFlight = false // 仅当前有效请求解除在途标志
    }
  }

  function handleCommandError(gameId: string, command: CommandRequest, e: unknown): void {
    if (e instanceof ApiError) {
      if (e.status === 404) {
        invalidateSession()
        return
      }
      if (e.status >= 500) {
        // 结果不明（服务端错误，批次可能已回滚）：保留 pending、锁定、允许重试/恢复。
        // 若这条命令的 pending 已被并发成功清除，则这是陈旧的迟到错误，整体忽略，不得重新上锁。
        if (!pendingMatches(gameId, command.request_id)) return
        state.busy = true
        state.awaitingRetry = true
        state.notice = '结果不明，请重试或刷新恢复'
        return
      }
      // 4xx 业务拒绝：清除本命令 pending、保留可修正选牌
      confirmPending(gameId, command.request_id)
      state.notice = messageOf(e)
      if (e.code === 'VERSION_CONFLICT' || e.code === 'NOT_YOUR_TURN') {
        // 版本冲突/未到回合：保持锁定直到重同步取回权威快照，杜绝基于旧快照再次出牌
        state.busy = true
        void resync() // resync 负责最终释放 busy
        return
      }
      state.busy = false
      return
    }
    // 网络错误/超时 → 结果不明：保留 pending、锁定、允许重试（同样忽略已被并发确认的陈旧错误）。
    if (!pendingMatches(gameId, command.request_id)) return
    state.busy = true
    state.awaitingRetry = true
    state.notice = '网络异常，结果不明，请重试'
  }

  async function playSelection(): Promise<void> {
    if (state.busy) return
    const gameId = state.gameId
    const token = state.token
    if (!gameId || !token || !state.view) return
    if (!isHumanTurn(state.view)) {
      state.notice = '还没轮到你出牌'
      return
    }
    const cardIds = [...state.selected].sort((a, b) => a - b)
    if (cardIds.length === 0) {
      state.notice = '请先选择要出的牌'
      return
    }
    const expectedVersion = state.view.version ?? 0
    const gen = generation
    state.busy = true
    state.notice = null
    try {
      const client = requireApi()
      const res = await client.resolveSelection(gameId, token, expectedVersion, cardIds)
      if (gen !== generation || gameId !== state.gameId) return
      const choices = res.choices ?? []
      if (choices.length === 0) {
        state.busy = false
        state.notice = '所选牌不可出，请重新选牌' // 保留选牌供修正
        return
      }
      if (choices.length === 1) {
        await submitPlay(gameId, token, cardIds, choices[0].interpretation_key, expectedVersion, gen)
        return
      }
      // 多声明：打开选择器，保持锁定直到选定或取消
      state.choices = choices
      resolveContext = { gameId, token, cardIds, expectedVersion, gen }
    } catch (e) {
      if (gen !== generation || gameId !== state.gameId) return
      if (e instanceof ApiError && e.status === 404) {
        invalidateSession() // 解析遇到本局失效：按服务重启规则清会话，而非普通错误
        return
      }
      state.busy = false
      state.notice = messageOf(e) // 解析失败（如 INVALID_MOVE）保留选牌
    }
  }

  async function submitPlay(
    gameId: string,
    token: string,
    cardIds: number[],
    interpretationKey: string | undefined,
    expectedVersion: number,
    gen: number,
  ): Promise<void> {
    const action: ActionRequest =
      interpretationKey != null
        ? { kind: 'PLAY', card_ids: cardIds, interpretation_key: interpretationKey }
        : { kind: 'PLAY', card_ids: cardIds }
    await submit(buildCommand(action, expectedVersion), gameId, token, gen)
  }

  async function pickDeclaration(interpretationKey: string): Promise<void> {
    const ctx = resolveContext
    if (!ctx) return
    resolveContext = null // 同步清空上下文：连续/重复选定只会提交一次，杜绝同选牌生成第二个 request_id
    state.choices = null
    await submitPlay(ctx.gameId, ctx.token, ctx.cardIds, interpretationKey, ctx.expectedVersion, ctx.gen)
  }

  function dismissDeclaration(): void {
    state.choices = null
    resolveContext = null
    state.busy = false // 回到可编辑，保留选牌
  }

  async function passTurn(): Promise<void> {
    if (state.busy) return
    const gameId = state.gameId
    const token = state.token
    if (!gameId || !token || !state.view) return
    if (!isHumanTurn(state.view)) {
      state.notice = '还没轮到你出牌'
      return
    }
    if (!state.canPass) {
      state.notice = '领出时不能过牌'
      return
    }
    const expectedVersion = state.view.version ?? 0
    const gen = generation
    state.notice = null
    await submit(buildCommand({ kind: 'PASS' }, expectedVersion), gameId, token, gen)
  }

  async function retryPendingCommand(): Promise<void> {
    const p = state.pending
    if (p) {
      if (p.game_id !== state.gameId || !state.token) {
        dropPending() // 跨局 pending 不得提交
        state.busy = false
        return
      }
      // 原样重试：复用原 request_id 与原请求体
      await submit(p.command, p.game_id, state.token, generation)
      return
    }
    // 无待确认命令但处于“需重同步”锁定态（版本冲突后重同步失败）：重新拉取权威快照
    if (state.needsResync && state.gameId && state.token) {
      state.busy = true
      await resync()
    }
  }

  async function cycleHint(): Promise<void> {
    if (state.busy) return
    const gameId = state.gameId
    const token = state.token
    if (!gameId || !token || !state.view) return
    if (!isHumanTurn(state.view)) {
      state.notice = '还没轮到你出牌'
      return
    }
    const gen = generation
    state.busy = true
    state.notice = null
    try {
      const client = requireApi()
      const res = await client.getLegalActions(gameId, token)
      if (gen !== generation || gameId !== state.gameId) return
      state.hintMoves = res.moves ?? []
      state.canPass = res.can_pass ?? false
      if (state.hintMoves.length === 0) {
        state.busy = false
        state.notice = '没有可用的合法动作'
        return
      }
      state.hintIndex = (state.hintIndex + 1) % state.hintMoves.length
      const move = state.hintMoves[state.hintIndex]
      state.selected.clear()
      for (const id of move.card_ids ?? []) state.selected.add(id) // 只用后端给出的实体 ID
      state.busy = false
    } catch (e) {
      if (gen !== generation || gameId !== state.gameId) return
      if (e instanceof ApiError && e.status === 404) {
        invalidateSession() // 提示遇到本局失效：按服务重启规则清会话
        return
      }
      state.busy = false
      state.notice = messageOf(e)
    }
  }

  // 进入页面时载入可能存在的同局 pending（供刷新后原样重试）。
  state.pending = loadPending()

  return {
    state,
    toggleSelected,
    resetSelection,
    setAnimate,
    applySnapshot,
    setSession,
    clearGame,
    createGame,
    recover,
    playSelection,
    pickDeclaration,
    dismissDeclaration,
    passTurn,
    retryPendingCommand,
    cycleHint,
  }
}
