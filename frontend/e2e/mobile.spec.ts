import { expect, test } from '@playwright/test'
import { disableAnimation, openFixture, snapshot } from './support'

test.describe('手机宽度的对局流程', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('320px 下可开始、提示和出牌，页面不横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 })
    await page.goto('/')
    await disableAnimation(page)
    await page.getByRole('button', { name: '开始一局' }).click()
    await expect(page.locator('.hand .card')).toHaveCount(27)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
    await page.getByRole('button', { name: '提示' }).click()
    await expect(page.locator('.hand .card.selected')).not.toHaveCount(0)
    await page.getByRole('button', { name: '出牌' }).click()
    await expect.poll(() => page.locator('.hand .card').count()).toBeLessThan(27)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
  })

  test('320px 下错误提示和重开确认出现在可见操作区', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 })
    await page.goto('/')
    await disableAnimation(page)
    await page.getByRole('button', { name: '开始一局' }).click()
    await page.locator('.hand .rank-group').first().locator('.card').first().click()
    await page.locator('.hand .rank-group').nth(1).locator('.card').first().click()
    await page.getByRole('button', { name: '出牌' }).click()
    const notice = page.locator('.table-actions .notice')
    await expect(notice).toHaveText('所选牌不可出，请重新选牌')
    await expect(notice).toBeInViewport()

    await page.getByRole('button', { name: '重新开局' }).click()
    const confirm = page.getByRole('button', { name: '结束并重新开始' })
    await expect(confirm).toBeFocused()
    await expect(confirm).toBeInViewport()
    await page.getByRole('button', { name: '继续本局' }).click()
    await expect(page.getByRole('button', { name: '重新开局' })).toBeFocused()
  })

  test('390px 下可选择声明并提交', async ({ page }) => {
    await openFixture(page, 'e2e-mobile-declare', 'e2e-mobile-declare-token')
    await disableAnimation(page)
    for (const card of await page.locator('.hand .card').all()) await card.click()
    await page.getByRole('button', { name: '出牌' }).click()
    const dialog = page.getByRole('dialog', { name: '选择声明' })
    await expect(dialog).toBeVisible()
    await dialog.locator('.options button').first().click()
    await expect(dialog).toBeHidden()
  })

  test('390px 下可过牌', async ({ page }) => {
    const fixture = await openFixture(page, 'e2e-mobile-pass', 'e2e-mobile-pass-token')
    await disableAnimation(page)
    const before = await snapshot(page, fixture)
    await expect(page.getByRole('button', { name: '过牌' })).toBeEnabled()
    await page.getByRole('button', { name: '过牌' }).click()
    await expect.poll(async () => (await snapshot(page, fixture)).version as number).toBeGreaterThan(before.version as number)
  })

  test('390px 下可看到结算并开始新局', async ({ page }) => {
    await openFixture(page, 'e2e-mobile-short', 'e2e-mobile-short-token')
    await disableAnimation(page)
    await page.locator('.hand .card').click()
    await page.getByRole('button', { name: '出牌' }).click()
    await expect(page.getByRole('heading', { name: '本局结束' })).toBeVisible()
    await page.getByRole('button', { name: '再来一局' }).click()
    await expect(page.locator('.hand .card')).toHaveCount(27)
  })
})
