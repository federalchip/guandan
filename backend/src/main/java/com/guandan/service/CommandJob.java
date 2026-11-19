package com.guandan.service;

import java.util.List;

/** 处理中命令的不可变登记信息；可变状态只存在于受锁保护的 GameRecord。 */
record CommandJob(String requestId, List<Object> fingerprint, long startVersion,
                  long generation, long deadlineMillis) {
    CommandJob {
        fingerprint = List.copyOf(fingerprint);
    }
}
