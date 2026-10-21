import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Card from '../components/Card.vue'

describe('王的牌面', () => {
  it('小王只显示完整点数，不出现额外的花色符号', () => {
    const w = mount(Card, { props: { card: { card_id: 52, deck_index: 0, rank: 15, suit: 'J' }, interactive: true } })
    expect(w.text()).toBe('小王')
    expect(w.get('button').attributes('aria-label')).toBe('第1副小王')
  })
})
