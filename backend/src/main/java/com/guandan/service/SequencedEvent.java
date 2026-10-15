package com.guandan.service;

import com.guandan.domain.model.DomainEvent;

/** 服务层为公开事件分配的递增 seq 与发生时的 state_version，连同领域事件。 */
record SequencedEvent(long seq, long stateVersion, DomainEvent event) {
}
