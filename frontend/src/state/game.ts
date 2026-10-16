import { reactive } from 'vue'
import type { components } from '../api/schema'

type GameView = components['schemas']['GameView']

/**
 * T8 前端可见状态：当前局快照 + 选中牌（按 card_id 实体，绝不按 rank/suit）。
 * 快照守卫遵循前端合同（主计划 §7）：只接受同一 game_id 且 version 不小于当前已知版本的快照，
 * 旧局/他局的迟到响应一律忽略，避免覆盖新状态。
 */
export interface GameStore {
  state: {
    gameId: string | null
    token: string | null
    view: GameView | null
    selected: Set<number>
  }
  toggleSelected(cardId: number): void
  resetSelection(): void
  applySnapshot(gameId: string, view: GameView): boolean
  setSession(gameId: string, token: string): void
  clearGame(): void
}

export function createGameState(): GameStore {
  const state = reactive({
    gameId: null as string | null,
    token: null as string | null,
    view: null as GameView | null,
    selected: new Set<number>(),
  })

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

  function applySnapshot(gameId: string, view: GameView): boolean {
    if (state.gameId === null) {
      state.gameId = gameId
      state.view = view
      state.selected.clear()
      return true
    }
    if (gameId !== state.gameId) {
      return false // 旧局/他局的迟到快照
    }
    const incoming = view.version ?? 0
    const current = state.view?.version ?? 0
    if (incoming < current) {
      return false // 陈旧版本
    }
    state.view = view
    return true
  }

  function setSession(gameId: string, token: string): void {
    state.gameId = gameId
    state.token = token
  }

  function clearGame(): void {
    state.gameId = null
    state.token = null
    state.view = null
    state.selected.clear()
  }

  return { state, toggleSelected, resetSelection, applySnapshot, setSession, clearGame }
}
