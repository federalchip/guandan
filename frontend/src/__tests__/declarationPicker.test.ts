import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import DeclarationPicker from '../components/DeclarationPicker.vue'

describe('声明选择的键盘流程', () => {
  it('进入面板后聚焦选项，Escape 取消，关闭后返回原按钮', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const host = document.createElement('div')
    document.body.append(host)
    const w = mount(DeclarationPicker, {
      attachTo: host,
      props: { choices: [{ kind: 'SINGLE', length: 1, main_rank: 9, interpretation_key: 'single-9' }] },
    })
    await nextTick()
    expect(document.activeElement).toBe(w.get('.options button').element)
    expect(w.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
    await w.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(w.emitted('cancel')).toHaveLength(1)
    w.unmount()
    expect(document.activeElement).toBe(opener)
    host.remove()
    opener.remove()
  })

  it('提交后原出牌按钮仍禁用时，把焦点交还给手牌区域', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const hand = document.createElement('div')
    hand.tabIndex = 0
    hand.setAttribute('aria-label', '我的手牌')
    document.body.append(hand)
    const host = document.createElement('div')
    document.body.append(host)
    const w = mount(DeclarationPicker, {
      attachTo: host,
      props: { choices: [{ kind: 'SINGLE', interpretation_key: 'single' }] },
    })
    await nextTick()
    opener.disabled = true
    w.unmount()
    expect(document.activeElement).toBe(hand)
    host.remove()
    hand.remove()
    opener.remove()
  })
})
