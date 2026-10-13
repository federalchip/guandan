package com.guandan.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.CardIds;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试专用：加载并索引人工规则 fixture（fixtures/rule_cases.json）。
 */
final class RuleFixtures {

    static final JsonNode ROOT;
    static final Map<String, JsonNode> BY_ID = new HashMap<>();

    static {
        try (InputStream in = RuleFixtures.class.getResourceAsStream("/fixtures/rule_cases.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /fixtures/rule_cases.json");
            }
            ROOT = new ObjectMapper().readTree(in);
            for (JsonNode c : ROOT.get("cases")) {
                BY_ID.put(c.get("case_id").asText(), c);
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RuleFixtures() {
    }

    static List<JsonNode> ofType(String type) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : ROOT.get("cases")) {
            if (type.equals(c.get("type").asText())) {
                out.add(c);
            }
        }
        return out;
    }

    static List<String> idsOfType(String type) {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : ofType(type)) {
            ids.add(c.get("case_id").asText());
        }
        return ids;
    }

    /** 读取一个选牌节点：优先 selected（符号），否则 selected_ids（原始整数）。 */
    static List<Integer> selectedIds(JsonNode node) {
        List<Integer> ids = new ArrayList<>();
        if (node.has("selected")) {
            for (JsonNode s : node.get("selected")) {
                ids.add(CardIds.parseOne(s.asText()));
            }
        }
        if (node.has("selected_ids")) {
            for (JsonNode s : node.get("selected_ids")) {
                ids.add(s.asInt());
            }
        }
        return ids;
    }
}
