# LLM 机器人决策 · 阶段 A（API 合同与异步本地策略）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不接模型的前提下，让本地随机机器人策略经过异步命令编排器运行：`POST /actions` 可返回 `202`，新增命令状态查询与 `processing_request_id`，整批完成后原子提交，同步随机默认路径完全不变。

**Architecture:** 从 `GameService.submit` 抽出 `BotBatchRunner`（私有工作状态上执行"真人动作+机器人批次"，不持锁）。异步模式在每局锁内预校验真人动作并登记不可变 `CommandJob`，释放锁后交给有界执行器；工作任务完成后重新取锁，核对任务所有权与起始版本，一次提交状态/事件/幂等收据；规则失败写终态 `FAILED` 缓存。命令结果用密封接口 `CommandOutcome`（Completed/Pending/Failed）统一表达，控制器映射 200/202。

**Tech Stack:** JDK 17、Spring Boot 3.5.16、Maven Wrapper（`./mvnw.cmd`）、JUnit 5、MockMvc、springdoc 2.8.17；前端仅重新生成 schema（Vue 3 + openapi-typescript）。

**Spec:** `docs/llm-bot-decision-design.md` + `docs/qoder-llm-bot-handoff.md`（阶段 A 节）。本计划只覆盖阶段 A；B（并发/截止/看门狗）、C（前端异步）、D（候选/网关）、E（评测）在 A 过审后另立计划。

## Global Constraints

- JDK 17：**禁止 switch 模式匹配**（Java 21 才转正），密封接口分支用 `instanceof` 模式变量（Java 16 转正）。
- `GameEngine` 是唯一规则裁决者；本阶段不引入任何模型 SDK、网络调用、真实凭据。
- 默认同步随机模式（`guandan.commands.async-enabled=false`）的 `200 CommandResponse` 合同逐字段不变；现有 260 个后端测试不改断言、必须全绿。
- 异步开关在**每局创建时固定**（`GameRecord.asyncMode`），运行中改配置不影响已有牌局。
- 模型/执行器工作**不得持有每局锁**；锁内只做登记、查询与原子提交。
- 传输 DTO 全部 snake_case（全局 Jackson SNAKE_CASE）；`processing_request_id` 无处理中命令时**缺省**（`@JsonInclude(NON_NULL)`）。
- 隐藏信息边界不变：任何响应不得含他人手牌、种子、令牌。
- 机器人步数上限 512（`BOT_STEP_LIMIT`）；真人固定座位 0。
- 幂等顺序不变：先查终态缓存/处理中任务（指纹逐元素 equals），再查版本；同 ID 异体 `REQUEST_ID_REUSED`。
- 新增错误码：`GAME_BUSY`→409、`COMMAND_NOT_FOUND`→404、`COMMAND_QUEUE_FULL`→503（设计文档 HTTP 表需同步补充，见 Task 4）。
- 执行器有界（core=max=2、队列 64、AbortPolicy）；登记成功但提交执行器失败时，必须在返回前清除任务并抛可重试错误，不留永久 PENDING。
- 每阶段 TDD：先写因缺失能力而失败的测试，运行确认红，再实现转绿；每任务单独提交，不 amend。
- 工作目录：`E:\professional_class\guandan\.worktrees\llm-bot-decision`（分支 `codex/llm-bot-decision`）。后端命令在 `backend/` 下执行，前端命令在 `frontend/` 下执行。

---

### Task 1: 抽出 BotBatchRunner（纯重构，同步行为不变）

**Files:**
- Create: `backend/src/main/java/com/guandan/service/BotBatchRunner.java`
- Create: `backend/src/test/java/com/guandan/service/BotBatchRunnerTest.java`
- Modify: `backend/src/main/java/com/guandan/service/GameService.java`（submit 的批次段改为委托 runner；删除 `MAX_BOT_STEPS` 与 `appendEvents`）

**Interfaces:**
- Produces: `BotBatchRunner`（包私有 final 类）构造器 `BotBatchRunner(BotPolicy policy, ActionAdvancer advancer)`；方法 `BatchResult run(GameState initial, Action humanAction, long gameSeed, long startSeq)`；嵌套 `record BatchResult(GameState state, long acceptedVersion, List<SequencedEvent> events, long nextSeq)`（events 不可变）。`run` 在私有状态上应用真人动作（座位 0）+ 机器人循环，失败抛 `GameRuleException`（含 `BOT_STEP_LIMIT`），**不触碰 GameRecord、不持锁**。Task 2/3 的同步与异步路径都调用它。

- [ ] **Step 1: 写失败测试 BotBatchRunnerTest**

```java
package com.guandan.service;

import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.engine.GameEngine;
import com.guandan.rules.MoveClassifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次执行器纯单元测试：私有状态推进、事件序号连续、512 步保护。 */
class BotBatchRunnerTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final long SEED = 42L;

    @Test
    void test_run_applies_human_then_bots_with_contiguous_seq() {
        var single5 = MoveClassifier.classify(CardIds.parse("S5@0"), PROFILE).get(0);
        GameState follow = stateWithHands(
                List.of(CardIds.parse("S3@0 H4@0"), CardIds.parse("H6@0"),
                        CardIds.parse("D7@0"), CardIds.parse("C8@0")),
                0, new Trick(1, single5, Set.of(0, 2, 3)), List.of(), Phase.PLAYING, null, 5L);
        BotBatchRunner runner = new BotBatchRunner(RandomLegalPolicy::chooseAction, GameEngine::applyAction);

        BotBatchRunner.BatchResult r = runner.run(follow, Action.pass(), SEED, 7L);

        assertEquals(6L, r.acceptedVersion(), "accepted_version 为真人单动作后的版本");
        assertFalse(r.events().isEmpty());
        assertEquals(7L, r.events().get(0).seq(), "事件序号从 startSeq 开始");
        for (int i = 1; i < r.events().size(); i++) {
            assertEquals(r.events().get(i - 1).seq() + 1, r.events().get(i).seq(), "seq 连续");
        }
        assertEquals(7L + r.events().size(), r.nextSeq());
        assertTrue(r.state().version() >= 6L);
        assertEquals(5L, follow.version(), "入参状态不被修改（私有工作副本）");
    }

    @Test
    void test_step_limit_throws_without_commit() {
        GameState stuck = stateWithHands(
                List.of(List.of(4), List.of(8), List.of(12), List.of(16)),
                1, null, List.of(), Phase.PLAYING, null, 0L);
        ActionAdvancer noAdvance = (st, player, action) -> new EngineOutcome(stuck, List.of());
        BotBatchRunner runner = new BotBatchRunner(RandomLegalPolicy::chooseAction, noAdvance);
        GameState initial = GameEngine.newGame(SEED, PROFILE);
        List<com.guandan.domain.model.Move> choices =
                GameEngine.resolveSelection(initial, 0, List.of(initial.hands().get(0).get(0)));
        Action human = Action.play(choices.get(0).cardIds(), choices.get(0).interpretationKey());

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> runner.run(initial, human, SEED, 1L));
        assertEquals("BOT_STEP_LIMIT", ex.code());
    }

    private static GameState stateWithHands(List<List<Integer>> hands, Integer currentPlayer, Trick trick,
                                            List<Integer> finishOrder, Phase phase,
                                            com.guandan.domain.model.GameResult result, long version) {
        Set<Integer> union = new HashSet<>();
        for (List<Integer> h : hands) union.addAll(h);
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) discarded.add(id);
        }
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, result, version);
    }
}
```

