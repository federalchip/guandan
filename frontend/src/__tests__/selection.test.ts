import { describe, it, expect } from 'vitest'
import { createGameState } from '../state/game'
import type { components } from '../api/schema'

type GameView = components['schemas']['GameView']

// 最小 GameView fixture：生成类型字段皆可空，测试只需 game_id/version。
function view(gameId: string, version: number): GameView {
  return { game_id: gameId, version }
}

// 黑桃 A：cardId = deck*54 + (rank-2)*4 + suit；AS@0=48、AS@1=102（同面不同副）。
const AS0 = 48
const AS1 = 102

describe('T8 选牌与快照守卫', () => {
  it('test_duplicate_faces_select_independently', () => {
    const g = createGameState()
    g.toggleSelected(AS0)
    expect(g.state.selected.has(AS0)).toBe(true)
    expect(g.state.selected.has(AS1)).toBe(false)
    g.toggleSelected(AS1)
    expect(g.state.selected.has(AS1)).toBe(true)
    expect(g.state.selected.size).toBe(2)
    g.toggleSelected(AS0)
    expect(g.state.selected.has(AS0)).toBe(false)
    expect(g.state.selected.has(AS1)).toBe(true)
  })

  it('test_stale_snapshot_does_not_replace_newer_one', () => {
    const g = createGameState()
    expect(g.applySnapshot('g1', view('g1', 8))).toBe(true)
    expect(g.state.view?.version).toBe(8)
    // 版本 8→7 的迟到快照不得覆盖
    expect(g.applySnapshot('g1', view('g1', 7))).toBe(false)
    expect(g.state.view?.version).toBe(8)
    // 相等或更高版本接受
    expect(g.applySnapshot('g1', view('g1', 8))).toBe(true)
    expect(g.applySnapshot('g1', view('g1', 9))).toBe(true)
    expect(g.state.view?.version).toBe(9)
  })

  it('test_old_game_snapshot_does_not_replace_new_game', () => {
    const g = createGameState()
    expect(g.applySnapshot('g2', view('g2', 0))).toBe(true)
    // 旧局 g1 的迟到快照不得替换当前新局 g2
    expect(g.applySnapshot('g1', view('g1', 5))).toBe(false)
    expect(g.state.gameId).toBe('g2')
    expect(g.state.view?.game_id).toBe('g2')
  })

  it('test_reset_selection_clears_all', () => {
    const g = createGameState()
    g.toggleSelected(AS0)
    g.toggleSelected(AS1)
    g.resetSelection()
    expect(g.state.selected.size).toBe(0)
  })
})
