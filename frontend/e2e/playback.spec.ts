import { expect, test } from '@playwright/test'
import { currentSession, openFixture } from './support'

test('事件逐步展示公开牌并锁定操作；新局快照取消旧播放', async ({ page }) => {
  await page.clock.install()
  await openFixture(page, 'e2e-playback', 'e2e-playback-token')
  await page.locator('.hand .card').click()
  const action = page.waitForResponse((response) => response.url().endsWith('/actions') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '出牌' }).click()
  const body = await (await action).json()
  const botPlay = body.events.find((event: { actor_id: number; event_type: string }) =>
    event.actor_id !== 0 && event.event_type === 'PLAYED')
  expect(botPlay).toBeTruthy()

  await page.clock.runFor(1)
  await expect(page.getByTestId('playback')).toBeVisible()
  await expect(page.getByRole('button', { name: '出牌' })).toBeDisabled()
  await expect(page.locator('[data-seat="0"] .played-cards .card')).toHaveCount(1)

  // Advance browser timers only until the first bot play becomes observable.
  for (let i = 0; i < 60; i++) {
    if ((await page.getByTestId('playback').textContent())?.includes(`座位 ${botPlay.actor_id} 出牌`)) break
    await page.clock.runFor(250)
  }
  await expect(page.getByTestId('playback')).toContainText(`座位 ${botPlay.actor_id} 出牌`)
  await expect(page.locator(`[data-seat="${botPlay.actor_id}"] [data-card-id="${botPlay.payload.played_cards[0].card_id}"]`)).toBeVisible()
  await expect(page.getByRole('button', { name: '出牌' })).toBeDisabled()

  await page.getByRole('button', { name: '再来一局' }).click()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  const fresh = await currentSession(page)
  await page.clock.runFor(30_000)
  expect((await currentSession(page)).game_id).toBe(fresh.game_id)
  await expect(page.getByTestId('playback')).toBeHidden()
  await expect(page.locator('.hand .card')).toHaveCount(27)
})