注意：`Move` 的访问器名以 `domain/model/Move.java` 实际 record 组件为准（`cardIds()`/`interpretationKey()`）；若命名不同，按实际调整本测试与后续代码，不得改 Move 本身。

- [ ] **Step 2: 运行确认红**

Run: `./mvnw.cmd -Dtest=BotBatchRunnerTest test`
Expected: 编译失败，`BotBatchRunner` 不存在。

- [ ] **Step 3: 实现 BotBatchRunner**

```java
package com.guandan.service;

import com.guandan.bots.BotSeeds;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** 在私有工作状态上执行"真人动作 + 机器人批次"；不触碰 GameRecord、不持锁，供同步与异步路径共用。 */
final class BotBatchRunner {

    static final int MAX_BOT_STEPS = 512;
    private static final int HUMAN_SEAT = 0;

    record BatchResult(GameState state, long acceptedVersion, List<SequencedEvent> events, long nextSeq) {
        BatchResult {
            events = List.copyOf(events);
        }
    }

    private final BotPolicy policy;
    private final ActionAdvancer advancer;

    BotBatchRunner(BotPolicy policy, ActionAdvancer advancer) {
        this.policy = policy;
        this.advancer = advancer;
    }

    BatchResult run(GameState initial, Action humanAction, long gameSeed, long startSeq) {
        List<SequencedEvent> newEvents = new ArrayList<>();
        long seq = startSeq;
        EngineOutcome humanOut = advancer.applyAction(initial, HUMAN_SEAT, humanAction);
        seq = appendEvents(newEvents, seq, humanOut);
        GameState work = humanOut.state();
        long acceptedVersion = work.version();

        int botSteps = 0;
        while (work.phase() == Phase.PLAYING) {
            Integer cp = work.currentPlayer();
            if (cp == null) {
                break;
            }
            if (cp == HUMAN_SEAT && !work.hands().get(HUMAN_SEAT).isEmpty()) {
                break;
            }
            if (botSteps >= MAX_BOT_STEPS) {
                throw new GameRuleException("BOT_STEP_LIMIT",
                        "机器人步数超过上限 " + MAX_BOT_STEPS, Map.of("limit", MAX_BOT_STEPS));
            }
            int seat = cp;
            List<Action> legal = GameEngine.getLegalActions(work, seat);
            PlayerView view = PlayerViewFactory.getPlayerView(work, seat);
            long botSeed = BotSeeds.derive(gameSeed, work.version(), seat);
            Action botAction = policy.chooseAction(view, legal, new SplittableRandom(botSeed));
            EngineOutcome botOut = advancer.applyAction(work, seat, botAction);
            seq = appendEvents(newEvents, seq, botOut);
            work = botOut.state();
            botSteps++;
        }
        return new BatchResult(work, acceptedVersion, newEvents, seq);
    }

    private static long appendEvents(List<SequencedEvent> sink, long seq, EngineOutcome out) {
        long stateVersion = out.state().version();
        for (DomainEvent e : out.events()) {
            sink.add(new SequencedEvent(seq++, stateVersion, e));
        }
        return seq;
    }
}
```

- [ ] **Step 4: GameService.submit 委托 runner**

把 `GameService.submit` 中从 `List<SequencedEvent> newEvents = new ArrayList<>();` 到 `rec.nextSeq = seq;` 的整段（原 134–168 行）替换为：

```java
            BotBatchRunner.BatchResult r = runner.run(work, toAction(command.action()), rec.gameSeed, rec.nextSeq);
            rec.state = r.state();
            rec.events.addAll(r.events());
            rec.nextSeq = r.nextSeq();
```

其后的 `finalView`/`eventViews`/`response` 构造改用 `r.state()`、`r.events()`、`r.acceptedVersion()`：

```java
            GameView finalView = DtoMapper.toGameView(rec.gameId,
                    PlayerViewFactory.getPlayerView(r.state(), HUMAN_SEAT), currentRoundEvents(rec.events));
            List<EventView> eventViews = new ArrayList<>(r.events().size());
            for (SequencedEvent se : r.events()) {
                eventViews.add(DtoMapper.toEventView(se.seq(), se.stateVersion(), se.event()));
            }
            CommandResponse response = new CommandResponse(command.requestId(), r.acceptedVersion(),
                    finalView, List.copyOf(eventViews));
            rec.idempotency.put(command.requestId(), new CachedCommand(fingerprint, response));
            return response;
```

字段与构造器：新增 `private final BotBatchRunner runner;`，现有构造器末尾 `this.runner = new BotBatchRunner(policy, advancer);`。删除 `MAX_BOT_STEPS` 常量与 `appendEvents` 方法（已移入 runner）；清理不再使用的 import（`BotSeeds`、`DomainEvent`、`EngineOutcome`、`SplittableRandom`、`Action` 若仍被 toAction 使用则保留）。

- [ ] **Step 5: 运行转绿 + 全量回归**

Run: `./mvnw.cmd -Dtest=BotBatchRunnerTest,GameServiceTest test` → 全绿
Run: `./mvnw.cmd test` → **262 tests**（260 基线 + 2 新增），0 失败。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/guandan/service/BotBatchRunner.java \
        backend/src/main/java/com/guandan/service/GameService.java \
        backend/src/test/java/com/guandan/service/BotBatchRunnerTest.java \
        docs/superpowers/plans/2026-11-17-llm-bot-phase-a.md
