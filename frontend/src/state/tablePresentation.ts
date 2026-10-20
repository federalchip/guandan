import type { components } from '../api/schema'

type CardView = components['schemas']['CardView']
type EventView = components['schemas']['EventView']
type GameView = components['schemas']['GameView']
type MoveView = components['schemas']['MoveView']

export interface SeatAction {
  cards: CardView[]
  move: MoveView | null
  passed: boolean
}

export type TablePresentation = SeatAction[]

export function emptyTable(): TablePresentation {
  return Array.from({ length: 4 }, () => ({ cards: [], move: null, passed: false }))
}

function payloadOf(event: EventView): Record<string, unknown> {
  return (event.payload ?? {}) as Record<string, unknown>
}

function playedCards(payload: Record<string, unknown>): CardView[] {
  if (!Array.isArray(payload.played_cards)) return []
  return payload.played_cards.filter((card): card is CardView =>
    typeof card === 'object' && card !== null && typeof card.card_id === 'number' &&
    typeof card.rank === 'number' && typeof card.suit === 'string',
  )
}

/** 本轮公开展示：每座位保留最近一次出牌，直到后端发出 TRICK_CLOSED。 */
export function applyTableEvent(table: TablePresentation, event: EventView): TablePresentation {
  if (event.event_type === 'TRICK_CLOSED') return emptyTable()
  const actor = event.actor_id
  if (typeof actor !== 'number' || actor < 0 || actor >= 4) return table
  if (event.event_type === 'PASSED') {
    return table.map((seat, i) => i === actor ? { ...seat, passed: true } : seat)
  }
  if (event.event_type !== 'PLAYED') return table
  const payload = payloadOf(event)
  const next = table.map((seat) => ({ ...seat, passed: false }))
  const ids = Array.isArray(payload.cards) ? payload.cards.filter((id): id is number => typeof id === 'number') : []
  next[actor] = {
    cards: playedCards(payload),
    move: {
      card_ids: ids,
      kind: typeof payload.kind === 'string' ? payload.kind : undefined,
      main_rank: typeof payload.main_rank === 'number' ? payload.main_rank : undefined,
      length: typeof payload.length === 'number' ? payload.length : ids.length,
    },
    passed: false,
  }
  return next
}

/** 新服务端用本轮公开事件完整恢复；兼容旧快照时至少显示当前有效出牌。 */
export function tableFromSnapshot(view: GameView): TablePresentation {
  if (view.round_events !== undefined) {
    return view.round_events.reduce(applyTableEvent, emptyTable())
  }
  const table = emptyTable()
  const trick = view.trick
  if (trick && typeof trick.owner_id === 'number' && trick.owner_id >= 0 && trick.owner_id < 4) {
    table[trick.owner_id] = { cards: trick.cards ?? [], move: trick.move ?? null, passed: false }
  }
  return table
}
