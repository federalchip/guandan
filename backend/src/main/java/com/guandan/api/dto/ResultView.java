package com.guandan.api.dto;

import java.util.List;

/** 结算结果视图：winner_team/placement_groups/outcome。 */
public record ResultView(int winnerTeam, List<List<Integer>> placementGroups, String outcome) {
}
