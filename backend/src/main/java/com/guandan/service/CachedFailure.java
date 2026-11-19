package com.guandan.service;

import java.util.List;

/** 终态失败收据：同 ID 同体幂等返回 FAILED，不再执行旧命令。 */
record CachedFailure(List<Object> fingerprint, String code, String message) {
}