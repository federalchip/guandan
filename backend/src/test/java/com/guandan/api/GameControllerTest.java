package com.guandan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.api.dto.ActionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T7 HTTP 合同测试：创建/解析/出牌/刷新流程、鉴权与令牌边界、schema 严格校验、隐藏信息递归检查、
 * 幂等与版本冲突。JSON 字段递归核对，不只断言“页面没显示”。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GameControllerTest {

    private static final String BASE = "/api/v1/games";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private record GameRef(String id, String token, long version) {
    }

    private GameRef createGame() throws Exception {
        String body = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.game_id").isString())
                .andExpect(jsonPath("$.session_token").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode n = om.readTree(body);
        String id = n.get("game_id").asText();
        assertEquals(id, n.get("state").get("game_id").asText(), "两处 game_id 一致");
        return new GameRef(id, n.get("session_token").asText(), n.get("state").get("version").asLong());
    }

    private static String auth(String token) {
        return "Bearer " + token;
    }

    @Test
    void test_create_then_get_snapshot() throws Exception {
        GameRef g = createGame();
        mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.game_id").value(g.id()))
                .andExpect(jsonPath("$.own_hand.length()").value(27))
                .andExpect(jsonPath("$.remaining_counts.length()").value(4))
                .andExpect(jsonPath("$.current_player").value(0))
                .andExpect(jsonPath("$.is_lead").value(true))
                .andExpect(jsonPath("$.can_pass").value(false))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void test_full_flow_resolve_play_refresh() throws Exception {
        GameRef g = createGame();
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_pass").value(false))
                .andExpect(jsonPath("$.moves").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode single = firstSingle(om.readTree(la).get("moves"));
        List<Integer> cards = new ArrayList<>();
        single.get("card_ids").forEach(c -> cards.add(c.asInt()));
        String key = single.get("interpretation_key").asText();

        mvc.perform(post(BASE + "/" + g.id() + "/resolve").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("expected_version", g.version(), "card_ids", cards))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices").isNotEmpty());

        String reqId = UUID.randomUUID().toString();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "PLAY");
        action.put("card_ids", cards);
        action.put("interpretation_key", key);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("request_id", reqId);
        cmd.put("expected_version", g.version());
        cmd.put("action", action);

        String cr = mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(reqId))
                .andExpect(jsonPath("$.accepted_version").value(1))
                .andExpect(jsonPath("$.events").isNotEmpty())
                .andExpect(jsonPath("$.events[0].seq").isNumber())
                .andExpect(jsonPath("$.events[0].state_version").isNumber())
                .andExpect(jsonPath("$.events[0].payload.played_cards[0].card_id").value(cards.get(0)))
                .andExpect(jsonPath("$.events[0].payload.played_cards[0].rank").isNumber())
                .andExpect(jsonPath("$.events[0].payload.played_cards[0].suit").isString())
                .andReturn().getResponse().getContentAsString();

        long finalVersion = om.readTree(cr).get("state").get("version").asLong();
        assertTrue(finalVersion >= 1);
        mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) finalVersion))
                .andExpect(jsonPath("$.own_hand.length()").value(26));
    }

    @Test
    void test_hidden_info_recursively_absent() throws Exception {
        GameRef g = createGame();
        String body = mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = om.readTree(body);

        Set<Integer> own = new HashSet<>();
        for (JsonNode c : json.get("own_hand")) {
            own.add(c.get("card_id").asInt());
        }
        assertEquals(27, own.size());

        Set<Integer> allCardIds = new HashSet<>();
        List<String> seedPaths = new ArrayList<>();
        collect(json, "$", allCardIds, seedPaths);
        assertTrue(own.containsAll(allCardIds),
                "响应中所有 card_id 只属于本人；泄露=" + minus(allCardIds, own));
        assertTrue(seedPaths.isEmpty(), "响应不得含 seed 字段: " + seedPaths);
        assertEquals(4, json.get("remaining_counts").size(), "对手仅以张数呈现");
    }

    @Test
    void test_missing_token_is_401() throws Exception {
        GameRef g = createGame();
        mvc.perform(get(BASE + "/" + g.id()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_cross_game_token_is_403() throws Exception {
        GameRef g1 = createGame();
        GameRef g2 = createGame();
        mvc.perform(get(BASE + "/" + g1.id()).header("Authorization", auth(g2.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
    }

    @Test
    void test_unknown_game_is_404() throws Exception {
        mvc.perform(get(BASE + "/no-such-game").header("Authorization", auth("whatever-token")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void test_pass_on_lead_is_400() throws Exception {
        GameRef g = createGame();
        String body = "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":0,"
                + "\"action\":{\"kind\":\"PASS\"}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASS_NOT_ALLOWED"));
    }

    @Test
    void test_version_conflict_is_409() throws Exception {
        GameRef g = createGame();
        String body = "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":99,"
                + "\"action\":{\"kind\":\"PASS\"}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void test_schema_errors_are_422() throws Exception {
        GameRef g = createGame();
        String u = UUID.randomUUID().toString();
        // PASS 携带 card_ids（判别联合违规）
        assert422(g, "{\"request_id\":\"" + u + "\",\"expected_version\":0,\"action\":{\"kind\":\"PASS\",\"card_ids\":[4]}}");
        // 额外字段
        assert422(g, "{\"request_id\":\"" + u + "\",\"expected_version\":0,\"action\":{\"kind\":\"PASS\"},\"extra\":1}");
        // card_id 为字符串（禁止标量强制）
        assert422(g, "{\"request_id\":\"" + u + "\",\"expected_version\":0,\"action\":{\"kind\":\"PLAY\",\"card_ids\":[\"4\"]}}");
        // expected_version 为浮点
        assert422(g, "{\"request_id\":\"" + u + "\",\"expected_version\":1.5,\"action\":{\"kind\":\"PASS\"}}");
        // request_id 非 UUID
        assert422(g, "{\"request_id\":\"abc\",\"expected_version\":0,\"action\":{\"kind\":\"PASS\"}}");
        // PLAY 超过 10 张
        assert422(g, "{\"request_id\":\"" + u + "\",\"expected_version\":0,\"action\":{\"kind\":\"PLAY\",\"card_ids\":[1,2,3,4,5,6,7,8,9,10,11]}}");
    }

    @Test
    void test_pass_with_explicit_null_fields_is_422() throws Exception {
        GameRef g = createGame();
        // 显式 card_ids:null（领出旧行为 400 PASS_NOT_ALLOWED，跟牌旧行为 200 推进）→ 合同要求 422
        assert422(g, "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":0,"
                + "\"action\":{\"kind\":\"PASS\",\"card_ids\":null}}");
        // 显式 interpretation_key:null 同理
        assert422(g, "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":0,"
                + "\"action\":{\"kind\":\"PASS\",\"interpretation_key\":null}}");
        // 控制组：缺失字段（规范 PASS 形）不被 nulls=FAIL 拦截，仍走到引擎 → 领出 400
        String absent = "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":0,"
                + "\"action\":{\"kind\":\"PASS\"}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(absent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASS_NOT_ALLOWED"));
    }

    @Test
    void test_explicit_null_rejected_but_absent_allowed() throws Exception {
        // 显式 null：解析期即拒（区别于字段缺失）
        assertThrows(Exception.class,
                () -> om.readValue("{\"kind\":\"PASS\",\"card_ids\":null}", ActionRequest.class));
        assertThrows(Exception.class,
                () -> om.readValue("{\"kind\":\"PASS\",\"interpretation_key\":null}", ActionRequest.class));
        // 缺失：规范 PASS 形，绑定成功且 validate() 通过
        ActionRequest absent = om.readValue("{\"kind\":\"PASS\"}", ActionRequest.class);
        assertNull(absent.cardIds());
        assertNull(absent.interpretationKey());
        absent.validate();
    }

    @Test
    void test_action_request_serialization_round_trip() throws Exception {
        // PASS 序列化为合同形：只有 kind，无 null 的 card_ids/interpretation_key，更无 presence 元数据
        ActionRequest pass = new ActionRequest("PASS", null, null);
        String passJson = om.writeValueAsString(pass);
        JsonNode pn = om.readTree(passJson);
        assertEquals("PASS", pn.get("kind").asText());
        assertFalse(pn.has("card_ids"), "PASS 不得序列化 card_ids: " + passJson);
        assertFalse(pn.has("interpretation_key"), "PASS 不得序列化 interpretation_key: " + passJson);
        assertFalse(pn.has("card_ids_present"), "presence 元数据不得进入 JSON: " + passJson);
        assertFalse(pn.has("interpretation_key_present"), "presence 元数据不得进入 JSON: " + passJson);
        assertEquals(pass, om.readValue(passJson, ActionRequest.class), "PASS 往返等价");

        // PLAY 省略 interpretation_key：序列化不含该字段，往返等价
        ActionRequest play = new ActionRequest("PLAY", List.of(4), null);
        String playJson = om.writeValueAsString(play);
        assertFalse(om.readTree(playJson).has("interpretation_key"), "省略键不序列化: " + playJson);
        assertEquals(play, om.readValue(playJson, ActionRequest.class), "PLAY 往返等价");

        // PLAY 带键往返等价
        ActionRequest playKey = new ActionRequest("PLAY", List.of(4), "gd-v1-level2|SINGLE|1|3|4");
        assertEquals(playKey, om.readValue(om.writeValueAsString(playKey), ActionRequest.class));
    }

    @Test
    void test_idempotent_repeat_returns_same_receipt() throws Exception {
        GameRef g = createGame();
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode single = firstSingle(om.readTree(la).get("moves"));
        List<Integer> cards = new ArrayList<>();
        single.get("card_ids").forEach(c -> cards.add(c.asInt()));
        String reqId = UUID.randomUUID().toString();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "PLAY");
        action.put("card_ids", cards);
        action.put("interpretation_key", single.get("interpretation_key").asText());
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("request_id", reqId);
        cmd.put("expected_version", g.version());
        cmd.put("action", action);
        String json = om.writeValueAsString(cmd);

        String r1 = mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String r2 = mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(om.readTree(r1).get("accepted_version").asLong(),
                om.readTree(r2).get("accepted_version").asLong(), "重复请求返回同一收据");
        assertEquals(om.readTree(r1).get("state").get("version").asLong(),
                om.readTree(r2).get("state").get("version").asLong(), "重复请求不再推进");
    }

    @Test
    void test_same_request_id_omitted_key_vs_string_null_is_409() throws Exception {
        GameRef g = createGame();
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode single = firstSingle(om.readTree(la).get("moves"));
        List<Integer> cards = new ArrayList<>();
        single.get("card_ids").forEach(c -> cards.add(c.asInt()));
        String reqId = UUID.randomUUID().toString();

        Map<String, Object> omitKey = new LinkedHashMap<>();
        omitKey.put("kind", "PLAY");
        omitKey.put("card_ids", cards);
        Map<String, Object> cmd1 = new LinkedHashMap<>();
        cmd1.put("request_id", reqId);
        cmd1.put("expected_version", g.version());
        cmd1.put("action", omitKey);
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(cmd1)))
                .andExpect(status().isOk());

        Map<String, Object> stringNullKey = new LinkedHashMap<>();
        stringNullKey.put("kind", "PLAY");
        stringNullKey.put("card_ids", cards);
        stringNullKey.put("interpretation_key", "null");
        Map<String, Object> cmd2 = new LinkedHashMap<>();
        cmd2.put("request_id", reqId);
        cmd2.put("expected_version", g.version());
        cmd2.put("action", stringNullKey);
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(cmd2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_REUSED"));
    }

    @Test
    void test_action_missing_expected_version_is_422() throws Exception {
        GameRef g = createGame();
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode single = firstSingle(om.readTree(la).get("moves"));
        List<Integer> cards = new ArrayList<>();
        single.get("card_ids").forEach(c -> cards.add(c.asInt()));

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("kind", "PLAY");
        action.put("card_ids", cards);
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("request_id", UUID.randomUUID().toString());
        cmd.put("action", action);   // 省略 expected_version
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(cmd)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));

        String explicitNull = "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":null,"
                + "\"action\":{\"kind\":\"PLAY\",\"card_ids\":[" + cards.get(0) + "]}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(explicitNull))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }

    @Test
    void test_resolve_missing_expected_version_is_422() throws Exception {
        GameRef g = createGame();
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode single = firstSingle(om.readTree(la).get("moves"));
        List<Integer> cards = new ArrayList<>();
        single.get("card_ids").forEach(c -> cards.add(c.asInt()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("card_ids", cards);   // 省略 expected_version
        mvc.perform(post(BASE + "/" + g.id() + "/resolve").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }

    @Test
    void test_action_missing_token_precedes_schema_error_is_401() throws Exception {
        GameRef g = createGame();
        // 无令牌 + 非法 request_id：鉴权(401)必须先于请求体校验(422)
        String body = "{\"request_id\":\"not-a-uuid\",\"expected_version\":0,\"action\":{\"kind\":\"PASS\"}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_resolve_missing_token_precedes_schema_error_is_401() throws Exception {
        GameRef g = createGame();
        // 无令牌 + 空 card_ids：鉴权(401)必须先于请求体校验(422)
        String body = "{\"expected_version\":0,\"card_ids\":[]}";
        mvc.perform(post(BASE + "/" + g.id() + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_malformed_json_no_token_is_401() throws Exception {
        GameRef g = createGame();
        // 无令牌 + JSON 语法错误：@RequestBody 解析前应先鉴权 → 401（旧行为 422）
        mvc.perform(post(BASE + "/" + g.id() + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_wrong_type_body_no_token_is_401() throws Exception {
        GameRef g = createGame();
        // 无令牌 + 字段类型非法（expected_version 为字符串，禁标量强制）：解析期错误也应先 401
        String body = "{\"request_id\":\"" + UUID.randomUUID() + "\",\"expected_version\":\"0\","
                + "\"action\":{\"kind\":\"PASS\"}}";
        mvc.perform(post(BASE + "/" + g.id() + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_resolve_malformed_json_no_token_is_401() throws Exception {
        GameRef g = createGame();
        mvc.perform(post(BASE + "/" + g.id() + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void test_malformed_json_with_valid_token_is_422() throws Exception {
        GameRef g = createGame();
        // 控制组：令牌有效时拦截器放行，解析错误仍 422（不被 401 掩盖）
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }

    // ---- 辅助 ----

    private void assert422(GameRef g, String body) throws Exception {
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"));
    }

    private static JsonNode firstSingle(JsonNode moves) {
        for (JsonNode m : moves) {
            if ("SINGLE".equals(m.get("kind").asText())) {
                return m;
            }
        }
        throw new AssertionError("应有单张候选");
    }

    private void collect(JsonNode node, String path, Set<Integer> cardIds, List<String> seedPaths) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (key.equals("card_id") && e.getValue().isInt()) {
                    cardIds.add(e.getValue().asInt());
                }
                if (key.toLowerCase().contains("seed")) {
                    seedPaths.add(path + "." + key);
                }
                collect(e.getValue(), path + "." + key, cardIds, seedPaths);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), path + "[" + i + "]", cardIds, seedPaths);
            }
        }
    }

    private static Set<Integer> minus(Set<Integer> a, Set<Integer> b) {
        Set<Integer> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
