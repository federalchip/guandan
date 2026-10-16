package com.guandan.api.dto;

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
 * <p>record 绑定无法区分“字段缺失”与“字段值为 JSON null”，故用自定义反序列化器：值仍走默认强类型绑定
 * （保留未知字段拒绝、禁标量强制、禁 float-as-int），字段是否存在单独由 {@code node.has(...)} 捕获。
 * 于是 {kind:"PASS"}（缺失，合法）与 {kind:"PASS",card_ids:null}（显式携带，违规）可区分，
 * validate() 依 presence 拒绝后者。
 */
@JsonDeserialize(using = ActionRequest.Deserializer.class)
public record ActionRequest(
        String kind,
        List<Integer> cardIds,
        String interpretationKey,
        boolean cardIdsPresent,
        boolean interpretationKeyPresent) {

    public static final int MIN_CARDS = 1;
    public static final int MAX_CARDS = 10;

    /** 程序化构造（测试/内部）：无 JSON 来源，presence 视为“值非 null 即存在”。 */
    public ActionRequest(String kind, List<Integer> cardIds, String interpretationKey) {
        this(kind, cardIds, interpretationKey, cardIds != null, interpretationKey != null);
    }

    /** DTO 边界判别联合校验；不合法抛 {@link com.guandan.domain.model.GameRuleException}(SCHEMA_INVALID)。 */
    public void validate() {
        if (kind == null) {
            throw SchemaInvalid.of("action 缺少 kind");
        }
        switch (kind) {
            case "PASS" -> {
                if (cardIdsPresent || interpretationKeyPresent) {
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

    /** 自定义反序列化：默认强类型绑定取值 + node.has 捕获字段存在性，区分缺失与显式 null。 */
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
            return new ActionRequest(raw.kind(), raw.cardIds(), raw.interpretationKey(),
                    node.has("card_ids"), node.has("interpretation_key"));
        }
    }
}
