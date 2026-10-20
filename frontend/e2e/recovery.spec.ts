import { expect, test } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { currentSession, disableAnimation, snapshot } from './support'

test('双击开始只创建一局，刷新从真实后端恢复同一局', async ({ page }) => {
  await page.goto('/')
  let creates = 0
  await page.route('**/api/v1/games', async (route) => {
    if (route.request().method() === 'POST') creates++
    await route.continue()
  })
  await page.getByRole('button', { name: '开始一局' }).dblclick()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  expect(creates).toBe(1)
  const session = await currentSession(page)
  const before = await snapshot(page, session)
  await page.reload()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  expect(await currentSession(page)).toEqual(session)
  expect((await snapshot(page, session)).version).toBe(before.version)
})

test('响应丢失后刷新：原 request_id 仅确认，不重复扣牌或重播', async ({ page }) => {
  await page.clock.install()
  await page.goto('/')
  await page.getByRole('button', { name: '开始一局' }).click()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  const session = await currentSession(page)
  let firstCommand: Record<string, unknown> | null = null
  await page.route('**/actions', async (route) => {
    firstCommand = route.request().postDataJSON() as Record<string, unknown>
    const applied = await route.fetch()
    expect(applied.ok()).toBeTruthy()
    await route.abort('failed')
  }, { times: 1 })
  await page.getByRole('button', { name: '提示' }).click()
  await page.getByRole('button', { name: '出牌' }).click()
  await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
  expect(firstCommand).not.toBeNull()
  const applied = await snapshot(page, session)
  expect(applied.version).toBeGreaterThan(0)
  const pending = await page.evaluate(() => JSON.parse(sessionStorage.getItem('guandan.pending') ?? 'null'))
  expect(pending.command).toEqual(firstCommand)

  const retry = page.waitForResponse((response) => response.url().endsWith('/actions') && response.request().method() === 'POST')
  await page.reload()
  const retryResponse = await retry
  expect(retryResponse.request().postDataJSON()).toEqual(firstCommand)
  await retryResponse.body()
  await page.clock.runFor(1)
  await expect(page.getByRole('button', { name: '提示' })).toBeEnabled()
  expect((await snapshot(page, session)).version).toBe(applied.version)
  await expect(page.locator('[data-testid="playback"]')).toBeHidden()
  expect(await page.evaluate(() => sessionStorage.getItem('guandan.pending'))).toBeNull()
})

test('旧局动作响应迟到时，新局不被覆盖或带入旧 pending', async ({ page }) => {
  await page.goto('/')
  await disableAnimation(page)
  await page.getByRole('button', { name: '开始一局' }).click()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  const old = await currentSession(page)
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  let reached!: () => void
  const oldApplied = new Promise<void>((resolve) => { reached = resolve })
  await page.route('**/actions', async (route) => {
    const response = await route.fetch()
    expect(response.ok()).toBeTruthy()
    reached()
    await gate
    await route.fulfill({ response })
  }, { times: 1 })
  await page.getByRole('button', { name: '提示' }).click()
  await page.getByRole('button', { name: '出牌' }).click()
  await oldApplied
  await page.getByRole('button', { name: '再来一局' }).click()
  await expect.poll(async () => (await currentSession(page)).game_id).not.toBe(old.game_id)
  const fresh = await currentSession(page)
  const lateResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/games/${old.game_id}/actions`) && response.request().method() === 'POST')
  release()
  await (await lateResponse).body()
  await page.evaluate(() => new Promise<void>((resolve) => setTimeout(resolve, 0)))
  await expect(page.locator('.hand .card')).toHaveCount(27)
  expect(await currentSession(page)).toEqual(fresh)
  expect(await page.evaluate(() => sessionStorage.getItem('guandan.pending'))).toBeNull()
  expect((await snapshot(page, fresh)).version).toBe(0)
})

test('后端进程重启后旧会话失效，页面清除旧局', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '开始一局' }).click()
  await expect(page.locator('.hand .card')).toHaveCount(27)
  const old = await currentSession(page)
  writeFileSync(path.resolve('../backend/target/e2e-restart.signal'), crypto.randomUUID())
  await expect.poll(async () => {
    try {
      const response = await page.request.get(`/api/v1/games/${old.game_id}`, {
        headers: { Authorization: `Bearer ${old.token}` },
        timeout: 1000,
      })
      return response.status()
    } catch {
      return 0
    }
  }, { timeout: 20_000 }).toBe(404)
  await page.reload()
  await expect(page.getByText('本局已失效，请重新开始')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始一局' })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('guandan.session'))).toBeNull()
})
