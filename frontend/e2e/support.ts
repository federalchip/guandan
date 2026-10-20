import { expect, type Page } from '@playwright/test'

export type Session = { game_id: string; token: string }

export async function openFixture(page: Page, gameId: string, token: string): Promise<Session> {
  const session = { game_id: gameId, token }
  await page.addInitScript((value) => {
    sessionStorage.setItem('guandan.session', JSON.stringify(value))
  }, session)
  await page.goto('/')
  await expect(page.locator('.table')).toBeVisible()
  return session
}

export async function currentSession(page: Page): Promise<Session> {
  return page.evaluate(() => JSON.parse(sessionStorage.getItem('guandan.session') ?? 'null') as Session)
}

export async function snapshot(page: Page, session: Session): Promise<Record<string, unknown>> {
  const response = await page.request.get(`/api/v1/games/${session.game_id}`, {
    headers: { Authorization: `Bearer ${session.token}` },
  })
  expect(response.ok()).toBeTruthy()
  return response.json() as Promise<Record<string, unknown>>
}

export async function disableAnimation(page: Page): Promise<void> {
  await page.getByRole('checkbox', { name: '动画' }).uncheck()
}
