import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ResultPanel from '../components/ResultPanel.vue'

describe('结算文案', () => {
  it('显示玩家关系及胜负，不暴露队伍编号', () => {
    const w = mount(ResultPanel, { props: { result: {
      winner_team: 0, outcome: 'DOUBLE_UP', placement_groups: [[0], [2], [1, 3]],
    } } })
    expect(w.text()).toContain('你和搭档获胜')
    expect(w.text()).toContain('第 1 名：你')
    expect(w.text()).toContain('第 3 名：下家、上家')
    expect(w.text()).not.toContain('队伍 {0, 2}')
  })
})
