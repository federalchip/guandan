package com.guandan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.List;

/**
 * 动作请求（带 kind 的判别联合）：PLAY 至少一张、最多十张、interpretation_key 可省略；
 * PASS 不得携带 card_ids 或 interpretation_key。额外字段由 Jackson fail-on-unknown 拒绝。
 * card_ids 为严格整数。判别联合校验在 {@link #validate()}（DTO 边界），不单独依赖 Bean Validation。
 *
 * <p>record 绑定无法区分“字段缺失”与“字段值为 JSON null”。字段存在性只是解析期元数据，不属于对外
 * JSON 形态，故不作为 record 组件；改由自定义反序列化器在解析期处理：值走影子 record 的默认强类型绑定
 * （保留未知字段拒绝、禁标量强制、禁 float-as-int），PASS 显式携带 card_ids/interpretation_key（值为
 * null）当场拒绝。序列化用 {@code @JsonInclude(NON_NULL)} 省略 null 字段，使 PASS 输出恰为 {kind:"PASS"}、
 * PLAY 省略键时不含 interpretation_key，保证与反序列化往返一致。前置鉴权拦截器已保证无令牌时先 401，
 * 故此处解析期拒绝不破坏“缺令牌先 401”的优先级。
 */
@JsonDeserialize(using = ActionRequest.Deserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionRequest(String kind, List<Integer> cardIds, String interpretationKey) {

    public static final int MIN_CARDS = 1;
    public static final int MAX_CARDS = 10;

    /** DTO 边界判别联合校验；不合法抛 {@link com.guandan.domain.model.GameRuleException}(SCHEMA_INVALID)。 */
    public void validate() {
        if (kind == null) {
            throw SchemaInvalid.of("action 缺少 kind");
        }
        switch (kind) {
            case "PASS" -> {
                if (cardIds != null || interpretationKey != null) {
                    throw SchemaInvalid.of("PASS 不得携带 card_ids 或 interpretation_key");
                }
            }
            case "PLAY" -> {
                if (cardIds == null || cardIds.size() < MIN_CARDS || cardIds.size() > MAX_CARDS) {
                    throw SchemaInvalid.of("PLAY 需携带 " + MIN_CARDS + "-" + MAX_CARDS + " 张 card_ids");
                }
                for (Integer id : cardIds) {
                    if (id == null) {
                        throw SchemaInvalid.of("card_ids 含 null");
                    }
                }
            }
            default -> throw SchemaInvalid.of("未知 action.kind: " + kind);
        }
    }

    /** 自定义反序列化：默认强类型绑定取值 + 解析期区分 PASS 的字段缺失与显式 null。 */
    public static final class Deserializer extends JsonDeserializer<ActionRequest> {

        /** 仅用于默认绑定的影子类型；无自定义反序列化器，故完整继承全局严格配置。 */
        private record Raw(String kind, List<Integer> cardIds, String interpretationKey) {
        }

        @Override
        public ActionRequest deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            if (node == null || !node.isObject()) {
                return ctxt.reportInputMismatch(ActionRequest.class, "action 必须是 JSON 对象");
            }
            Raw raw = ctxt.readTreeAsValue(node, Raw.class);
            // 显式 null 绑定后与“缺失”不可区分，故在此按原始 JSON 判定：PASS 显式携带即拒（有值情形交 validate 给具体信息）
            if ("PASS".equals(raw.kind())
                    && (isExplicitNull(node, "card_ids") || isExplicitNull(node, "interpretation_key"))) {
                return ctxt.reportInputMismatch(ActionRequest.class, "PASS 不得携带 card_ids 或 interpretation_key");
            }
            return new ActionRequest(raw.kind(), raw.cardIds(), raw.interpretationKey());
        }

        private static boolean isExplicitNull(JsonNode node, String field) {
            return node.has(field) && node.get(field).isNull();
        }
    }
}
