package com.guandan.api.dto;

import java.util.List;

/** 解析响应：当前 version 与该选牌的全部有效声明 choices（多解释返回多个）。 */
public record ResolveResponse(long version, List<MoveView> choices) {
}
