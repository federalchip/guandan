import { expect, test } from '@playwright/test'

test('四家五张牌完整可见，出牌前后座位和牌桌高度稳定', async ({ page }) => {
  const cards = Array.from({ length: 20 }, (_, index) => ({
    card_id: index, deck_index: 0, rank: 2 + Math.floor(index / 4), suit: ['S', 'H', 'D', 'C'][index % 4],
  }))
  const base = {
    game_id: 'layout-inspection', version: 1, phase: 'IN_PROGRESS', current_player: 0,
    remaining_counts: [22, 22, 22, 22], finish_order: [], own_hand: [], can_pass: true,
    round_events: [], trick: null,
  }
  const played = {
    ...base, version: 2,
    round_events: [0, 1, 2, 3].map((seat) => ({
      seq: seat + 1, actor_id: seat, event_type: 'PLAYED',
      payload: {
        cards: cards.slice(seat * 5, seat * 5 + 5).map((card) => card.card_id),
        played_cards: cards.slice(seat * 5, seat * 5 + 5), kind: 'STRAIGHT', length: 5,
      },
    })),
    trick: { owner_id: 1, move: { kind: 'STRAIGHT', length: 5 }, cards: cards.slice(5, 10) },
  }
  let response: typeof base | typeof played = base
  await page.route('**/api/v1/games/layout-inspection', (route) => route.fulfill({ json: response }))
  await page.route('**/api/v1/games/layout-inspection/legal-actions', (route) => route.fulfill({ json: { moves: [], can_pass: true } }))
  await page.addInitScript(() => sessionStorage.setItem(
    'guandan.session', JSON.stringify({ game_id: 'layout-inspection', token: 'layout-token' }),
  ))

  for (const width of [320, 390, 560, 561, 640, 800, 1280]) {
    await page.setViewportSize({ width, height: 844 })
    response = base
    await page.goto('/')
    await expect(page.locator('.table')).toBeVisible()
    const before = await page.locator('.table').evaluate((table) => ({
      table: table.getBoundingClientRect().height,
      seats: [0, 1, 2, 3].map((seat) => table.querySelector(`[data-seat="${seat}"]`)!.getBoundingClientRect().height),
    }))

    response = played
    await page.reload()
    await expect(page.locator('[data-seat="1"] .played-cards .card')).toHaveCount(5)
    const after = await page.locator('.table').evaluate((table) => ({
      table: table.getBoundingClientRect().height,
      seats: [0, 1, 2, 3].map((seat) => table.querySelector(`[data-seat="${seat}"]`)!.getBoundingClientRect().height),
    }))
    expect(after).toEqual(before)

    for (const seat of [0, 1, 2, 3]) {
      const strip = page.locator(`[data-seat="${seat}"] .played-cards`)
      const fits = await strip.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
      expect(fits, `${width}px 下座位 ${seat} 的五张牌应完整可见`).toBe(true)
    }
    const centerFits = await page.locator('.center').evaluate((center) => {
      const bounds = center.getBoundingClientRect()
      const cards = center.querySelector('.trick-cards')!
      const stripBounds = cards.getBoundingClientRect()
      const lastCard = cards.lastElementChild!.getBoundingClientRect()
      return {
        withinCenter: lastCard.bottom <= bounds.bottom - 1,
        withinStrip: lastCard.bottom <= stripBounds.bottom - 1,
        horizontalOverflow: cards.scrollWidth - cards.clientWidth,
        verticalOverflow: cards.scrollHeight - cards.clientHeight,
      }
    })
    expect(centerFits, `${width}px 下当前有效的五张牌应完整可见`).toEqual({
      withinCenter: true, withinStrip: true, horizontalOverflow: 0, verticalOverflow: 0,
    })
    await page.getByRole('button', { name: '提示' }).click()
    await expect(page.locator('.table-actions .notice')).toHaveText('没有可用的合法动作')
    const withNotice = await page.locator('.table').evaluate((table) => table.getBoundingClientRect().height)
    expect(withNotice, `${width}px 下操作通知不应改变牌桌高度`).toBe(after.table)
    if (width === 1280) {
      expect(await page.locator('.table').evaluate((table) => table.getBoundingClientRect().width)).toBeGreaterThan(1100)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  }
})
