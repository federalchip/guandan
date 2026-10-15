package com.guandan.api.dto;

import java.util.List;

/** 解析请求：expected_version(必填非负整数) 与 card_ids(1-10 张严格整数)；只解析不推进状态。 */
public record ResolveRequest(Long expectedVersion, List<Integer> cardIds) {

    public static final int MIN_CARDS = 1;
    public static final int MAX_CARDS = 10;

    public void validate() {
        if (expectedVersion == null) {
            throw SchemaInvalid.of("expected_version 必填");
        }
        if (expectedVersion < 0) {
            throw SchemaInvalid.of("expected_version 必须为非负整数");
        }
        if (cardIds == null || cardIds.size() < MIN_CARDS || cardIds.size() > MAX_CARDS) {
            throw SchemaInvalid.of("card_ids 需 " + MIN_CARDS + "-" + MAX_CARDS + " 张");
        }
        for (Integer id : cardIds) {
            if (id == null) {
                throw SchemaInvalid.of("card_ids 含 null");
            }
        }
    }
}
