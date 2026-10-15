package com.guandan.service;

import com.guandan.api.dto.CommandResponse;

/** 幂等账本条目：请求体指纹（expected_version + action，不含 request_id）与已提交的响应。 */
record CachedCommand(String fingerprint, CommandResponse response) {
}
