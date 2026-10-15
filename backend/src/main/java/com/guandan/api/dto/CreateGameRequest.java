package com.guandan.api.dto;

/** POST /games 请求体：空对象；任何额外字段由 Jackson fail-on-unknown 拒绝为 422。 */
public record CreateGameRequest() {
}
