import type { components } from '../api/schema'

type GameView = components['schemas']['GameView']
type EventView = components['schemas']['EventView']
type CardView = components['schemas']['CardView']

const HUMAN_SEAT = 0

function payloadOf(event: EventView): Record<string, unknown> {
  return (event.payload ?? {}) as Record<string, unknown>
}

function numOf(v: unknown): number | undefined {
  return typeof v === 'number' ? v : undefined
}

/**
 * 把一个事件投影到展示态，返回新的 GameView（不改入参）。只用事件里已有的权威字段
 * （余牌数、出牌者、公开牌面、牌型/张数、next_leader、名次），不在前端重实现规则。
 * nextCurrent 为下一动作座位（末尾用最终快照的 current_player），用于推进回合高亮。
 */
export function projectEvent(base: GameView, event: EventView, nextCurrent: number | undefined): GameView {
  const payload = payloadOf(event)
  const next: GameView = {
    ...base,
    remaining_counts: [...(base.remaining_counts ?? [])],
    finish_order: [...(base.finish_order ?? [])],
  }
  if (typeof event.state_version === 'number') next.version = event.state_version
  const actor = numOf(event.actor_id)

  switch (event.event_type) {
    case 'PLAYED': {
      const cards = Array.isArray(payload.cards) ? (payload.cards as number[]) : []
      if (actor != null) {
        const remaining = numOf(payload.remaining)
        if (remaining != null) next.remaining_counts![actor] = remaining
        const publicFaces = Array.isArray(payload.played_cards)
          ? (payload.played_cards as CardView[]).filter((c) => c && typeof c.card_id === 'number')
          : []
        // 新事件直接使用后端给出的自然牌面；旧事件中的本人出牌仍可从手牌还原。
        const faces: CardView[] = publicFaces.length ? publicFaces
          : actor === HUMAN_SEAT ? (base.own_hand ?? []).filter((c) => c.card_id != null && cards.includes(c.card_id)) : []
        next.trick = {
          owner_id: actor,
          move: {
            kind: typeof payload.kind === 'string' ? payload.kind : undefined,
            main_rank: numOf(payload.main_rank),
            length: numOf(payload.length) ?? cards.length,
            card_ids: cards,
          },
          cards: faces,
        }
        if (actor === HUMAN_SEAT) {
          const played = new Set(cards)
          next.own_hand = (base.own_hand ?? []).filter((c) => c.card_id == null || !played.has(c.card_id))
        }
      }
      break
    }
    case 'TRICK_CLOSED': {
      next.trick = undefined
      const leader = numOf(payload.next_leader)
      if (leader != null && leader >= 0) next.current_player = leader
      break
    }
    case 'PLAYER_FINISHED': {
      if (actor != null && !next.finish_order!.includes(actor)) next.finish_order!.push(actor)
      break
    }
    case 'GAME_FINISHED': {
      next.phase = 'FINISHED'
      break
    }
    // PASSED：桌面与手牌不变，仅推进回合
  }

  // 回合推进：TRICK_CLOSED 已用 next_leader 设定；其余事件用下一动作座位/最终快照
  if (event.event_type !== 'TRICK_CLOSED' && nextCurrent != null) {
    next.current_player = nextCurrent
  }
  return next
}