git commit -m "refactor: extract bot batch runner from game service"
```

---

### Task 2: 异步登记、原子提交与命令状态（服务层）

**Files:**
- Create: `backend/src/main/java/com/guandan/service/CommandJob.java`
- Create: `backend/src/main/java/com/guandan/service/AsyncCommandConfig.java`
- Create: `backend/src/main/java/com/guandan/service/CachedFailure.java`
- Create: `backend/src/main/java/com/guandan/service/CommandOutcome.java`
- Create: `backend/src/test/java/com/guandan/service/AsyncCommandTest.java`
- Modify: `backend/src/main/java/com/guandan/service/GameRecord.java`
- Modify: `backend/src/main/java/com/guandan/service/InMemoryGameStore.java`
- Modify: `backend/src/main/java/com/guandan/service/GameService.java`
- Modify: `backend/src/main/java/com/guandan/service/ServiceConfig.java`
- Modify: `backend/src/main/java/com/guandan/api/dto/GameView.java`（新增可选 processingRequestId）
- Modify: `backend/src/main/java/com/guandan/api/dto/DtoMapper.java`（toGameView 四参重载）
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1 的 `BotBatchRunner.run(GameState, Action, long, long)` 与 `BatchResult`。
- Produces（Task 3 控制器依赖这些精确签名）:
  - `public sealed interface CommandOutcome` permits `Completed(CommandResponse response)`、`Pending(String requestId, long pollAfterMillis)`、`Failed(String code, String message)`（均为嵌套 record）。
  - `GameService.submitCommand(String gameId, String sessionToken, CommandRequest command)` → `CommandOutcome`。
  - `GameService.commandStatus(String gameId, String sessionToken, String requestId)` → `CommandOutcome`；未知命令抛 `GameRuleException("COMMAND_NOT_FOUND", …)`。
  - `GameService` 主构造器 `@Autowired (InMemoryGameStore, GameSeedSource, BotPolicy, ActionAdvancer, Executor commandExecutor, AsyncCommandConfig)`；保留四参便捷构造器（同步默认 + `Runnable::run`）。`submit(...)` 签名与同步行为不变。
  - `AsyncCommandConfig(boolean asyncEnabled, long batchTimeoutMillis, long pollAfterMillis)`，静态 `syncDefaults()` = `(false, 12000, 500)`。
  - `GameView.processingRequestId()`（可为 null，JSON 缺省）。
  - `GameRecord.currentJob`（包内可见）、`GameRecord.failures`。

- [ ] **Step 1: 写失败测试 AsyncCommandTest**

```java
package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.MoveView;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.engine.GameEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 A 异步命令服务层测试：手动执行器确定性控制"处理中→提交/失败"，不依赖真实等待。 */
class AsyncCommandTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final long SEED = 42L;

    private final ManualExecutor executor = new ManualExecutor();

    private GameService asyncService(InMemoryGameStore store) {
        return new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, GameEngine::applyAction,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
    }

    static final class ManualExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void drain() { Runnable r; while ((r = queue.poll()) != null) r.run(); }
    }

    // ---- 登记与处理中 ----

    @Test
    void test_async_submit_registers_pending_and_keeps_old_snapshot() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest cmd = playCommand(g.state().version(), single);

        CommandOutcome outcome = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        CommandOutcome.Pending pending = assertInstanceOf(CommandOutcome.Pending.class, outcome);
        assertEquals(cmd.requestId(), pending.requestId());
        assertEquals(500L, pending.pollAfterMillis());
        assertEquals(1, executor.queue.size(), "恰登记一个工作任务");
        GameRecord rec = store.get(g.gameId());
        assertEquals(0L, rec.state.version(), "处理中对外仍是旧快照");
        assertEquals(27, rec.state.hands().get(0).size(), "真人牌未扣");
        assertEquals(cmd.requestId(), rec.currentJob.requestId());
        GameView view = svc.getGame(g.gameId(), g.sessionToken());
        assertEquals(cmd.requestId(), view.processingRequestId());
        assertEquals(0L, view.version());
        assertInstanceOf(CommandOutcome.Pending.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId()));
    }

    @Test
    void test_async_same_id_same_body_while_pending_registers_once() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));

        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        CommandOutcome again = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        assertInstanceOf(CommandOutcome.Pending.class, again);
        assertEquals(cmd.requestId(), ((CommandOutcome.Pending) again).requestId());
        assertEquals(1, executor.queue.size(), "同 ID 同体处理中不重复登记");
    }

    @Test
    void test_async_same_id_different_body_while_pending_is_reused() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        long v0 = g.state().version();
        List<MoveView> moves = svc.legalActions(g.gameId(), g.sessionToken()).moves();
        MoveView a = moves.get(0);
        MoveView b = moves.stream().filter(m -> !m.cardIds().equals(a.cardIds())).findFirst().orElseThrow();
        String reqId = UUID.randomUUID().toString();
        svc.submitCommand(g.gameId(), g.sessionToken(),
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", a.cardIds(), a.interpretationKey())));

        GameRuleException ex = assertThrows(GameRuleException.class, () -> svc.submitCommand(g.gameId(),
                g.sessionToken(),
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", b.cardIds(), b.interpretationKey()))));
        assertEquals("REQUEST_ID_REUSED", ex.code());
        assertEquals(1, executor.queue.size());
    }

    @Test
    void test_async_different_id_while_pending_is_game_busy() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest first = playCommand(g.state().version(), single);
        svc.submitCommand(g.gameId(), g.sessionToken(), first);
        // 处理中 legal-actions 会 GAME_BUSY，第二命令复用登记前取到的动作（不同 request_id 即可）
        CommandRequest other = playCommand(g.state().version(), single);
        assertNotEquals(first.requestId(), other.requestId());

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), other));
        assertEquals("GAME_BUSY", ex.code());
        assertEquals(first.requestId(), ex.details().get("request_id"), "details 只含处理中 request_id 与等待建议");
        assertEquals(1, executor.queue.size());
    }

    @Test
    void test_async_version_conflict_precedes_registration() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest cmd = playCommand(g.state().version() + 5, single);

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals("VERSION_CONFLICT", ex.code());
        assertNull(store.get(g.gameId()).currentJob, "版本冲突不登记任务");
        assertTrue(executor.queue.isEmpty());
    }

    @Test
    void test_async_illegal_human_action_rejected_synchronously() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        int opponentCard = Deck.deal(SEED).get(1).get(0);
        CommandRequest cmd = new CommandRequest(UUID.randomUUID().toString(), g.state().version(),
                new ActionRequest("PLAY", List.of(opponentCard), null));

        assertThrows(GameRuleException.class, () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertNull(store.get(g.gameId()).currentJob, "非法真人动作同步拒绝，不登记");
        assertTrue(executor.queue.isEmpty());
        assertEquals(0L, store.get(g.gameId()).state.version());
    }

    // ---- 原子提交 ----

    @Test
    void test_async_worker_commits_once_atomically() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        CommandOutcome outcome = svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId());
        CommandOutcome.Completed completed = assertInstanceOf(CommandOutcome.Completed.class, outcome);
        CommandResponse cr = completed.response();
        assertEquals(cmd.requestId(), cr.requestId());
        assertEquals(1L, cr.acceptedVersion(), "accepted_version 为真人动作后版本");
        assertTrue(cr.state().version() > 0L);
        assertEquals(1L, cr.events().get(0).seq());
        for (int i = 1; i < cr.events().size(); i++) {
            assertEquals(cr.events().get(i - 1).seq() + 1, cr.events().get(i).seq());
        }
        GameRecord rec = store.get(g.gameId());
        assertNull(rec.currentJob, "提交后释放任务占位");
        assertEquals(cr.state().version(), rec.state.version());
        assertEquals(1L + cr.events().size(), rec.nextSeq);
        assertEquals(1, rec.idempotency.size());
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
        // 同 ID 重试命中幂等缓存，返回原收据，不再执行
        CommandOutcome retry = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        assertEquals(cr.acceptedVersion(), assertInstanceOf(CommandOutcome.Completed.class, retry).response().acceptedVersion());
        assertTrue(executor.queue.isEmpty());
        // 再次 drain 无任务可跑，状态不变
        long version = rec.state.version();
        executor.drain();
        assertEquals(version, rec.state.version());
    }

    // ---- 终态失败 ----

    @Test
    void test_async_bot_failure_marks_failed_without_commit() {
        InMemoryGameStore store = new InMemoryGameStore();
        BotPolicy throwing = (view, legal, rng) -> { throw new IllegalStateException("bot boom"); };
        GameService svc = new GameService(store, () -> SEED, throwing, GameEngine::applyAction,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        CommandOutcome.Failed failed = assertInstanceOf(CommandOutcome.Failed.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId()));
        assertEquals("INTERNAL_ERROR", failed.code());
        GameRecord rec = store.get(g.gameId());
        assertEquals(0L, rec.state.version(), "失败整批不提交");
        assertTrue(rec.events.isEmpty());
        assertEquals(1L, rec.nextSeq);
        assertNull(rec.currentJob);
        assertTrue(rec.idempotency.isEmpty());
        // 同 ID 终态失败幂等返回，不再执行
        CommandOutcome retry = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        assertEquals("INTERNAL_ERROR", assertInstanceOf(CommandOutcome.Failed.class, retry).code());
        assertTrue(executor.queue.isEmpty(), "终态失败不重新登记任务");
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
    }

    @Test
    void test_async_step_limit_marks_failed() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameState stuck = stateWithHands(
                List.of(List.of(4), List.of(8), List.of(12), List.of(16)),
                1, null, List.of(), Phase.PLAYING, null, 0L);
        ActionAdvancer noAdvance = (st, player, action) -> new EngineOutcome(stuck, List.of());
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, noAdvance,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        assertEquals("BOT_STEP_LIMIT", assertInstanceOf(CommandOutcome.Failed.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId())).code());
        assertEquals(0L, store.get(g.gameId()).state.version());
    }

    // ---- 查询边界与 GAME_BUSY ----

    @Test
    void test_async_command_status_auth_and_not_found() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CreatedGame other = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        assertEquals("SESSION_FORBIDDEN", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), other.sessionToken(), cmd.requestId())).code());
        assertEquals("SESSION_INVALID", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), null, cmd.requestId())).code());
        assertEquals("COMMAND_NOT_FOUND", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), g.sessionToken(), UUID.randomUUID().toString())).code());
        assertEquals("GAME_NOT_FOUND", assertThrows(GameRuleException.class,
                () -> svc.commandStatus("missing-game", g.sessionToken(), cmd.requestId())).code());
    }

    @Test
    void test_async_resolve_and_legal_actions_busy_while_pending() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        svc.submitCommand(g.gameId(), g.sessionToken(), playCommand(g.state().version(), single));

        assertEquals("GAME_BUSY", assertThrows(GameRuleException.class,
                () -> svc.resolve(g.gameId(), g.sessionToken(),
                        new ResolveRequest(g.state().version(), single.cardIds()))).code());
        assertEquals("GAME_BUSY", assertThrows(GameRuleException.class,
                () -> svc.legalActions(g.gameId(), g.sessionToken())).code());
    }

    // ---- 执行器拒绝与模式固定 ----

    @Test
    void test_async_executor_rejection_clears_job_and_allows_retry() {
        InMemoryGameStore store = new InMemoryGameStore();
        Executor rejecting = r -> { throw new RejectedExecutionException("full"); };
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, rejecting, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals("COMMAND_QUEUE_FULL", ex.code());
        assertNull(store.get(g.gameId()).currentJob, "拒绝后不留永久 PENDING");
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
        // 同 ID 原命令可重新登记（此前未执行）
        assertInstanceOf(CommandOutcome.Pending.class, svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
    }

    @Test
    void test_async_mode_fixed_at_creation() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        // 四参 create 的旧式记录 asyncMode=false：即使服务开了异步开关，该局仍走同步
        store.create("g-sync", "t-sync", SEED, GameEngine.newGame(SEED, PROFILE));
        GameService syncSvc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, executor, AsyncCommandConfig.syncDefaults());
        CommandRequest cmd = playCommand(0L, firstSingle(syncSvc.legalActions("g-sync", "t-sync")));
        assertInstanceOf(CommandOutcome.Completed.class, svc.submitCommand("g-sync", "t-sync", cmd));
        assertTrue(executor.queue.isEmpty(), "同步局不使用执行器");
    }

    @Test
    void test_sync_service_submit_command_returns_completed_immediately() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, executor, AsyncCommandConfig.syncDefaults());
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        CommandOutcome.Completed completed = assertInstanceOf(CommandOutcome.Completed.class,
                svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals(1L, completed.response().acceptedVersion());
        assertTrue(executor.queue.isEmpty());
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
    }

    // ---- 辅助（与 GameServiceTest 相同形态） ----

    private static MoveView firstSingle(LegalActionsResponse la) {
        return la.moves().stream().filter(m -> "SINGLE".equals(m.kind())).findFirst().orElseThrow();
    }

    private static CommandRequest playCommand(long version, MoveView mv) {
        return new CommandRequest(UUID.randomUUID().toString(), version,
                new ActionRequest("PLAY", mv.cardIds(), mv.interpretationKey()));
    }

    private static GameState stateWithHands(List<List<Integer>> hands, Integer currentPlayer,
                                            com.guandan.domain.model.Trick trick, List<Integer> finishOrder,
                                            Phase phase, com.guandan.domain.model.GameResult result, long version) {
        Set<Integer> union = new HashSet<>();
        for (List<Integer> h : hands) union.addAll(h);
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) discarded.add(id);
        }
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, result, version);
    }
}
```

- [ ] **Step 2: 运行确认红**

Run: `./mvnw.cmd -Dtest=AsyncCommandTest test`
Expected: 编译失败（`CommandOutcome`、`AsyncCommandConfig`、六参构造器、`processingRequestId` 不存在）。

- [ ] **Step 3: 新增四个服务层类型**

`CommandJob.java`：

```java
package com.guandan.service;

