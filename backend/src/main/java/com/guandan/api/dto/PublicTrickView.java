package com.guandan.api.dto;

import java.util.List;

/** 公开桌面牌视图：owner_id/move/cards（仅已公开打出的实体牌）。 */
public record PublicTrickView(int ownerId, MoveView move, List<CardView> cards) {
}
