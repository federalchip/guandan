import type { components } from '../api/schema'

type CardView = components['schemas']['CardView']

/** v1 固定打 2，级牌为 2。 */
export const LEVEL_RANK = 2

const SUIT_WEIGHT: Record<string, number> = { S: 0, H: 1, D: 2, C: 3, J: 4 }

/**
 * 展示点数从大到小的顺序：大王、小王、级牌，其后 A 递减到 3（跳过已置顶的级牌）。
 * 仅用于前端展示排序，不改变实体身份（card_id 保持不变）。
 */
function rankOrder(levelRank: number): number[] {
  const order: number[] = [16, 15, levelRank]
  for (let r = 14; r >= 3; r--) {
    if (r !== levelRank) order.push(r)
  }
  return order
}

/**
 * 手牌展示排序：同点分组，按级牌序自大到小；同点花色 ♠>♥>♦>♣，再按 CardId 升序保证确定性。
 * 返回新数组，不修改入参（权威快照顺序保持不变）。
 */
export function sortHandForDisplay(cards: readonly CardView[], levelRank: number = LEVEL_RANK): CardView[] {
  const order = rankOrder(levelRank)
  const precedence = new Map<number, number>()
  order.forEach((r, i) => precedence.set(r, i))
  const last = order.length
  const rankWeight = (rank: number | undefined): number => (rank == null ? last : precedence.get(rank) ?? last)
  const suitWeight = (suit: string | undefined): number => (suit == null ? SUIT_WEIGHT.J : SUIT_WEIGHT[suit] ?? 99)

  return [...cards].sort((a, b) => {
    const byRank = rankWeight(a.rank) - rankWeight(b.rank)
    if (byRank !== 0) return byRank
    const bySuit = suitWeight(a.suit) - suitWeight(b.suit)
    if (bySuit !== 0) return bySuit
    return (a.card_id ?? 0) - (b.card_id ?? 0)
  })
}