import java.util.List;

/** 处理中命令的不可变登记信息；可变状态只存在于受锁保护的 GameRecord。 */
record CommandJob(String requestId, List<Object> fingerprint, long startVersion,
                  long generation, long deadlineMillis) {
    CommandJob {
        fingerprint = List.copyOf(fingerprint);
    }
}
```

`AsyncCommandConfig.java`：

```java
package com.guandan.service;

/** 异步命令开关与时间预算；每局创建时固定 asyncEnabled，运行中改配置不影响已有牌局。 */
public record AsyncCommandConfig(boolean asyncEnabled, long batchTimeoutMillis, long pollAfterMillis) {
    public static AsyncCommandConfig syncDefaults() {
        return new AsyncCommandConfig(false, 12_000L, 500L);
    }
}
```

`CachedFailure.java`：

```java
package com.guandan.service;

import java.util.List;

/** 终态失败收据：同 ID 同体幂等返回 FAILED，不再执行旧命令。 */
record CachedFailure(List<Object> fingerprint, String code, String message) {
}
```

`CommandOutcome.java`：

```java
package com.guandan.service;

import com.guandan.api.dto.CommandResponse;

/** 命令提交与状态查询的统一结果：完成（原 CommandResponse）、处理中、终态失败。 */
public sealed interface CommandOutcome {
    record Completed(CommandResponse response) implements CommandOutcome {}
    record Pending(String requestId, long pollAfterMillis) implements CommandOutcome {}
    record Failed(String code, String message) implements CommandOutcome {}
}
```

- [ ] **Step 4: GameRecord / InMemoryGameStore 扩展**

`GameRecord` 新增字段与五参构造器（保留四参委托，asyncMode=false）：

```java
    final boolean asyncMode;
    final Map<String, CachedFailure> failures = new HashMap<>();
    CommandJob currentJob;
    long jobGeneration;

    GameRecord(String gameId, String sessionToken, long gameSeed, GameState state) {
        this(gameId, sessionToken, gameSeed, state, false);
    }

    GameRecord(String gameId, String sessionToken, long gameSeed, GameState state, boolean asyncMode) {
        this.gameId = gameId;
        this.sessionToken = sessionToken;
        this.gameSeed = gameSeed;
        this.state = state;
        this.asyncMode = asyncMode;
    }
