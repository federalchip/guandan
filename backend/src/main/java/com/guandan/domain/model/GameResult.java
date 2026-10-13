package com.guandan.domain.model;

import java.util.List;

/**
 * 结算结果。winnerTeam 为头游所属队索引（team0={0,2}，team1={1,3}）；
 * placementGroups 为名次组，例如 [[0],[2],[1,3]] 表示双下；outcome 为结果类型。
 */
public record GameResult(int winnerTeam, List<List<Integer>> placementGroups, Outcome outcome) {

    public GameResult {
        placementGroups = placementGroups == null
                ? List.of()
                : placementGroups.stream().map(List::copyOf).toList();
    }
}
