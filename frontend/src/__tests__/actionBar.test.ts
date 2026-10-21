import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ActionBar from '../components/ActionBar.vue'

const active = {
  started: true, finished: false, canAct: true, canPass: false,
  canRetry: false, selectedCount: 0, turnStatus: '轮到你出牌',
}

describe('操作区', () => {
  it('进行中重新开局需要第二步确认，并允许继续当前局', async () => {
    const w = mount(ActionBar, { props: active, attachTo: document.body })
    await w.get('button.restart').trigger('click')
    expect(w.emitted('restart')).toBeUndefined()
    expect(w.text()).toContain('结束当前局并重新开始？')
    expect(document.activeElement).toBe(w.get('button.confirm-restart').element)
    await w.get('button.cancel-restart').trigger('click')
    expect(w.text()).not.toContain('结束当前局并重新开始？')
    expect(document.activeElement).toBe(w.get('button.restart').element)
    await w.get('button.restart').trigger('click')
    await w.get('button.confirm-restart').trigger('click')
    expect(w.emitted('restart')).toHaveLength(1)
    w.unmount()
  })

  it('完成局可以直接再来一局', async () => {
    const w = mount(ActionBar, { props: { ...active, finished: true } })
    await w.get('button.restart').trigger('click')
    expect(w.emitted('restart')).toHaveLength(1)
  })

  it('显示当前回合和无法过牌的原因', () => {
    const w = mount(ActionBar, { props: active })
    expect(w.get('[role="status"]').text()).toContain('轮到你出牌')
    expect(w.text()).toContain('先从手牌中选牌')
    expect(w.text()).toContain('领出时不能过牌')
  })
})
