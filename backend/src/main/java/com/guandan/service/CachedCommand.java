package com.guandan.service;

import com.guandan.api.dto.CommandResponse;

import java.util.List;

/**
 * 幂等账本条目：请求体指纹（expected_version + action，不含 request_id）与已提交的响应。
 * 指纹为结构化列表，按位置逐元素 equals，区分 null 与字符串 "null"、避免分隔符拼接歧义。
 */
record CachedCommand(List<Object> fingerprint, CommandResponse response) {
}
