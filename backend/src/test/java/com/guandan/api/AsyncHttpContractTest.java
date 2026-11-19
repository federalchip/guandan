package com.guandan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.api.dto.ActionRequest;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.PlayerView;
import com.guandan.service.BotPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段 A 异步 HTTP 合同：真实 MockMvc + 手动执行器/可控策略，确定性覆盖 202/查询/ busy/失败终态。 */
@SpringBootTest(properties = "guandan.commands.async-enabled=true")
@AutoConfigureMockMvc
@Import(AsyncHttpContractTest.ManualConfig.class)
class AsyncHttpContractTest {

    private static final String BASE = "/api/v1/games";

    @TestConfiguration
    static class ManualConfig {
        // 与生产注入点 @Qualifier("commandExecutor") 同名匹配，@Primary 确保手动执行器胜出
        @Bean @Primary @Qualifier("commandExecutor")
        Executor manualExecutor() { return new ManualExecutor(); }

        @Bean @Primary
        BotPolicy controllablePolicy() { return new ControllablePolicy(); }
    }

    static final class ManualExecutor implements Executor {
        final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void drain() { Runnable r; while ((r = queue.poll()) != null) r.run(); }
    }

    static final class ControllablePolicy implements BotPolicy {
        final AtomicBoolean fail = new AtomicBoolean(false);
        @Override public Action chooseAction(PlayerView view, List<Action> legal, SplittableRandom rng) {
            if (fail.get()) throw new IllegalStateException("bot boom");
            return RandomLegalPolicy.chooseAction(view, legal, rng);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired Executor manualExecutor;
    @Autowired BotPolicy controllablePolicy;

    @BeforeEach
    void resetPolicy() { ((ControllablePolicy) controllablePolicy).fail.set(false); }

    private record GameRef(String id, String token, long version) {}

    private GameRef createGame() throws Exception {
        String body = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode n = om.readTree(body);
        return new GameRef(n.get("game_id").asText(), n.get("session_token").asText(),
                n.get("state").get("version").asLong());
    }

    private static String auth(String token) { return "Bearer " + token; }

    @SuppressWarnings("unchecked")
    private String firstSingleBody(GameRef g) throws Exception {
        String la = mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode m : om.readTree(la).get("moves")) {
            if ("SINGLE".equals(m.get("kind").asText())) {
                return om.writeValueAsString(new ActionRequest("PLAY",
                        om.convertValue(m.get("card_ids"), List.class),
                        m.hasNonNull("interpretation_key") ? m.get("interpretation_key").asText() : null));
            }
        }
        throw new IllegalStateException("no single");
    }

    private String commandJson(String requestId, long version, String actionJson) {
        return "{\"request_id\":\"" + requestId + "\",\"expected_version\":" + version
                + ",\"action\":" + actionJson + "}";
    }

    private String postAction(GameRef g, String requestId, String actionJson) throws Exception {
        return mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(requestId, g.version(), actionJson)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.poll_after_ms").value(500))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void test_post_actions_202_and_snapshot_stays_old() throws Exception {
        GameRef g = createGame();
        String rid = UUID.randomUUID().toString();
        postAction(g, rid, firstSingleBody(g));
        mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.processing_request_id").value(rid))
                .andExpect(jsonPath("$.own_hand.length()").value(27));
    }

    @Test
    void test_command_status_pending_then_completed() throws Exception {
        GameRef g = createGame();
        String rid = UUID.randomUUID().toString();
        String action = firstSingleBody(g);   // 登记前取一次；完成后手牌已变，重取会改变指纹
        postAction(g, rid, action);
        mvc.perform(get(BASE + "/" + g.id() + "/commands/" + rid).header("Authorization", auth(g.token())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.request_id").value(rid));

        ((ManualExecutor) manualExecutor).drain();

        String done = mvc.perform(get(BASE + "/" + g.id() + "/commands/" + rid)
                        .header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.request_id").value(rid))
                .andExpect(jsonPath("$.result.accepted_version").value(1))
                .andReturn().getResponse().getContentAsString();
        long finalVersion = om.readTree(done).get("result").get("state").get("version").asLong();
        mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(finalVersion))
                .andExpect(jsonPath("$.processing_request_id").doesNotExist());
        // 完成后同 ID 重试回到原同步 200 CommandResponse 形态（无 status 包裹）
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(rid, g.version(), action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(rid))
                .andExpect(jsonPath("$.accepted_version").value(1))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void test_same_id_different_body_pending_409() throws Exception {
        GameRef g = createGame();
        String rid = UUID.randomUUID().toString();
        String action = firstSingleBody(g);
        postAction(g, rid, action);
        // 异体：改 expected_version 即不同指纹
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(rid, g.version() + 1, action)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_REUSED"));
    }

    @Test
    void test_different_id_pending_409_game_busy() throws Exception {
        GameRef g = createGame();
        String rid = UUID.randomUUID().toString();
        String action = firstSingleBody(g);   // 处理中 legal-actions 为 GAME_BUSY，先取动作
        postAction(g, rid, action);
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(UUID.randomUUID().toString(), g.version(), action)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_BUSY"))
                .andExpect(jsonPath("$.details.request_id").value(rid));
    }

    @Test
    void test_resolve_and_legal_actions_busy_while_pending() throws Exception {
        GameRef g = createGame();
        postAction(g, UUID.randomUUID().toString(), firstSingleBody(g));
        mvc.perform(get(BASE + "/" + g.id() + "/legal-actions").header("Authorization", auth(g.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_BUSY"));
        mvc.perform(post(BASE + "/" + g.id() + "/resolve").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":" + g.version() + ",\"card_ids\":[4]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GAME_BUSY"));
    }

    @Test
    void test_command_status_not_found_and_forbidden() throws Exception {
        GameRef g = createGame();
        GameRef other = createGame();
        String rid = UUID.randomUUID().toString();
        postAction(g, rid, firstSingleBody(g));
        mvc.perform(get(BASE + "/" + g.id() + "/commands/" + UUID.randomUUID())
                        .header("Authorization", auth(g.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMAND_NOT_FOUND"));
        mvc.perform(get(BASE + "/" + g.id() + "/commands/" + rid)
                        .header("Authorization", auth(other.token())))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/missing-game/commands/" + rid).header("Authorization", auth(g.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    void test_failed_command_terminal_and_idempotent() throws Exception {
        ((ControllablePolicy) controllablePolicy).fail.set(true);
        GameRef g = createGame();
        String rid = UUID.randomUUID().toString();
        postAction(g, rid, firstSingleBody(g));
        ((ManualExecutor) manualExecutor).drain();

        mvc.perform(get(BASE + "/" + g.id() + "/commands/" + rid).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        mvc.perform(get(BASE + "/" + g.id()).header("Authorization", auth(g.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.processing_request_id").doesNotExist());
        // 同 ID 失败重试：200 FAILED，不重新执行
        mvc.perform(post(BASE + "/" + g.id() + "/actions").header("Authorization", auth(g.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(rid, g.version(), firstSingleBody(g))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertEquals(0, ((ManualExecutor) manualExecutor).queue.size());
    }
}
