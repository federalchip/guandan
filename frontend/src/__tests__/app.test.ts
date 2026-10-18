import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../App.vue'

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('动画开关（页面提供可用的关闭入口）', () => {
  it('test_toggle_present_defaults_on_and_can_be_disabled', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, status: 200, json: async () => ({}) } as unknown as Response)),
    )
    const w = mount(App)
    await flushPromises()
    const cb = w.find('.anim-toggle input[type="checkbox"]')
    expect(cb.exists()).toBe(true)
    expect((cb.element as HTMLInputElement).checked).toBe(true) // 默认开启动画
    await cb.setValue(false) // 关闭
    await flushPromises()
    expect((cb.element as HTMLInputElement).checked).toBe(false) // 绑定回显 state.animate
  })
})
