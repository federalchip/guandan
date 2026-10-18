import type { components } from '../api/schema'

type EventView = components['schemas']['EventView']
type MoveView = components['schemas']['MoveView']

export const KIND_LABEL: Record<string, string> = {
  SINGLE: '单张',
  PAIR: '对子',
  TRIPLE: '三张',
  FULL_HOUSE: '三带二',
  STRAIGHT: '顺子',
  PAIR_RUN: '连对',
  TRIPLE_RUN: '钢板',
  BOMB: '炸弹',
  STRAIGHT_FLUSH: '同花顺',
  JOKER_BOMB: '天王炸',
}

const RANK_LABEL: Record<number, string> = { 11: 'J', 12: 'Q', 13: 'K', 14: 'A', 15: '小王', 16: '大王' }

function seatName(actorId: unknown): string {
  if (typeof actorId !== 'number') return '桌面'
  return actorId === 0 ? '你' : `座位 ${actorId}`
}

function rankLabel(rank: unknown): string {
  if (typeof rank !== 'number') return ''
  return RANK_LABEL[rank] ?? String(rank)
}

function kindLabel(kind: unknown): string {
  return typeof kind === 'string' ? (KIND_LABEL[kind] ?? kind) : '未知牌型'
}

/** 把一个事件渲染成一行可读文本，用于动画播放时逐步呈现给玩家。 */
export function describeEvent(event: EventView): string {
  const payload = (event.payload ?? {}) as Record<string, unknown>
  switch (event.event_type) {
    case 'PLAYED': {
      const parts = [`${seatName(event.actor_id)} 出牌`, kindLabel(payload.kind)]
      if (typeof payload.length === 'number') parts.push(`${payload.length} 张`)
      const r = rankLabel(payload.main_rank)
      if (r) parts.push(`主点 ${r}`)
      return parts.join(' · ')
    }
    case 'PASSED':
      return `${seatName(event.actor_id)} 过牌`
    case 'TRICK_CLOSED': {
      const next = payload.next_leader
      return typeof next === 'number' && next >= 0 ? `本墩结束 · 由${seatName(next)}领出` : '本墩结束'
    }
    case 'PLAYER_FINISHED': {
      const pos = payload.position
      return typeof pos === 'number' ? `${seatName(event.actor_id)} 完成 · 第 ${pos} 名` : `${seatName(event.actor_id)} 完成`
    }
    case 'GAME_FINISHED':
      return '牌局结束'
    default:
      return event.event_type ?? '事件'
  }
}

/** 把桌面上的一手牌（MoveView）渲染成“牌型 · 张数 · 主点”文本，供牌桌在播放/收敛时展示。 */
export function describeMove(move: MoveView | undefined): string {
  if (!move) return ''
  const parts = [kindLabel(move.kind)]
  if (typeof move.length === 'number') parts.push(`${move.length} 张`)
  const r = rankLabel(move.main_rank)
  if (r) parts.push(`主点 ${r}`)
  return parts.join(' · ')
}