```

`InMemoryGameStore.create` 增加五参重载，四参委托：

```java
    GameRecord create(String gameId, String sessionToken, long gameSeed, GameState initialState) {
        return create(gameId, sessionToken, gameSeed, initialState, false);
    }

    GameRecord create(String gameId, String sessionToken, long gameSeed, GameState initialState, boolean asyncMode) {
        GameRecord record = new GameRecord(gameId, sessionToken, gameSeed, initialState, asyncMode);
        games.put(gameId, record);
        tokenToGameId.put(sessionToken, gameId);
        return record;
    }
```

- [ ] **Step 5: GameView + DtoMapper 增加 processing_request_id**

`GameView` record 末尾追加组件（import `com.fasterxml.jackson.annotation.JsonInclude`）：

```java
                       List<EventView> roundEvents,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String processingRequestId) {
```

`DtoMapper`：三参 `toGameView` 委托新四参重载；四参版本在原构造末尾追加 `processingRequestId`：

```java
    public static GameView toGameView(String gameId, PlayerView v, List<EventView> roundEvents) {
        return toGameView(gameId, v, roundEvents, null);
    }

    public static GameView toGameView(String gameId, PlayerView v, List<EventView> roundEvents,
                                      String processingRequestId) {
        // …原映射体不变，new GameView(… , List.copyOf(roundEvents), processingRequestId);
    }
```

- [ ] **Step 6: GameService 异步编排**

新增 import：`org.springframework.beans.factory.annotation.Autowired`、`java.util.concurrent.Executor`、`java.util.concurrent.RejectedExecutionException`、`org.slf4j.Logger`、`org.slf4j.LoggerFactory`。

字段与构造器：

```java
    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final Executor executor;
    private final AsyncCommandConfig commandConfig;
    private final BotBatchRunner runner;

    @Autowired
    public GameService(InMemoryGameStore store, GameSeedSource seedSource, BotPolicy policy,
                       ActionAdvancer advancer, Executor commandExecutor, AsyncCommandConfig commandConfig) {
        this.store = store;
        this.seedSource = seedSource;
        this.policy = policy;
        this.advancer = advancer;
        this.executor = commandExecutor;
        this.commandConfig = commandConfig;
        this.runner = new BotBatchRunner(policy, advancer);
    }

    /** 同步便捷构造器：现有单元测试沿用，异步关闭、执行器为直接执行。 */
    public GameService(InMemoryGameStore store, GameSeedSource seedSource, BotPolicy policy,
                       ActionAdvancer advancer) {
        this(store, seedSource, policy, advancer, Runnable::run, AsyncCommandConfig.syncDefaults());
    }
```

`createGame` 改为 `store.create(gameId, sessionToken, seed, state, commandConfig.asyncEnabled());`。

`getGame` 锁内返回改用四参 `DtoMapper.toGameView(rec.gameId, …, currentRoundEvents(rec.events), rec.currentJob == null ? null : rec.currentJob.requestId())`。

`resolve` 与 `legalActions` 取锁后第一行加 `requireIdle(rec);`。

`submit` 改为委托（同步测试入口不变）：

```java
    public CommandResponse submit(String gameId, String sessionToken, CommandRequest command) {
        CommandOutcome outcome = submitCommand(gameId, sessionToken, command);
        if (outcome instanceof CommandOutcome.Completed c) {
            return c.response();
        }
        throw new IllegalStateException("命令进入异步处理，请使用 submitCommand/commandStatus");
    }
```

新增核心方法（原 submit 的锁内前段逻辑移入 submitCommand；同步批次段抽为 commitBatch）：

```java
    public CommandOutcome submitCommand(String gameId, String sessionToken, CommandRequest command) {
        GameRecord rec = authorize(gameId, sessionToken);
        CommandJob job;
        GameState origin;
        Action humanAction;
        long startSeq;
        rec.lock.lock();
        try {
            command.validate();
            List<Object> fingerprint = fingerprint(command);
            CachedCommand cached = rec.idempotency.get(command.requestId());
            if (cached != null) {
                if (cached.fingerprint().equals(fingerprint)) {
                    return new CommandOutcome.Completed(cached.response());
                }
                throw reused(command.requestId());
            }
            CachedFailure failed = rec.failures.get(command.requestId());
            if (failed != null) {
                if (failed.fingerprint().equals(fingerprint)) {
                    return new CommandOutcome.Failed(failed.code(), failed.message());
                }
                throw reused(command.requestId());
            }
            CommandJob pending = rec.currentJob;
            if (pending != null) {
                if (pending.requestId().equals(command.requestId())) {
                    if (pending.fingerprint().equals(fingerprint)) {
                        return new CommandOutcome.Pending(pending.requestId(), commandConfig.pollAfterMillis());
                    }
                    throw reused(command.requestId());
                }
                throw busy(pending);
            }
            requireVersion(rec.state, command.expectedVersion());
            if (!rec.asyncMode) {
                BotBatchRunner.BatchResult r = runner.run(rec.state, toAction(command.action()),
                        rec.gameSeed, rec.nextSeq);
                return new CommandOutcome.Completed(commitBatch(rec, command.requestId(), fingerprint, r));
            }
            humanAction = toAction(command.action());
            // 锁内预校验真人动作：非法同步拒绝，不登记任务；工作任务将从原始快照确定性重放
            advancer.applyAction(rec.state, HUMAN_SEAT, humanAction);
            origin = rec.state;
            startSeq = rec.nextSeq;
            job = new CommandJob(command.requestId(), fingerprint, origin.version(),
                    ++rec.jobGeneration, System.currentTimeMillis() + commandConfig.batchTimeoutMillis());
            rec.currentJob = job;
        } finally {
            rec.lock.unlock();
        }
        try {
            executor.execute(() -> runAsyncBatch(rec, job, origin, humanAction, startSeq));
        } catch (RejectedExecutionException e) {
            rec.lock.lock();
            try {
                if (rec.currentJob == job) {
                    rec.currentJob = null;
                }
            } finally {
                rec.lock.unlock();
            }
            throw new GameRuleException("COMMAND_QUEUE_FULL", "命令队列已满，请稍后重试", Map.of());
        }
        return new CommandOutcome.Pending(job.requestId(), commandConfig.pollAfterMillis());
    }

    public CommandOutcome commandStatus(String gameId, String sessionToken, String requestId) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            CommandJob pending = rec.currentJob;
            if (pending != null && pending.requestId().equals(requestId)) {
                return new CommandOutcome.Pending(requestId, commandConfig.pollAfterMillis());
            }
            CachedCommand cached = rec.idempotency.get(requestId);
            if (cached != null) {
                return new CommandOutcome.Completed(cached.response());
            }
            CachedFailure failed = rec.failures.get(requestId);
            if (failed != null) {
                return new CommandOutcome.Failed(failed.code(), failed.message());
            }
            throw new GameRuleException("COMMAND_NOT_FOUND", "命令不存在",
                    Map.of("request_id", String.valueOf(requestId)));
        } finally {
            rec.lock.unlock();
        }
    }

    private void runAsyncBatch(GameRecord rec, CommandJob job, GameState origin, Action humanAction, long startSeq) {
        try {
            BotBatchRunner.BatchResult r = runner.run(origin, humanAction, rec.gameSeed, startSeq);
            rec.lock.lock();
            try {
                if (!ownsJob(rec, job)) {
                    log.warn("丢弃迟到批次: game={} request={}", rec.gameId, job.requestId());
                    return;
                }
                commitBatch(rec, job.requestId(), job.fingerprint(), r);
                rec.currentJob = null;
            } finally {
                rec.lock.unlock();
            }
        } catch (GameRuleException e) {
            failJob(rec, job, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("异步批次内部故障: game={} request={}", rec.gameId, job.requestId(), e);
            failJob(rec, job, "INTERNAL_ERROR", "内部错误");
        }
    }

    /** 任务实例同一即涵盖 request_id 与 generation 核对；另核对起始版本，拒绝迟到写入。 */
    private static boolean ownsJob(GameRecord rec, CommandJob job) {
        return rec.currentJob == job && rec.state.version() == job.startVersion();
    }

    private void failJob(GameRecord rec, CommandJob job, String code, String message) {
        rec.lock.lock();
        try {
            if (!ownsJob(rec, job)) {
                return;
            }
            rec.failures.put(job.requestId(), new CachedFailure(job.fingerprint(), code, message));
            rec.currentJob = null;
        } finally {
            rec.lock.unlock();
        }
    }

    /** 锁内调用：一次提交状态、事件、序号与幂等收据。 */
    private CommandResponse commitBatch(GameRecord rec, String requestId, List<Object> fingerprint,
                                        BotBatchRunner.BatchResult r) {
        rec.state = r.state();
        rec.events.addAll(r.events());
        rec.nextSeq = r.nextSeq();
        GameView finalView = DtoMapper.toGameView(rec.gameId,
                PlayerViewFactory.getPlayerView(r.state(), HUMAN_SEAT), currentRoundEvents(rec.events));
        List<EventView> eventViews = new ArrayList<>(r.events().size());
        for (SequencedEvent se : r.events()) {
            eventViews.add(DtoMapper.toEventView(se.seq(), se.stateVersion(), se.event()));
        }
        CommandResponse response = new CommandResponse(requestId, r.acceptedVersion(),
                finalView, List.copyOf(eventViews));
        rec.idempotency.put(requestId, new CachedCommand(fingerprint, response));
        return response;
    }

    private void requireIdle(GameRecord rec) {
        if (rec.currentJob != null) {
            throw busy(rec.currentJob);
        }
    }

    private GameRuleException busy(CommandJob pending) {
        return new GameRuleException("GAME_BUSY", "本局有命令正在处理",
                Map.of("request_id", pending.requestId(), "poll_after_ms", commandConfig.pollAfterMillis()));
    }

    private static GameRuleException reused(String requestId) {
        return new GameRuleException("REQUEST_ID_REUSED", "request_id 已用于不同请求体",
                Map.of("request_id", requestId));
    }
```

删除旧 submit 中已移走的批次/提交代码；`fingerprint`、`requireVersion`、`toAction`、`currentRoundEvents`、`authorize`、`requireSession` 保持不变。

- [ ] **Step 7: ServiceConfig 装配 + application.yml**

`ServiceConfig` 新增（import `org.springframework.beans.factory.annotation.Value`、`java.util.concurrent.*`）：

```java
    @Bean
    AsyncCommandConfig asyncCommandConfig(
            @Value("${guandan.commands.async-enabled:false}") boolean asyncEnabled,
            @Value("${guandan.commands.batch-timeout-ms:12000}") long batchTimeoutMillis,
            @Value("${guandan.commands.poll-after-ms:500}") long pollAfterMillis) {
        return new AsyncCommandConfig(asyncEnabled, batchTimeoutMillis, pollAfterMillis);
    }

    @Bean(destroyMethod = "shutdown")
    Executor commandExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
```

`application.yml` 末尾追加：

```yaml
guandan:
  commands:
    async-enabled: false
    batch-timeout-ms: 12000
    poll-after-ms: 500
```

- [ ] **Step 8: 运行转绿 + 全量回归**

Run: `./mvnw.cmd -Dtest=AsyncCommandTest,GameServiceTest test` → 全绿
Run: `./mvnw.cmd test` → **276 tests**（262 + 14 新增；GameView 新组件为 NON_NULL，既有 JSON 断言不变），0 失败。若 OpenApiSpecTest drift 因 GameView schema 变化而红，属预期——在 Task 3 Step 6 重新导出后转绿；本步骤先运行 `-Dtest='!OpenApiSpecTest'` 排除并记录，Task 3 必须修复。

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/guandan/service/CommandJob.java \
        backend/src/main/java/com/guandan/service/AsyncCommandConfig.java \
        backend/src/main/java/com/guandan/service/CachedFailure.java \
        backend/src/main/java/com/guandan/service/CommandOutcome.java \
        backend/src/main/java/com/guandan/service/GameRecord.java \
        backend/src/main/java/com/guandan/service/InMemoryGameStore.java \
        backend/src/main/java/com/guandan/service/GameService.java \
        backend/src/main/java/com/guandan/service/ServiceConfig.java \
        backend/src/main/java/com/guandan/api/dto/GameView.java \
        backend/src/main/java/com/guandan/api/dto/DtoMapper.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/guandan/service/AsyncCommandTest.java
git commit -m "feat: register async commands with atomic batch commit"
```

---

### Task 3: HTTP 合同（200/202 联合、命令查询、错误码、OpenAPI）

**Files:**
- Create: `backend/src/main/java/com/guandan/api/dto/PendingResponse.java`
- Create: `backend/src/main/java/com/guandan/api/dto/CommandCompleted.java`
- Create: `backend/src/main/java/com/guandan/api/dto/CommandFailed.java`
- Create: `backend/src/test/java/com/guandan/api/AsyncHttpContractTest.java`
- Modify: `backend/src/main/java/com/guandan/api/GameController.java`
- Modify: `backend/src/main/java/com/guandan/api/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/com/guandan/api/OpenApiSpecTest.java`
- Regenerate: `docs/openapi.json`（`-Dopenapi.export=true`）

**Interfaces:**
- Consumes: Task 2 的 `GameService.submitCommand/commandStatus` → `CommandOutcome`（Completed/Pending/Failed）。
- Produces: `POST /api/v1/games/{id}/actions` → 200 `CommandResponse` | 200 `CommandFailed` | 202 `PendingResponse`；`GET /api/v1/games/{id}/commands/{requestId}` → 200 `CommandCompleted` | 200 `CommandFailed` | 202 `PendingResponse` | 404 `ErrorBody`。DTO：`PendingResponse(String requestId, String status, long pollAfterMs)`（便捷构造器固定 status="PENDING"）、`CommandCompleted(String status, CommandResponse result)`（status="COMPLETED"）、`CommandFailed(String status, ErrorBody error)`（便捷构造器 `(String code, String message)`）。

- [ ] **Step 1: 写失败测试 AsyncHttpContractTest**

```java
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        @Bean @Primary
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
```

注意：`firstSingleBody` 中 `om.convertValue(m.get("card_ids"), List.class)` 产生 raw List，若编译告警可加 `@SuppressWarnings("unchecked")`；`assertNotEquals` import 若未用到则删除。

- [ ] **Step 2: 运行确认红**

Run: `./mvnw.cmd -Dtest=AsyncHttpContractTest test`
Expected: 编译失败（`PendingResponse` 等不存在）或路由 404/返回形态不符。

- [ ] **Step 3: 新增三个 HTTP DTO**

`PendingResponse.java`：

```java
package com.guandan.api.dto;

/** 202 登记回执：命令已登记，不表示真人动作已提交。 */
public record PendingResponse(String requestId, String status, long pollAfterMs) {
    public PendingResponse(String requestId, long pollAfterMs) {
        this(requestId, "PENDING", pollAfterMs);
    }
}
```

`CommandCompleted.java`：

```java
package com.guandan.api.dto;

/** GET /commands 终态：完成，result 与原同步 CommandResponse 等价。 */
public record CommandCompleted(String status, CommandResponse result) {
    public CommandCompleted(CommandResponse result) {
        this("COMPLETED", result);
    }
}
```

`CommandFailed.java`：

```java
package com.guandan.api.dto;

import java.util.Map;

/** 终态失败：已整批回滚，同 ID 幂等缓存，不再执行。 */
public record CommandFailed(String status, ErrorBody error) {
    public CommandFailed(String code, String message) {
        this("FAILED", new ErrorBody(code, message, Map.of()));
    }
}
```

- [ ] **Step 4: GameController 路由与映射**

`submit` 改返回 `ResponseEntity<Object>` 并调 `submitCommand`；新增 `commandStatus` 路由。JDK 17 用 instanceof 链（不用 switch 模式匹配）。springdoc 注解显式声明 200/202 schema（import `io.swagger.v3.oas.annotations.Operation`、`io.swagger.v3.oas.annotations.responses.ApiResponse`、`io.swagger.v3.oas.annotations.responses.ApiResponses`、`io.swagger.v3.oas.annotations.media.Content`、`io.swagger.v3.oas.annotations.media.Schema`）：

```java
    @PostMapping("/games/{id}/actions")
    @Operation(summary = "提交真人命令：同步完成 200 CommandResponse；异步登记 202；同 ID 终态失败 200 CommandFailed")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "同步完成或同 ID 终态失败",
                    content = @Content(schema = @Schema(oneOf = {CommandResponse.class, CommandFailed.class}))),
            @ApiResponse(responseCode = "202", description = "已登记，机器人批次处理中",
                    content = @Content(schema = @Schema(implementation = PendingResponse.class)))
    })
    public ResponseEntity<Object> submit(@PathVariable("id") String id,
                                         @RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody CommandRequest body) {
        CommandOutcome outcome = service.submitCommand(id, BearerTokens.token(auth), body);
        if (outcome instanceof CommandOutcome.Completed c) {
            return ResponseEntity.ok(c.response());
        }
        if (outcome instanceof CommandOutcome.Pending p) {
            return accepted(p);
        }
        CommandOutcome.Failed f = (CommandOutcome.Failed) outcome;
        return ResponseEntity.ok(new CommandFailed(f.code(), f.message()));
    }

    @GetMapping("/games/{id}/commands/{requestId}")
    @Operation(summary = "查询命令状态：202 处理中；200 终态（COMPLETED/FAILED）；404 未知命令或旧局")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "终态",
                    content = @Content(schema = @Schema(oneOf = {CommandCompleted.class, CommandFailed.class}))),
            @ApiResponse(responseCode = "202", description = "处理中",
                    content = @Content(schema = @Schema(implementation = PendingResponse.class))),
            @ApiResponse(responseCode = "404", description = "COMMAND_NOT_FOUND / GAME_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    })
    public ResponseEntity<Object> commandStatus(@PathVariable("id") String id,
                                                @PathVariable("requestId") String requestId,
                                                @RequestHeader(value = "Authorization", required = false) String auth) {
        CommandOutcome outcome = service.commandStatus(id, BearerTokens.token(auth), requestId);
        if (outcome instanceof CommandOutcome.Completed c) {
            return ResponseEntity.ok(new CommandCompleted(c.response()));
        }
        if (outcome instanceof CommandOutcome.Pending p) {
            return accepted(p);
        }
        CommandOutcome.Failed f = (CommandOutcome.Failed) outcome;
        return ResponseEntity.ok(new CommandFailed(f.code(), f.message()));
    }

    private static ResponseEntity<Object> accepted(CommandOutcome.Pending p) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new PendingResponse(p.requestId(), p.pollAfterMillis()));
    }
```

新增 import：`com.guandan.api.dto.CommandCompleted`、`CommandFailed`、`PendingResponse`、`ErrorBody`、`com.guandan.service.CommandOutcome`。`SessionAuthInterceptor` 的路径模式 `/api/v1/games/*/**` 已覆盖新子路由，无需改动（在测试中验证 403/401 生效）。

- [ ] **Step 5: ApiExceptionHandler 新错误码**

`STATUS_BY_CODE` 追加三项：

```java
            Map.entry("GAME_BUSY", HttpStatus.CONFLICT),
            Map.entry("COMMAND_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("COMMAND_QUEUE_FULL", HttpStatus.SERVICE_UNAVAILABLE),
```

- [ ] **Step 6: OpenApiSpecTest 扩展 + 导出规范**

`spec_covers_game_routes_and_snake_case_action_fields` 的路径数组追加 `"/api/v1/games/{id}/commands/{requestId}"`，并新增断言：

```java
        assertTrue(spec.at("/components/schemas/GameView/properties").has("processing_request_id"),
                "GameView 应含可选 processing_request_id");
        assertTrue(spec.at("/paths/~1api~1v1~1games~1{id}~1actions/post/responses").has("202"),
                "POST /actions 应声明 202 异步登记");
        assertTrue(spec.at("/paths/~1api~1v1~1games~1{id}~1commands~1{requestId}/get/responses").has("202"),
                "GET /commands 应声明 202 处理中");
        assertTrue(spec.at("/paths/~1api~1v1~1games~1{id}~1commands~1{requestId}/get/responses").has("404"),
                "GET /commands 应声明 404");
```

（JSON Pointer 中 `/` 转义为 `~1`。）

Run: `./mvnw.cmd -Dtest=AsyncHttpContractTest,GameControllerTest,OpenApiSpecTest test`
Expected: AsyncHttpContractTest 与 GameControllerTest 绿；OpenApiSpecTest 的 drift 检查红（规范未导出）。
Run: `./mvnw.cmd -Dtest=OpenApiSpecTest -Dopenapi.export=true test` → 导出 `docs/openapi.json` 后全绿。

- [ ] **Step 7: 全量回归**

Run: `./mvnw.cmd test`
Expected: **283 tests**（276 + 7 HTTP 新增），0 失败。GameControllerTest 原 10 例在默认同步上下文下不变。

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/guandan/api/dto/PendingResponse.java \
        backend/src/main/java/com/guandan/api/dto/CommandCompleted.java \
        backend/src/main/java/com/guandan/api/dto/CommandFailed.java \
        backend/src/main/java/com/guandan/api/GameController.java \
        backend/src/main/java/com/guandan/api/ApiExceptionHandler.java \
        backend/src/test/java/com/guandan/api/AsyncHttpContractTest.java \
        backend/src/test/java/com/guandan/api/OpenApiSpecTest.java \
        docs/openapi.json
git commit -m "feat: expose async command HTTP contract"
```

---

### Task 4: 文档同步、前端 schema 与阶段 A 收尾回归

**Files:**
- Modify: `docs/api-v1.md`（新增异步合同章节，保留原同步合同）
- Modify: `docs/llm-bot-decision-design.md`（HTTP 表补 COMMAND_QUEUE_FULL；状态行更新）
- Regenerate: `frontend/src/api/schema.d.ts`（`npm run gen:api`）

**Interfaces:**
- Consumes: Task 3 导出的 `docs/openapi.json`。
- Produces: 与代码一致的 API 文档与前端类型；阶段 A 验收记录（测试数量、命令输出）。

- [ ] **Step 1: 更新 docs/api-v1.md**

在幂等/错误码章节后新增「异步命令模式（阶段 A，开关 `guandan.commands.async-enabled`，默认关闭）」章节，内容必须包含：

```markdown
## 异步命令模式（新增合同，默认关闭）

开关 `guandan.commands.async-enabled=true` 时，新建牌局固定为异步模式；已有同步局不受影响。
同步模式的全部原合同（200 CommandResponse、错误码、幂等顺序）保持不变。

- `POST /api/v1/games/{id}/actions`
  - 异步登记成功：`202 {"request_id":"…","status":"PENDING","poll_after_ms":500}`。202 仅表示已登记，不表示真人动作已提交；牌局对外保持上一个已提交快照。
  - 同 ID 同体处理中：同一 `202` 回执，不重复登记。
  - 同 ID 完成后的重试：原 `200 CommandResponse`（幂等收据）。
  - 同 ID 终态失败的重试：`200 {"status":"FAILED","error":{"code","message","details"}}`，不再执行。
  - 同 ID 异体：`409 REQUEST_ID_REUSED`（先于版本检查）。
  - 不同 ID 且本局处理中：`409 GAME_BUSY`，details 仅含 `request_id` 与 `poll_after_ms`。
  - 版本不符（无处理中任务）：`409 VERSION_CONFLICT`，不登记任务。
  - 非法真人动作：同步 4xx 拒绝，不登记任务。
  - 执行器队列满：`503 COMMAND_QUEUE_FULL`，任务已清除，可用原 ID 重试。
- `GET /api/v1/games/{id}/commands/{requestId}`（仅本局令牌）
  - 处理中：`202`（同登记回执形态）。
  - 完成：`200 {"status":"COMPLETED","result":CommandResponse}`。
  - 失败：`200 {"status":"FAILED","error":ErrorBody}`；FAILED 表示整批已回滚，可刷新后以新 ID 发起新命令。
  - 未知命令：`404 COMMAND_NOT_FOUND`（可能原 POST 未登记，前端应以原 ID 原体重发 POST）。
  - 旧局失效：`404 GAME_NOT_FOUND`。
  - 查询不触发第二次执行。
- `GET /api/v1/games/{id}`：GameView 新增可选 `processing_request_id`；处理中返回旧快照并携带该字段，无处理中命令时字段缺省。
- 本局处理中时 `POST /resolve` 与 `GET /legal-actions` 返回 `409 GAME_BUSY`。
- 命令状态机：PENDING → COMPLETED | FAILED，终态不可变；每局同时最多一个未完成命令。
- 进程重启仍使整局与命令一并失效（内存模式边界不变）。
```

错误码表追加 `GAME_BUSY(409)`、`COMMAND_NOT_FOUND(404)`、`COMMAND_QUEUE_FULL(503)` 三行。

- [ ] **Step 2: 更新设计文档**

`docs/llm-bot-decision-design.md`：
1. 状态行改为「阶段 A 已实施（异步本地策略）；B–E 待实施」。
2. HTTP 合同草案表 `POST /actions` 行补充：执行器队列满返回 `503 COMMAND_QUEUE_FULL`（任务已清除，可原 ID 重试）；并在表后说明该码为实现期新增、已同步 api-v1.md。

- [ ] **Step 3: 重新生成前端 schema 并验证前端不受影响**

Run（frontend/）: `npm run gen:api`
Run: `npm run test:unit -- --run` → 96 tests 全绿
Run: `npm run typecheck` → exit 0
Run: `npm run build` → exit 0
（本阶段不改任何前端行为代码；schema 新增可选字段不得破坏现有类型。）

- [ ] **Step 4: 后端全量最终回归**

Run（backend/）: `./mvnw.cmd -Dtest=GameServiceTest,OpenApiSpecTest test` → 全绿
Run: `./mvnw.cmd test` → 283 tests，0 失败。记录精确输出数字用于交付报告。

- [ ] **Step 5: Commit**

```bash
git add docs/api-v1.md docs/llm-bot-decision-design.md frontend/src/api/schema.d.ts
git commit -m "docs: document async command contract and refresh schemas"
```

- [ ] **Step 6: 停下交评审（阶段 A 验收门）**

向用户报告：文件清单、四个提交哈希、精确测试数量（后端 283 / 前端 96）、API 示例（202 回执、GET commands 终态）、未通过项（如有）、明确声明**未接任何真实模型**、B–E 待放行。不得自行进入阶段 B。

---

## Self-Review 记录

- **Spec 覆盖（交接单 A.1–A.4）**：同 ID 同体只登记一次（Task 2 测试 2）；同 ID 异体 REQUEST_ID_REUSED（测试 3 + HTTP）；不同 ID GAME_BUSY（测试 4 + HTTP）；版本冲突优先（测试 5）；GET commands 鉴权/404/GAME_NOT_FOUND（测试 10 + HTTP）；终态失败不重执行（测试 8/9 + HTTP）；OpenAPI/DTO 200/202 联合与 processing_request_id（Task 3）；测试开关下本地策略走异步、整批一次提交、旧快照不变（测试 1/6）；指定测试命令与全量（Task 2/3/4）。A 阶段前端不启用异步——仅 regen schema，符合交接单。
- **占位符扫描**：无 TBD/TODO；所有代码步骤给出完整代码；两处「以实际 record 组件为准」的核对提示指向真实文件（Move.java），属验证指令而非占位。
- **类型一致性**：`CommandOutcome.Completed/Pending/Failed`、`BatchResult`、`AsyncCommandConfig`、`PendingResponse/CommandCompleted/CommandFailed` 在 Task 2/3 间签名一致；JDK 17 instanceof 链贯穿；`toGameView` 四参重载在 Task 2 Step 5 定义、Step 6 使用。
- **已知边界（有意留到 B）**：deadlineMillis 已登记但看门狗未实现；迟到代次拒绝已有 ownsJob 守卫但无超时重算测试；执行器拒绝清理已在 A 实现（B 再补并发压测）。这些不构成 A 的验收缺口，交接单 B.1/B.2 明确承接。