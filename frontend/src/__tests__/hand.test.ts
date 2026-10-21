import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Hand from '../components/Hand.vue'
import type { components } from '../api/schema'

type CardView = components['schemas']['CardView']

function card(cardId: number, rank: number, suit: string): CardView {
  return { card_id: cardId, deck_index: cardId >= 54 ? 1 : 0, rank, suit }
}

describe('手牌按点数竖向排列', () => {
  it('提供可聚焦的横向手牌区域和滑动提示', () => {
    const wrapper = mount(Hand, { props: {
      cards: [card(0, 2, 'S'), card(4, 3, 'S'), card(8, 4, 'S'), card(12, 5, 'S'), card(16, 6, 'S')],
      selected: new Set<number>(),
    } })
    const scroller = wrapper.find('[aria-label="我的手牌"]')
    expect(scroller.attributes('tabindex')).toBe('0')
    expect(wrapper.text()).toContain('左右滑动查看更多手牌')
  })

  it('只剩一列牌时不提示横向滑动', () => {
    const wrapper = mount(Hand, { props: { cards: [card(48, 14, 'S')], selected: new Set<number>() } })
    expect(wrapper.text()).not.toContain('左右滑动查看更多手牌')
  })

  it('将同点数牌放在同一列，并保持每张实体牌可独立选择', async () => {
    const wrapper = mount(Hand, {
      props: {
        cards: [card(48, 14, 'S'), card(102, 14, 'S'), card(0, 2, 'S'), card(54, 2, 'S')],
        selected: new Set([102]),
      },
    })

    const groups = wrapper.findAll('.rank-group')
    expect(groups).toHaveLength(2)
    expect(groups[0]!.findAll('[data-card-id]').map((c) => c.attributes('data-card-id'))).toEqual(['48', '102'])
    expect(groups[1]!.findAll('[data-card-id]').map((c) => c.attributes('data-card-id'))).toEqual(['0', '54'])
    expect(groups[0]!.find('[data-card-id="102"]').classes()).toContain('selected')
    expect(groups[0]!.find('[data-card-id="48"]').attributes('aria-label')).toBe('第1副黑桃A')
    expect(groups[0]!.find('[data-card-id="102"]').attributes('aria-label')).toBe('第2副黑桃A')
    expect(groups[0]!.find('[data-card-id="102"]').attributes('aria-pressed')).toBe('true')
    expect(groups[0]!.find('[data-card-id="48"]').attributes('aria-pressed')).toBe('false')

    await groups[0]!.find('[data-card-id="48"]').trigger('click')
    await groups[0]!.find('[data-card-id="102"]').trigger('click')
    expect(wrapper.emitted('select')).toEqual([[48], [102]])
  })
})
