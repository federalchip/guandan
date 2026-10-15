package com.guandan.api.dto;

import java.util.List;

/** 合法动作响应：version、PLAY 候选 moves、can_pass；结束后 moves=[]、can_pass=false。 */
public record LegalActionsResponse(long version, List<MoveView> moves, boolean canPass) {
}
