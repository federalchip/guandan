# 掼蛋 v1 MVP Implementation Plan：开发与测试计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本次只制定计划，尚未实现产品代码；执行时先读完整文档，按任务顺序推进。

**Goal:** 实现固定打 2、单局两副标准牌共 108 张、1 名真人加 3 名合法随机机器人的网页版掼蛋 MVP，后端统一裁决并能稳定结算。

**Architecture:** Vue 负责展示和交互，Spring Boot/Spring MVC 提供 HTTP 接口，独立的纯 Java 规则引擎维护牌局并生成合法动作。真人和机器人共用动作验证入口；每次真人操作后，后端执行机器人动作，直到再次轮到真人或牌局结束，再返回公开事件与最终状态。v1 使用单 JVM 进程内存存储，不接大模型。

**Tech Stack:** Vue 3、TypeScript、Vite、npm；JDK 17、Spring Boot 3.5.16、Spring MVC、Jackson、Bean Validation、Maven Wrapper；JUnit Jupiter、QuickTheories 0.26、MockMvc、Vitest、Playwright。T2 锁定 Maven Wrapper 与 Maven 插件版本；Spring Boot 官方系统要求确认 3.5.16 支持 Java 17。

**Spec:** 本文“V1需求与规则合同”“架构与接口合同”两节就是本计划实现的设计依据；执行者必须同时阅读，不能仅阅读任务列表。

## Global Constraints

- v1 固定级数为 2；每局使用两副标准牌，共 108 张实体牌，只进行一次发牌与结算，没有跨局状态。页面统一显示“单局 · 两副牌 108 张 · 固定打 2”。
- 真人固定座位 0；搭档为 2；另一个队伍为 1、3；座位按出牌方向编号，轮转为 0→1→2→3→0。
- v1 固定真人先出，作为明确的开局简化；不实现翻牌确定首家。
- 后端是唯一裁决者；前端只做操作检查、提交防重、错误显示与状态恢复，不实现第二套完整规则。
- 机器人只从合法动作中随机选择；不调用 LLM，不训练模型，不使用其他玩家的隐藏手牌选牌。
- 规则引擎不依赖 Spring MVC、Jackson、Bean Validation、数据库或前端代码；HTTP DTO 与领域模型分开。
- 固定使用 JDK 17；前端运行环境采用 Node.js 22.12 或更高的兼容版本，创建项目时核对 Vite 的运行要求；Maven Wrapper、pom.xml 的框架/插件/依赖版本和前端 package-lock.json 一起纳入版本控制。
- 不实现账号、好友房间、多人联机、数据库、Redis、WebSocket、排行榜、付费、美术资产制作或原生 App。
- 只运行一个后端 JVM 进程；内存牌局在服务重启后消失，页面明确提示并允许重新开始。
- v1 面向本地单进程使用；牌局、事件、令牌与幂等记录在该进程生命周期内保留，统一在重启时清空；暂不实现 TTL、后台清理或持久化，不承诺长期无人值守运行。
- 不以删掉逢人配、接风或复杂牌型来换取测试通过；如果这些规则阻塞，先修复规则模块。
- 所有工期和性能数字都是规划或验收目标，尚无产品运行结果。

## Review Focus

1. 同一组选牌存在多个有效牌型或顺子端点：返回可选声明，不任意猜测；由 T3、T7、T9 验证。
2. 生成器合并完全相同实体牌的候选：任意实际选牌仍可被独立解析；不同花色不得错误合并；由 T4a–T4c、T7 验证；T4 即记录固定复杂手牌基准，M2 即测量完整单局耗时。
3. 最后一手牌被其他人压住、或出完牌者退出响应集合：接风与新领出者必须正确；由 T5 验证。
4. 请求超时后重试、刷新或两个页面同时操作：不重复扣牌，不覆盖较新状态，不重播已被快照覆盖的事件；pending 命令绑定 game_id，不跨局重试；由 T7、T9、T11 验证。
5. 结算时仍有人持牌、以及服务重启后的旧会话：牌数保持守恒，不暴露隐藏牌，不无限等待；由 T5、T7、T11 验证。

---

## V1需求与规则合同

### 1. 用户流程与范围

1. 打开页面，点击“开始一局”。
2. 后端创建 108 张实体牌，洗牌后每人分配 27 张，固定打 2、真人先出。
3. 真人可选牌、取消选牌、查看提示、出牌或在允许时过牌。
4. 服务端解析所选实体牌；若存在多种有效声明，页面展示牌型及比较点数，让真人选择。
5. 服务端验证并执行动作，机器人顺次操作；页面按返回事件展示出牌过程，再显示最终状态。
6. 真人先出完时，剩余机器人继续打到规定的结束条件；真人可以观看结果。
7. 结算显示头游所在队、名次或双下；“再来一局”创建新游戏，级数仍为 2。
8. 刷新页面通过保存的游戏 ID、会话令牌恢复；服务重启导致找不到游戏时，提示重新开始。

v1 不计算升级，不进贡，不还贡，不抗贡；不做跨局从 2 到 A 的胜负。界面始终显示“单局 · 两副牌 108 张 · 固定打 2”，避免把“一局”误读为仅使用一副标准牌。

### 2. 固定规则版本

规则版本 ID：`gd-v1-level2`。下面是明确的 v1 实现合同，参考文末规则资料，但不声称覆盖全部地方玩法；其中真人先出、纯百搭对子处理和结算展示是本项目明确选择的约定。如用户要调整，应在 T1 同时修改合同与预期用例，不能在实现途中悄悄换规则。

| 项目 | v1 决定 |
|---|---|
| 实体牌 | 两副标准牌，含每副两个王，共 108 张；每张牌有唯一 ID |
| 级牌 | 2；两张红桃 2 是逢人配 |
| 单张大小 | 大王 > 小王 > 2 > A > K > … > 3 |
| 单张百搭 | 红桃 2 单出时按级牌使用，不能声明为王或其他单张 |
| 纯百搭对子 | 两张红桃 2 单独成对时按级牌对子处理，这是 v1 明确约定 |
| 对子 | 同点数两张；两小王或两大王可以成对；大小王混合不能成对 |
| 三同张 | 三张相同点数；不能用百搭补成三张王 |
| 三带对 | 三同张加另一点数的对子，三张的点数与对子点数不同；王对子可作带牌，但不能由百搭伪造 |
| 顺子 | 恰好 5 张；自然点数连续；允许 A2345、10JQKA；不允许 KA234 等绕接；王不能参与 |
| 三连对 | 恰好 3 对；允许 AA2233 和 QQKKAA；不允许 KKAA22 |
| 钢板 | 恰好两个连续三同张；允许 AAA222 和 KKKAAA；不允许环绕连接 |
| 同花顺 | 恰好 5 张、同花色、自然点数连续；百搭可声明对应花色和点数 |
| 普通炸弹 | 同点数 4–10 张；百搭可以补牌，不能作为王；同一实体牌不能使用两次 |
| 天王炸弹 | 两大王加两小王，必须为四张真实王 |
| 百搭替换 | 与其他牌组合时，可补普通牌的点数及同花顺花色；不能替代大小王 |
| 自然顺序 | 顺子、三连对、钢板、同花顺中的级牌按自然点数参与，不提升到 A 上方 |
| 普通牌比较 | 必须同牌型、同张数；三带对比较三同张点数，连牌比较规范化的高端点；相等不能压 |
| 重叠牌型 | 按所选有效声明执行比较；同一组牌支持普通顺子或同花顺时可选择声明，不能自动强制最大牌型；这是 v1 明确口径 |
| 炸弹体系 | 天王炸弹 > 10 至 6 张炸弹 > 同花顺 > 5 张炸弹 > 4 张炸弹 > 普通牌型 |
| 同类炸弹 | 同张数时按级牌提升后的点数比较；张数优先于点数 |
| 同花顺比较 | 比较规范化高端点，花色不分大小；A2345 的高端点为 5 |
| 领出 | 无待压制牌时必须出牌，不允许过牌 |
| 跟牌 | 可以过牌；出牌必须压过当前有效桌面牌 |

不要把所有牌型强行放进一个可比较的全序：普通对子不能因为张数多就压普通单张；同花顺通过炸弹体系比较。

“两张红桃 2 单独成对”只约束两张牌作为一次 PAIR 出牌，此时仅是级牌对子；与其他实体牌组成三带对时，两张百搭可以组成任意非王点数的带牌对子，但该点数必须不同于三张主牌。带牌点数不参与压制比较，主牌型、张数和主比较点数相同的绑定按第 3 节合并；展示用绑定从有效点数中按自然点数选最小值。这一口径由下文 P02、P03 的完整人工预期固定。

### 3. 百搭声明与等价性

- `classify` 返回所有在牌型、比较点数方面不同的有效声明。
- 同一批实体牌，如果牌型、长度、主比较点数完全一致，且绑定差异不改变后续可压制关系，则归为同一个语义声明；保留一份确定的百搭绑定用于展示。
- 例如打 2 时，`S3 S4 S5 S6 H2` 可声明为黑桃 23456 或 34567 同花顺，高端点分别为 6、7；按本项目声明口径，也保留其普通顺子解释。
- 同一组牌也可能同时支持普通牌型与炸弹体系中的牌型，不能只返回第一个匹配结果。
- 真人明确选择声明；机器人选择已解析的合法动作；服务端再次验证，不能信任客户端传入的大小或百搭绑定。
- 声明键由服务端生成；同一版本、同一批牌、同一语义声明得到稳定的键。客户端仅传回键，不自行构造其含义。

### 4. 回合与结算

用“当前有效出牌者 + 尚需响应的活跃玩家集合”表达一轮，避免写死连续三次过牌。

1. 有人出牌成功：桌面有效牌更新；所有仍持牌、且不是本次出牌者的玩家，重新成为待响应者。
2. 某人过牌：从待响应集合移除；过牌后如果别人又出新牌，该玩家仍可以在之后的轮转中重新响应。
3. 某人出完牌：记录其实际出完次序，之后跳过该玩家；先检查本局是否已经满足结算条件。
4. 未结算且待响应集合为空：本轮结束。有效出牌者仍持牌，则由其领出；已出完，则由仍持牌的搭档接风。通用引擎可保留搭档不活跃时沿出牌方向选下一活跃者的回退，但 v1 正常结算条件通常先结束。
5. 最后一手牌被别人压住时，以后者作为本轮有效出牌者，不再给已出完者的搭档自动接风。
6. 同一队包揽头游、二游时直接结束：结果为第一名、第二名、另一队双下，不虚构剩余两人的第三/第四顺序。
7. 其他情况下，第三名实际出完后结束，剩余一人为末游；其未出的牌仍保留在手中。
8. 赢家队伍定义为头游所属队；只显示本局结果，不修改级数。

`finish_order` 只记录真正出完的人；结果用 `placement_groups` 表示名次组。例如 `((0,), (2,), (1,3))` 表示队友包揽前二、另一队双下；普通结果可为 `((0,), (1,), (2,), (3,))`，其中 3 可能仍持牌。

## 架构与接口合同

### 1. 为什么后端权威、前端轻校验

前端检查：是否选牌、实体 ID 是否重复、是不是操作状态、请求是否正在发送、所选牌是否仍在当前手牌。完整牌型识别、大小比较、合法动作和回合推进都在后端。

断网时保留已看到的牌桌，禁止继续本地出牌，提供重试和状态恢复。未来若需要离线模式，应明确单独设计；本版本的“兜底”不代表前端接管隐藏牌局。

v1 用 HTTP 请求足够。规则和服务不依赖 HTTP；v2 如加入 WebSocket，只替换消息入口/推送，不能重写牌型判定。未来多人房间、账号和持久化需要新增工程工作，当前边界只减少重写，不能消除扩展成本。

### 2. 目录与职责

| 路径 | 职责 |
|---|---|
| `backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/*` | 锁定 JDK 17 编译目标、依赖、测试插件与 Maven Wrapper |
| `backend/src/main/java/com/guandan/domain/model/*.java` | Card、Move、Action、GameState、事件和玩家视图等 record/enum |
| `backend/src/main/java/com/guandan/domain/Deck.java` | 108 张实体牌目录、洗牌与发牌 |
| `backend/src/main/java/com/guandan/domain/RuleProfile.java` | 固定规则版本与比较点数映射 |
| `backend/src/main/java/com/guandan/rules/MoveClassifier.java` | 所选牌的有效声明与百搭解析 |
| `backend/src/main/java/com/guandan/rules/MoveComparator.java` | 牌型压制比较 |
| `backend/src/main/java/com/guandan/rules/LegalMoveGenerator.java` | 按牌型模板生成合法出牌候选 |
| `backend/src/main/java/com/guandan/engine/GameEngine.java` | 新一局、动作验证、纯状态转移 |
| `backend/src/main/java/com/guandan/engine/PlayerViewFactory.java` | 隐藏信息过滤 |
| `backend/src/main/java/com/guandan/bots/RandomLegalPolicy.java` | 从合法动作随机选择 |
| `backend/src/main/java/com/guandan/service/InMemoryGameStore.java` | 单 JVM 内存记录与每局锁 |
| `backend/src/main/java/com/guandan/service/GameService.java` | 请求幂等、原子执行真人动作与机器人动作批次 |
| `backend/src/main/java/com/guandan/api/dto/*.java`、`ApiExceptionHandler.java` | HTTP 请求/响应 DTO、严格校验、统一错误码 |
| `backend/src/main/java/com/guandan/api/GameController.java`、`backend/src/main/java/com/guandan/GuandanApplication.java` | HTTP 路由、应用与运行入口 |
| `frontend/src/api/game.ts` | HTTP 客户端、错误码与重试 |
| `frontend/src/state/game.ts` | 可见快照、选牌、请求生命周期与事件播放状态 |
| `frontend/src/components/*.vue` | Card、Hand、Table、ActionBar、DeclarationPicker、ResultPanel |
| `frontend/src/App.vue` | 页面组合 |
| `backend/src/test/resources/fixtures/*.json` | 人工预期规则用例、完整声明集合与短手牌牌局 |
| `backend/src/test/java/com/guandan/oracle/SmallHandOracle.java` | 仅用于测试的小手牌慢速参照实现 |
| `backend/src/test/resources/fixtures/benchmark_hands.json` | 固定的 27 张复杂手牌、跟牌输入与稳定 case_id；T4 和 T10 共用 |
| `backend/src/test/java/com/guandan/engine/M2SmokeTest.java` | M2 少量完整对局、每步不变量和整局耗时初测 |
| `backend/src/main/java/com/guandan/cli/GuandanCli.java` | 自动对局、复现与性能记录入口；主程序先分派 CLI 命令，不启动 HTTP 服务 |
| `frontend/e2e/*.spec.ts` | 浏览器端到端流程 |
| `docs/rules-v1.md`、`docs/api-v1.md`、`docs/test-report-v1.md` | 实际实现的规则、接口与验证结果 |
| `README.md` | 安装、启动、验证与已知限制 |

脚手架、依赖配置归入需要它们的任务，不单独追求“架构搭好了”的里程碑。

### 3. 领域类型

以下名字与含义在各任务中保持一致。领域值类型使用 JDK 17 record/enum；record 构造器对嵌套集合做防御性复制，不能只依赖 record 自身的浅层不可变。领域层不使用 Spring/Jackson 注解。

| 类型 | 字段或含义 |
|---|---|
| `CardId`、`PlayerId` | Java `int`；卡牌 ID 为 0–107，座位 ID 为 0–3；公开字段仍名为 card_id/player_id |
| `Card` | `card_id, deck_index, rank, suit`；普通 rank=2–14，15=小王，16=大王；suit 为 S/H/D/C/J |
| `RuleProfile` | `profile_id="gd-v1-level2", level_rank=2, first_player=0, turn_order=(0,1,2,3)` |
| `MoveKind` | SINGLE、PAIR、TRIPLE、FULL_HOUSE、STRAIGHT、PAIR_RUN、TRIPLE_RUN、BOMB、STRAIGHT_FLUSH、JOKER_BOMB |
| `Move` | `cardIds: List<Integer>, kind, mainRank, length, wildBindings, interpretationKey`；普通三带对的 mainRank 是三张点数，连牌是高端点；JSON 字段仍用 snake_case |
| `Action` | `kind: PLAY/PASS, cardIds: List<Integer>, interpretationKey: String`；PASS 不带牌和声明，Java 可用分离的不可变子类型 |
| `Trick` | `ownerId, move, pendingResponders: Set<Integer>` |
| `GameResult` | `winnerTeam: int, placementGroups: List<List<Integer>>, outcome: DOUBLE_UP/TEAMMATE_THIRD/TEAMMATE_LAST` |
| `GameState` | `profile, hands: List<List<Integer>>, discarded: List<Integer>, currentPlayer: Integer, trick: Trick, finishOrder, phase: PLAYING/FINISHED, result, version`；`discarded` 含当前 `trick` 的实体牌，`trick` 不另计数；结束时 currentPlayer=null |
| `PublicTrick` | `ownerId, move, cards: List<Card>`；只有已公开打出的实体牌 |
| `PlayerView` | `ownHand: List<Card>, remainingCounts: List<Integer>, trick: PublicTrick, finishOrder, currentPlayer: Integer, isLead: boolean, canPass: boolean, profileId: String, levelRank: int, phase, result: GameResult, version: long`；不含其他人的手牌和服务端种子 |
| `DomainEvent` | `event_type, actor_id, payload`；包括 PLAYED、PASSED、TRICK_CLOSED、PLAYER_FINISHED、GAME_FINISHED；payload 只含公开信息 |
| `EngineOutcome` | `state: GameState, events: List<DomainEvent>` |
| `GameRuleException` | `code: String, message: String, details: Map<String,Object>`；非法动作抛出，输入状态不发生修改 |

普通牌实体 ID 公式：`deck_index * 54 + (rank - 2) * 4 + suit_index`，花色顺序 S/H/D/C。每副第 52、53 个位置分别为小王、大王。因此红桃 2 的 ID 为 1、55。重复牌面仍是不同实体。

领域接口：

```java
List<Card> Deck.buildDeck();
List<List<Integer>> Deck.deal(long seed);
List<Move> MoveClassifier.classify(List<Integer> cardIds, RuleProfile profile);
boolean MoveComparator.canBeat(Move challenger, Move previous, RuleProfile profile);
List<Move> LegalMoveGenerator.generateLegalMoves(List<Integer> hand, Move previous,
                                                 RuleProfile profile); // previous 可为 null
GameState GameEngine.newGame(long seed, RuleProfile profile);
List<Move> GameEngine.resolveSelection(GameState state, int playerId, List<Integer> cardIds);
List<Action> GameEngine.getLegalActions(GameState state, int playerId);
EngineOutcome GameEngine.applyAction(GameState state, int playerId, Action action);
PlayerView PlayerViewFactory.getPlayerView(GameState state, int playerId);
Action RandomLegalPolicy.chooseAction(PlayerView view, List<Action> legalActions,
                                      SplittableRandom rng);
```

`getLegalActions` 包括允许时的 PASS；生成器只生成 PLAY 候选。`resolveSelection` 独立解析玩家实际选中的 ID，不要求其恰好是生成器选出的重复实体代表。跟牌场景只返回能压住桌面牌的声明；没有声明返回空列表。牌 ID 越界、重复、不属于手牌或非当前玩家是错误，不当成普通无效牌型。

`applyAction` 对合法的单个 PLAY/PASS 增加一次 version；无效动作不增加。一个命令批次可能有多个真人/机器人动作，所以 HTTP 响应 version 可能增加多次。事件另有服务层分配的递增 `seq`，不能与 version 混为一谈。

### 4. 生成器与机器人合同

- 生产生成器：按点数/花色建立索引，再按牌型模板及百搭缺口生成，最后规范化声明、去重并过滤大小。
- 不在生产路径枚举 27 张牌的所有子集，也不把百搭无条件展开成 52×52 种牌。
- 完全相同的实体牌面可以选固定代表；不同花色、百搭使用与未使用导致的剩余牌差异必须保留。
- 候选语义去重键至少含：消耗的实体牌面多重集、牌型、张数、主比较点数；只有不影响剩余手牌与未来可压制关系的声明才可合并。
- `RandomLegalPolicy` 均匀选择传入的合法动作，包括允许时的 PASS；没有跟牌动作时只能 PASS，领出时至少存在一个单张动作。候选列表按稳定顺序返回，不能依赖 HashMap/HashSet 遍历次序影响同 seed 的选择。
- policy 只接收 PlayerView、合法动作和独立 RNG；不能接收 GameState，也不能访问存储层。
- 不设胜率指标；v1 指标是动作合法、没有读取隐藏牌、能结束、能复现。

### 5. 服务执行与一致性

服务层接口：

```java
CreatedGame GameService.createGame();
GameView GameService.getGame(String gameId, String sessionToken);
ResolveResponse GameService.resolve(String gameId, String sessionToken,
                                    long expectedVersion, List<Integer> cardIds);
LegalActionsResponse GameService.legalActions(String gameId, String sessionToken);
CommandResponse GameService.submit(String gameId, String sessionToken,
                                   CommandRequest command);
```

CreatedGame 包含随机游戏 ID、不可预测的临时 session_token、真人可见的 GameView。每局只允许该令牌控制座位 0；机器人座位不对外开放。服务端洗牌与机器人 RNG 种子仅用于内部复现，不返回浏览器。测试通过注入固定 seed/fixture，正式创建接口不接受客户端指定种子。

`CommandRequest = {request_id, expected_version, action}`。

`CommandResponse = {request_id, accepted_version, state, events}`；state 是 GameView，events 是本命令的公开事件，每条带 seq 和 state_version。accepted_version 是真人单个动作提交后的 version，不是整个机器人批次的最终 version。

传输模型统一定义在 `api/dto/`：`GameView` 是 PlayerView 的 JSON 表示并增加 game_id；因此 CreatedGame.state、CommandResponse.state 与 GET 返回值均使用 GameView，领域引擎仍只返回 PlayerView。`ResolveResponse={version, choices:list[MoveView]}`，`LegalActionsResponse={version, moves:list[MoveView], can_pass}`。MoveView 使用本文 Move 的字段，enum 转字符串、List 转数组；Jackson 仅序列化白名单 DTO，并显式映射为既定 snake_case JSON 字段。百搭绑定明确为 `{card_id, rank, suit}` 列表；只有同花顺的 suit 为所声明花色，其他牌型为 null。公开 PLAYED 事件 payload 只包含本次打出的 cards/声明及 actor 的剩余张数；事件不是完整 GameState 的序列化。其他事件使用公开身份、领出者、出完顺序和结果字段的白名单。

Action 请求用带 kind 的判别联合：PLAY 至少一张、最多十张，键可省略；PASS 不能带 card_ids 或 interpretation_key；额外字段禁止。resolve 的 card_ids 同样至少一张、最多十张。CommandRequest.request_id 为 UUID 字符串，expected_version 为非负整数；card_id 与 expected_version 使用严格整数，不将布尔值、浮点数或字符串自动转换。Jackson 的未知字段拒绝、标量强制转换和 null/缺失校验须显式配置或在 DTO 边界验证；Bean Validation 不能单独代替判别联合校验。Card 的 deck_index 为 0/1、rank 为整数、suit 为字符串，API 不传 HTML 片段。CreatedGame 中两处 game_id 必须一致。

提交事务顺序：

1. 验证游戏和令牌，取得该局锁。
2. 查 request_id：同 ID、同请求体返回原有响应，不再次执行；同 ID、不同请求体返回 409 REQUEST_ID_REUSED。请求体指 expected_version 与 action，不含 request_id。
3. 新请求核对 expected_version 与当前回合，复制工作状态与事件序号。
4. 在工作状态上执行真人动作，依次执行机器人动作，直到 current_player=0 且真人仍持牌，或 phase=FINISHED。
5. 每个机器人动作也经过 `GameEngine.applyAction`；正式新局用 `SecureRandom` 创建不返回浏览器的游戏 seed，发牌和机器人分别用 JDK 17 `SplittableRandom`。机器人种子由游戏 seed、当前 version、座位按固定字节序拼接后经 SHA-256 摘要取前 8 字节（大端 long）派生；固定 PRNG、Fisher–Yates 洗牌和候选顺序，保证同一 JDK 17 实现的失败重试和复现一致。禁止使用对象 hashCode、HashMap 遍历顺序或可变的共享 RNG 状态派生。
6. 成功后一次提交工作状态、事件和幂等响应；批次中任何规则/机器人异常都回滚，不能只扣掉真人牌后返回错误。
7. 每次命令机器人步数上限 512，超过时报 BOT_STEP_LIMIT 并回滚；正常单局中出牌次数不超过 108，过牌也受活跃响应集合限制，上限只作为发现 bug 的保护。

使用每局 `ReentrantLock` 和 Spring MVC 同步控制器方法；游戏记录及幂等账本在同一局锁内读取与一次提交，`ConcurrentHashMap` 仅管理局记录，不代替整局锁。CPU 计算不另起未受控后台任务，不在持锁期间模拟出牌动画或 sleep。内存工作状态与事件序号使用防御性复制，普通内存对象不依赖数据库 `@Transactional` 回滚。其他局使用独立锁，v1 不声称支持多 JVM 扩容。

本地存储生命周期：游戏创建后，状态、公开事件、会话令牌、该局锁及成功命令的幂等响应都保留到进程退出；已结算或被用户放弃的牌局暂不单独回收。“再来一局”只创建新游戏，不清理旧局的幂等账本。进程重启后统一清空，旧 game_id 按既定 404 流程恢复；禁止仅淘汰幂等记录却继续保留可操作的旧局。大量连续新建可能增加内存占用，v1 接受以本地重启清空的运行约定。未来可在 store 边界增加整局过期/删除与持久化，但必须同时规定重试有效期及过期响应；这些能力不进入 v1 验收。

客户端有两项独立防护：解析、等待声明选择、发送、恢复及动画播放期间禁止发起另一条出牌/过牌命令；服务端通过 request_id/expected_version 保证一致性。前端只接受同一 game_id 且 version 不小于当前已知版本的快照，避免延迟响应覆盖新状态；相同版本的重复响应只用于确认请求，不重新触发动画或清空新选牌。恢复 GET 按前端合同直接采用快照。

### 6. HTTP 合同

统一 `/api/v1` 前缀；除创建外使用 `Authorization: Bearer <session_token>`。路由不接收客户端 player_id。

| 方法与路径 | 请求 | 响应与行为 |
|---|---|---|
| `POST /games` | 空对象 | 201 CreatedGame；固定规则、真人先出 |
| `GET /games/{id}` | 无 | 200 GameView；供刷新与失败后恢复 |
| `GET /games/{id}/legal-actions` | 无 | 200 `{version, moves, can_pass}`；仅自己的合法动作；结束后 moves=[]、can_pass=false |
| `POST /games/{id}/resolve` | `{expected_version, card_ids}` | 200 `{version, choices}`；只解析不推进状态；多解释返回多个 choice |
| `POST /games/{id}/actions` | CommandRequest | 200 CommandResponse；执行真人及机器人批次 |
| `GET /health` | 无 | 200；不创建牌局 |

PLAY action：`{kind:"PLAY", card_ids:[...], interpretation_key:"..."}`。PASS action：`{kind:"PASS"}`。

错误响应统一为 `{code, message, details}`，正常响应不会泄露 traceback、seed 或其他人手牌。

| 状态码 | 错误码 |
|---|---|
| 400 | DUPLICATE_CARD_ID、CARD_NOT_OWNED、INVALID_MOVE、INVALID_DECLARATION、PASS_NOT_ALLOWED |
| 401 | SESSION_INVALID：无令牌或无效令牌 |
| 403 | SESSION_FORBIDDEN：令牌属于另一局 |
| 404 | GAME_NOT_FOUND：游戏不存在或服务重启丢失 |
| 409 | VERSION_CONFLICT、NOT_YOUR_TURN、GAME_FINISHED、REQUEST_ID_REUSED、AMBIGUOUS_DECLARATION |
| 422 | SCHEMA_INVALID：字段类型、ID 范围或请求结构不合法；将 Jackson/Bean Validation 错误规范化为此结构 |
| 500 | BOT_STEP_LIMIT、INTERNAL_ERROR：批次回滚，服务端记录可复现上下文 |

多声明但 PLAY 未带键时返回 AMBIGUOUS_DECLARATION 和有效 choices；存在唯一声明时可兼容省略键，页面正常流程仍总是传回服务端键。

错误优先级：缺少令牌先 401；携带令牌但游戏不存在先 404，以便服务重启后的旧会话正确恢复；游戏存在时再区分无效令牌 401 与其他局的有效令牌 403。同请求的幂等命中发生在版本检查之前，返回缓存响应；前端随后可 GET 获取更新的快照。

### 7. 前端交互合同

- 手牌展示按点数分组降序：大王 > 小王 > 级牌（v1 固定打 2，故 2 置于 A 之上）> A > K > … > 3；同点按花色 ♠>♥>♦>♣，再按 CardId 升序保证确定性。排序只影响展示，不改变实体身份（card_id 不变）与权威快照顺序。
- 第一次创建保存 game_id/session_token；选牌始终按 CardId。
- “出牌”先调用 resolve；0 个结果显示不可出，1 个直接提交，多结果由 DeclarationPicker 选择。
- “提示”从服务端合法动作中选一个，自动高亮对应实体牌；再次提示循环候选；不标注“最佳”。
- “过牌”按后端 can_pass 启用；服务端仍再次验证。
- 发送前将 `PendingCommand={game_id, command:CommandRequest}` 保存到当前标签页 sessionStorage；command 含完整 request_id、expected_version、action。该封装仅用于浏览器持久化，HTTP 请求体仍为 CommandRequest。重试只允许 pending.game_id 与当前游戏一致，使用该局令牌及原 ID/原请求体，不能生成新 ID 或把旧命令改投新局。
- 网络超时或结果不明时保留 pending，锁住新出牌/过牌并允许重试/恢复；成功确认后仅清除匹配 game_id/request_id 的 pending。明确的业务拒绝也清除对应 pending，再按错误类型修正选牌或 GET 恢复；旧请求的迟到响应不能清除另一条待确认命令。
- 刷新先 GET 恢复快照；若有同局 pending，原样重试以确认是否执行。GET 本身不能确认某个 request_id 是否已执行，不能仅因 version 增大就删除 pending；不属于当前局的 pending 丢弃且不得提交。
- 前端分别维护最新已知的服务端快照和动画展示状态。收到有效新响应时先记住最终 version；动画只改变展示状态，不修改权威快照或允许提前出牌。播放完直接应用最终快照，支持关闭动画直接应用。
- 是否播放以收到响应前的展示快照版本为基准：只有它等于该命令 expected_version、没有更新快照介入、且该批 request_id 尚未处理时，才播放完整批次。按 seq 顺序播放，同一批多个事件可共用 state_version，不能处理一个事件就按该版本跳过其余事件；同局事件 seq 不重复处理。
- GET 恢复成功且 version 不低于当前已知版本时，取消现有动画并直接采用快照；较旧的 GET 响应忽略，不打断当前展示。已采用快照的 version 及以前的事件全部视为被覆盖。之后重试得到的缓存响应若最终 version 不高于该快照，只确认 pending，不重播旧事件、不倒退牌数。若返回的是更高版本但缺少匹配的展示起点，直接采用较新最终快照，不播放残缺事件序列；旧版本响应只确认匹配请求。
- 动画播放中到达更新快照时，取消尚未播放的事件并直接采用最新快照；动画旧回调不得再覆盖它。解析、声明弹窗、提交、结果不明、恢复和播放期间禁止另起出牌/过牌流程；只有当前局已同步、没有 pending/动画且确实轮到真人时才恢复操作。
- 断网保留视图并显示失败/重试；服务重启后显示“本局已失效，请重新开始”。
- 成功切换新游戏时，清除旧局 pending、声明弹窗、提示候选和动画队列，重置事件去重状态；所有请求结果及动画回调校验所属 game_id 和本地展示代次，旧局或已取消代次的回调不得写入当前状态。若新游戏创建失败，保留旧局及其 pending。取消浏览器请求不代表撤销服务端已执行的动作。

## 开发任务与阶段验收

每个任务都按“预期用例 → 红灯 → 最小实现 → 绿灯 → 提交”执行。新模块不存在时用失败的 import/断言说明尚未实现，不把测试跳过当成红灯。脚手架不需要为工具配置本身写重复测试。

命令约定：后端命令从 `backend/` 运行，使用 JDK 17 和仓库中的 Maven Wrapper；前端命令从 `frontend/` 运行。JUnit Jupiter 测试类及 Maven 的 `slow`、`performance` profile 按下文约定执行，测试产物写入 `backend/artifacts/`。

### T1：规则合同与人工样例，4–7 小时

**Files:** `docs/rules-v1.md`、`backend/src/test/resources/fixtures/rule_cases.json`、`backend/src/test/resources/fixtures/turn_cases.json`。

**Interfaces:** 消费本文规则合同；输出规则版本 gd-v1-level2 与可机读 case_id、实体牌输入、expected 声明/错误/结算。

- [ ] 把本文规则与明确简化复制到 rules-v1.md，不增加升级和贡还功能。
- [ ] 用下方测试矩阵建立人工 expected 数据，覆盖每种牌型、百搭、比较与接风；牌张使用 `S3@0` 等符号，@0/@1 区分两副。P01–P07 必须按人工列出的**完整语义声明集合**录入，不能用判定器的输出反填；每个声明同时记录牌型、长度、主比较点数和规范化展示绑定。
- [ ] 检查每个样例没有超过两张同花同点实体牌、没有重复 CardId；短手牌场景中未参与的牌放到 discarded。按“四家手牌 + discarded = 108 个不同 ID”检查守恒，当前 trick 牌是 discarded 的子集。
- [ ] 人工核对低 A、级牌自然顺序、炸弹等级、双下结算四组预期，记录采用的明确约定。
- [ ] 提交规则与 fixture，提交信息 `docs: define guandan v1 rule contract`。

**验收:** expected 来自规则合同和人工核对，不能用待测函数的结果自动填入。

### T2：实体牌与领域数据，5–9 小时

**Files:** `backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/*`、`backend/src/main/java/com/guandan/domain/model/*.java`、`backend/src/main/java/com/guandan/domain/{Deck,RuleProfile}.java`、`backend/src/test/java/com/guandan/domain/{CardIds,DeckTest}.java`。

**Interfaces:** 输出本文全部领域类型、`Deck.buildDeck()`、`Deck.deal(seed)`、固定 `RuleProfile`；测试辅助 `CardIds.parse("S3@0 H3@0")` 返回 `List<Integer>`。

- [ ] 建立 JDK 17 Maven 工程，锁定 Spring Boot/JUnit/QuickTheories、Surefire、Spring Boot 可执行 Jar 和 Maven Wrapper 版本，设置最终产物名 `guandan-v1.jar`，配置 `slow`/`performance` profile；先准备测试运行依赖，尚不实现领域逻辑。
- [ ] 在 `DeckTest` 写 `deckHas108UniqueIds`、`eachFaceHasTwoCopies`、`levelHeartsAreIds1And55`、`dealIsPartitionAndReproducible`：分别断言 108 唯一 ID、普通牌面与王各两份、两百搭 ID、四份 27 张且同 seed 同结果。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=DeckTest test`，记录未实现的失败。
- [ ] 实现 Java record/enum、牌目录与 JDK 17 `SplittableRandom` 驱动的 Fisher–Yates 洗牌；所有嵌套集合做防御性复制。
- [ ] 再运行同一命令，要求全部 PASS；额外断言输入 deck 不被 deal 破坏。
- [ ] 提交 `feat: add guandan cards and domain models`。

### T3：所选牌解析与压制比较，12–20 小时

**Files:** `backend/src/main/java/com/guandan/rules/{MoveClassifier,MoveComparator}.java`、`backend/src/test/java/com/guandan/rules/{MoveClassifierTest,MoveComparatorTest}.java`。

**Interfaces:** 消费 T2；输出 `MoveClassifier.classify(cardIds, profile)`、`MoveComparator.canBeat(challenger, previous, profile)`，返回 Move 与本文声明键。

- [ ] 参数化人工 fixture，写 `test_classify_manual_cases`、`test_low_ace_has_correct_high_rank`、`test_wildcard_never_substitutes_joker`、`test_ambiguous_straight_keeps_both_endpoints`、`test_pure_wild_pair_is_level_pair`。
- [ ] 写比较断言：4 张炸弹 < 5 张炸弹 < 同花顺 < 6 张炸弹 < 天王炸弹；普通不同型不能压；同型同点不能压；单张 2>A，但序列不提升 2。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=MoveClassifierTest,MoveComparatorTest test`，确认红灯。
- [ ] 按牌型分解解析，规范化语义与百搭绑定；拒绝越界/重复实体 ID；比较函数按普通牌型与炸弹体系分别处理，不用统一排序值代替可压制规则。
- [ ] 同一命令全部 PASS；每种牌型都至少有正例、反例和边界例。
- [ ] 提交 `feat: classify guandan moves and compare strength`。

### T4：合法动作生成与独立验证，26–44 小时

**Files:** `backend/src/main/java/com/guandan/rules/LegalMoveGenerator.java`、`backend/src/test/java/com/guandan/oracle/{SmallHandOracle,SmallHandOracleTest}.java`、`backend/src/test/java/com/guandan/rules/{LegalMoveGeneratorTest,GeneratorOracleTest,GenerationBenchmarkTest}.java`、`backend/src/test/resources/fixtures/benchmark_hands.json`。

**Interfaces:** 消费 T3；输出 `LegalMoveGenerator.generateLegalMoves(hand, previous, profile)`，只返回 PLAY 候选的 Move。

T4a → T4b → T4c 顺序执行；每步独立验证和提交，不以最后一次大测试替代三个验收结果。

#### T4a：独立慢速参照，7–12 小时

**交付:** 测试专用的小手牌参照与人工预期测试；无需生产生成器即可运行。

**Interfaces:** 测试类提供 `SmallHandOracle.classify(cardIds, profile)` 与 `SmallHandOracle.generateMoves(hand, previous, profile)`，均返回 `List<Move>`；前者只判断完整的所选牌，后者枚举 n≤10 的手牌子集并过滤可压制关系。

- [ ] 在 `SmallHandOracleTest` 用 `SmallHandOracle.classify` 参数化 T1 的选牌预期，覆盖每种牌型及 0/1/2 百搭的声明；测试完整语义集合、不同解释和等价副本规范化，不把一组选牌的预期直接当成全部子集的预期。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=SmallHandOracleTest test`，记录参照未实现的红灯。
- [ ] 实现 `SmallHandOracle.java`：n≤10 时穷举实体子集，独立执行百搭赋值、自然牌模式判断和可压制过滤，再按消耗牌面、牌型、长度和主比较点数规范化。允许共用牌目录与领域类型；不调用生产生成、判定或比较函数得到参照预期。对百搭枚举作正确的范围约束，避免无条件展开花色与点数。
- [ ] 重跑同一命令全部 PASS；检查参照依赖，人工 expected 不由生产函数生成。提交 `test: add independent small-hand move oracle`。

**验收:** 独立参照通过人工完整声明集合；依赖检查通过；与生产生成器无关即可运行。本步不宣称证明生产生成器正确。

#### T4b：候选合法性与完整性，12–20 小时

**交付:** 正确且顺序稳定的生产生成器，及其与独立参照的双向对照。

- [ ] 写 `test_generated_moves_are_owned_and_valid`、`test_response_moves_all_beat_previous`、`test_generator_preserves_suit_distinct_consumptions`、`test_identical_copies_have_one_representative`、`test_wildcards_used_at_most_once`。
- [ ] 写 `test_generator_equals_small_hand_oracle`：选择含 0/1/2 百搭、王、同花、重复点数的确定手牌，覆盖领出和跟牌；双向比较规范化语义集合，失败分别列出遗漏与多余候选。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=LegalMoveGeneratorTest,GeneratorOracleTest test`，确认红灯。
- [ ] 实现点数/花色索引和牌型模板；保留花色与百搭消耗差异；相同牌面副本选确定代表；保证候选及声明顺序稳定。穷举实体子集只留在 tests/。
- [ ] 重跑要求 PASS；发生分歧先回查人工用例，不能自动改 expected。提交 `feat: generate complete legal move candidates`。

**验收:** 所有候选合法，人工及小手牌双向对照无遗漏/多余，等价副本处理正确，生产路径不枚举所有手牌子集。

#### T4c：固定复杂手牌基准与优化，7–12 小时

**交付:** 可重复的复杂手牌输入、生成耗时基线和必要的优化；正确性不得退化。

- [ ] 建立 benchmark_hands.json，保存以下输入展开后的精确 CardId 与稳定 case_id；`@0/@1` 的含义沿用 T1，不在每次测量时随机换牌：

| case_id | 固定 27 张手牌 | 重点 |
|---|---|---|
| B01 | 3–8 的四种花色各取 @0，共 24 张；再加 S9@0、H2@0、H2@1 | 不同花色顺子消耗、两百搭、多声明 |
| B02 | S3–SA 各取 @0/@1，共 24 张；再加 S2@0、H2@0、H2@1 | 同花密集、重复副本、同花顺 |
| B03 | 7、8、9 的四种花色各取 @0/@1，共 24 张；再加 S3@0、H2@0、H2@1 | 大炸弹、钢板、两百搭 |
| B04 | 3、5、7、9、J、K 的四种花色各取 @0，共 24 张；再加 JOKER_SMALL@0、JOKER_BIG@0、S2@0 | 三带对、重复点数、无百搭对照 |

- [ ] 写 `test_benchmark_hands_are_valid`：每例恰为 27 个合法且不同的 CardId；每例分别测试领出和跟 `C10@0 C10@1` 的对子，桌面牌与手牌不重叠。先记录 fixture 校验红灯，再完成数据使其通过。
- [ ] 在 `GenerationBenchmarkTest` 建立由 Maven `performance` profile 启用的基准：每个输入/场景预热 10 次、测量 100 次，记录候选数量、p50/p95/max、CPU、JDK/JVM 参数、GC 与缓存条件；调用实际生产生成器，不把结果缓存命中当作首次计算性能。输出 `artifacts/t4-generation-baseline.json`。
- [ ] 运行 `.\mvnw.cmd -q -Pperformance -Dtest=GenerationBenchmarkTest test`；按本文性能目标记录达标情况。必要时优化索引、重复声明消除和局部计算；记录优化前后同一输入的结果，不通过少返回合法动作获得加速。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=SmallHandOracleTest,LegalMoveGeneratorTest,GeneratorOracleTest test`，确认正确性未退化；再次运行相同基准，提交 `perf: benchmark and optimize move generation`。

**验收:** 固定输入有效，正确性检查全部 PASS，基准含全部输入和场景的真实数据、达标或差距及优化记录。T4c 完成基线不等于性能已经达标；剩余差距须明确进入 T10，不能把未达标指标写成通过。M2 继续测完整单局耗时，T10 承担完整性能验收。

### T5：纯牌局状态机、接风与结果，12–20 小时

**Files:** `backend/src/main/java/com/guandan/engine/{GameEngine,PlayerViewFactory}.java`、`backend/src/test/java/com/guandan/engine/{GameEngineTest,PlayerViewFactoryTest}.java`。

**Interfaces:** 消费 T2–T4；输出 `newGame`、`resolveSelection`、`getLegalActions`、`applyAction`、`getPlayerView`；签名采用本文领域接口。

- [ ] 写 `test_lead_cannot_pass`、`test_invalid_action_does_not_mutate_input`、`test_turn_skips_finished_player`、`test_passed_player_can_respond_to_new_play`。
- [ ] 用短手牌 fixture 写 `test_last_play_unbeaten_gives_partner_lead`、`test_last_play_beaten_does_not_give_partner_lead`、`test_responder_set_uses_active_players`。
- [ ] 写 `test_double_up_has_shared_last_group`、`test_third_finish_assigns_remaining_last`、`test_terminal_remaining_cards_still_conserved`、`test_view_hides_opponent_cards_and_seed`；守恒断言只合计四家手牌与 discarded，并验证当前 trick 牌均在 discarded 中。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=GameEngineTest,PlayerViewFactoryTest test`，确认红灯。
- [ ] 实现单动作纯转移；先验证 ID、归属、回合和声明，再扣牌；用待响应集合处理一轮；结束时保留未出的牌。
- [ ] 同一命令要求 PASS；有效动作 version+1；无效动作 version、hands、discarded、trick 都保持不变。
- [ ] 提交 `feat: advance guandan turns and settle a deal`。

### T6：合法随机机器人，4–7 小时

**Files:** `backend/src/main/java/com/guandan/bots/RandomLegalPolicy.java`、`backend/src/test/java/com/guandan/bots/RandomLegalPolicyTest.java`、`backend/src/test/java/com/guandan/engine/M2SmokeTest.java`。

**Interfaces:** 消费 PlayerView 与 `getLegalActions` 输出；实现 `RandomLegalPolicy.chooseAction(view, legalActions, rng)`。

- [ ] 写 `test_bot_action_is_in_supplied_legal_actions`、`test_bot_cannot_pass_on_lead`、`test_bot_passes_when_no_response_exists`、`test_same_rng_seed_reproduces_choices`。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=RandomLegalPolicyTest test`，确认红灯。
- [ ] 均匀选择合法动作；policy 不导入 service/store/engine，也不接收其他人的手牌；没有 LLM 或网络依赖。
- [ ] 同一命令要求 PASS；通过函数参数与依赖检查确认其可见信息边界。
- [ ] 在 `M2SmokeTest` 写 `seededGamesAndTimings`：四个合法随机机器人以 seed=10000 预热一局，再完整运行 seed=0–19 的 20 局；沿用服务合同的稳定策略种子派生方式，每步按“四家手牌 + discarded = 108、trick ⊆ discarded”检查守恒，并检查回合和动作合法性；每局最多 512 个动作。
- [ ] 运行 `.\mvnw.cmd -q -Pslow -Dtest=M2SmokeTest test`，输出 `artifacts/m2-smoke.json`：逐局 seed、结果、动作数、候选生成累计耗时、纯引擎加策略的整局计算耗时，以及样本 p50/p95/max；另列含不变量检查和日志的总壁钟时间。该初测不包含 HTTP/动画，不当作 T10 的 HTTP 批次验收；用实际均值估算 10,000 局计算时间，并标注仅为小样本推算。
- [ ] 提交 `feat: add random legal bot policy`。

### T7：HTTP 服务、会话、幂等与事务，10–18 小时

**Files:** `backend/src/main/java/com/guandan/service/{InMemoryGameStore,GameService}.java`、`backend/src/main/java/com/guandan/api/{GameController,ApiExceptionHandler}.java`、`backend/src/main/java/com/guandan/api/dto/*.java`、`backend/src/main/java/com/guandan/GuandanApplication.java`、`backend/src/test/java/com/guandan/service/GameServiceTest.java`、`backend/src/test/java/com/guandan/api/GameControllerTest.java`、`docs/api-v1.md`。

**Interfaces:** 消费 T5–T6；实现本文 GameService 与 HTTP 合同；所有传输 DTO 在 `api/dto/` 定义，OPENAPI或api-v1.md与实际DTO是前端字段依据。

- [ ] 用注入的固定 seed、内存存储和 policy 写创建、解析、出牌、过牌、刷新快照、结束后拒绝动作的测试。
- [ ] 写 `test_resolve_accepts_noncanonical_identical_copy`：生成器选 S3@0，但真人选择手里的 S3@1 仍可解析并扣除正确实体。
- [ ] 写 `test_repeat_request_does_not_apply_twice`、`test_same_request_id_different_body_is_rejected`、`test_two_commands_same_version_only_one_commits`：后者通过并发提交两条合法命令断言一个成功、一个冲突。
- [ ] 写机器人异常整批回滚、步数保护回滚测试：前者注入抛异常的 policy；后者用测试替身替换服务依赖的动作推进接口，使其不推进回合，以触发 512 步保护；断言前后 state、事件序号和幂等账本无部分提交。故障替换不作为生产功能。
- [ ] 写无令牌、跨游戏令牌、schema 错误、隐藏信息、服务重建后 404 的测试；递归检查 JSON 字段，不能只有“页面没显示”的断言。
- [ ] 写 `test_restart_clears_all_game_records` 与 `test_new_game_keeps_old_request_receipt`：重建 store 后旧局/令牌/事件/幂等响应均不存在；同进程新建另一局不破坏旧局已成功命令的重试确认。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=GameServiceTest,GameControllerTest test`，确认红灯。
- [ ] 实现每局锁、工作状态复制、成功后提交；幂等检查在 version 检查之前；同步 HTTP 路由调用服务。
- [ ] 同一命令全部 PASS；更新 api-v1.md，包括实际 JSON 示例与错误码。
- [ ] 提交 `feat: expose authoritative game service over HTTP`。

### T8：牌桌、实体选牌与可见状态，6–10 小时

**Files:** `frontend/package.json`、`frontend/package-lock.json`、`frontend/vite.config.ts`、`frontend/src/{App.vue,main.ts}`、`frontend/src/api/game.ts`、`frontend/src/state/game.ts`、`frontend/src/components/{Card,Hand,Table,ActionBar,ResultPanel}.vue`、`frontend/src/__tests__/selection.test.ts`。

**Interfaces:** 消费 T7 的 OPENAPI或api-v1.md与实际DTO；输出 `createGame()`、`getGame(gameId, token)`、可见 GameView 类型；状态模块提供 `toggleSelected(cardId)`、`applySnapshot(gameId, view)`、`resetSelection()`。

- [ ] 建立 Vue TS 基础配置、Vitest 运行器和 npm 脚本；此时组件和状态模块只有测试所需入口，不实现业务。提交解析后的 package-lock.json。
- [ ] 写 `test_duplicate_faces_select_independently`：两张黑桃 A 的不同 ID 分别选择，不以 rank/suit 作 key。
- [ ] 写 `test_stale_snapshot_does_not_replace_newer_one`、`test_old_game_snapshot_does_not_replace_new_game`；用版本 8→7、新旧游戏 ID 明确断言。
- [ ] 运行 `npm run test:unit -- --run`，确认相关业务断言或未实现模块造成红灯。
- [ ] 实现四座位、手牌、桌面公开牌、剩余张数与结果；默认视口以桌面横向使用为主，27 张手牌重叠布局需可选择每张实体牌。
- [ ] 页面主操作文案固定为“开始一局”和“再来一局”，状态栏显示“单局 · 两副牌 108 张 · 固定打 2”；结果与重启提示统一用“本局”。
- [ ] 配置开发代理 /api 到 8000；API 请求通过一个客户端模块；保存 game_id/token，刷新 GET 恢复。
- [ ] 运行 `npm run test:unit -- --run`、`npm run typecheck`、`npm run build`，均成功。
- [ ] 提交 `feat: render guandan table and card selection`。

### T9：声明选择、提示、重试与事件播放，6–12 小时

**Files:** `frontend/src/api/game.ts`、`frontend/src/state/game.ts`、`frontend/src/components/{ActionBar,DeclarationPicker}.vue`、`frontend/src/__tests__/command.test.ts`。

**Interfaces:** 增加 `resolveSelection(gameId, token, expectedVersion, cardIds)`、`getLegalActions(gameId, token)`、`submitCommand(gameId, token, command)`；浏览器持久化类型 `PendingCommand={game_id, command:CommandRequest}`；状态模块输出 `playSelection()`、`passTurn()`、`retryPendingCommand()`、`cycleHint()`；方法通过注入 API 客户端测试。

- [ ] 写 0/1/多声明的流程断言：0 不提交，1 提交该键，多声明只有选定后提交。
- [ ] 写 `test_timeout_retry_reuses_exact_command`、`test_double_click_only_sends_one_command`、`test_refresh_recovers_pending_command`、`test_delayed_response_does_not_regress_version`。
- [ ] 写 `test_pending_command_is_bound_to_game`、`test_old_ack_cannot_clear_new_pending`、`test_successful_new_game_clears_old_pending`、`test_failed_new_game_keeps_pending`：跨局 pending 不提交；匹配确认才清除；新建成功清理旧上下文，新建失败仍可恢复旧局。
- [ ] 写 `test_recovery_snapshot_suppresses_cached_events`、`test_duplicate_response_does_not_replay_events`、`test_same_version_events_all_play_once`：GET 已到 version=8 后收到最终 version=8 的缓存响应只确认请求；同批按 seq 去重，多个事件共享 state_version 时仍完整播放一次。
- [ ] 写 `test_new_snapshot_cancels_animation`、`test_missing_animation_base_applies_snapshot`、`test_cancelled_callback_cannot_mutate_view`、`test_animation_blocks_new_command`：版本 8 的动画中到达版本 12 快照立即切换；没有匹配起点直接用快照；旧回调不得回写；动画期间不能发新命令。
- [ ] 写提示只选择后端给出的实体 ID、领出禁用过牌、非法动作保留可修正选牌、断网保留视图的断言。
- [ ] 运行 `npm run test:unit -- --run`，确认红灯。
- [ ] 按前端合同实现全流程防重、按局持久化 pending、声明选择与提示，以及权威快照/动画展示状态分离；重复或已被恢复快照覆盖的事件不重播，取消的回调无权更新展示，支持动画关闭。
- [ ] 同一单测、typecheck、build 全部成功。
- [ ] 提交 `feat: submit moves and recover game commands`。

**执行状态（2026-10-18）：T9 已通过阶段门。** 0/1/多声明、提示、出牌/过牌、pending 原样重试与跨局隔离、版本保护、事件去重和播放均有前端单测；后续复审补齐了重同步请求防竞态、断网恢复后无展示起点直接采用最终快照，以及较新快照落地后清除重同步锁的回归覆盖。复验 `npm run test:unit -- --run` 为 78 项通过，`npm run typecheck` 和 `npm run build` 均通过。真实浏览器整局与性能完整验收仍归 T10–T11；上方任务清单保留原始执行步骤，不以当前勾选状态表示验收结果。

### T10：属性测试、自动对局与复现，7–13 小时

**Files:** `backend/src/main/java/com/guandan/cli/GuandanCli.java`、`backend/src/test/java/com/guandan/engine/{GamePropertiesTest,SimulationTest,ReplayTest}.java`；扩展 T4 已建立的 `GenerationBenchmarkTest` 与固定 `benchmark_hands.json`，不另起一套基准输入。

**Interfaces:** 消费纯引擎和 policy；`GuandanApplication` 在启动 HTTP 前分派 `simulate/replay/benchmark` 命令，CLI 不启动 Web 服务；复现记录仅保存在测试或开发环境，不通过玩家 API 下载隐藏种子。

- [ ] 写属性：牌 ID 守恒、牌没有重叠归属、丢弃牌单调增加、非终态当前玩家仍持牌、生成器不输出非法动作、有效动作 version 单调；每个属性失败输出 seed/步骤/最小手牌。
- [ ] 写 `test_100_seeded_deals_terminate` 和 `test_replay_reproduces_result_and_public_events`；四个测试机器人使用同一观察边界，测试运行器可读取完整状态检查不变量。
- [ ] 运行 `.\mvnw.cmd -q -Dtest=GamePropertiesTest,SimulationTest,ReplayTest test`，确认 CLI/复现功能未实现的红灯。
- [ ] 实现 CLI：simulate 记录 seed、初始牌序、规则 ID、策略种子派生版本、动作声明、最终结果与逐局耗时；replay 使用记录重放；benchmark 复用 T4 固定输入和测量协议，记录环境、候选数、时间分位数及相对 T4/M2 基线的变化。QuickTheories 的随机性质测试保留可复现 seed，失败案例导出 JSON fixture。
- [ ] 运行同一测试要求 PASS；再运行 1,000 局快速批次与 10,000 局完整批次，发现失败先固定为回归 fixture，再修复。
- [ ] 扩展性能测试，覆盖 T4 的全部输入/场景、legal-actions 的响应字节数及序列化开销、正常 HTTP 动作批次、真人出完后的剩余机器人批次；后两项通过固定 seed/短手牌 fixture 产生有效请求，每种批次至少测量 100 次，不把幂等缓存返回计作新动作执行。
- [ ] 完整验收按本文 G 节的放宽目标逐项报告；HTTP 时间包括服务计算和响应序列化、不含动画，长批次独立汇总，记录机器运行时间与有效开发人时，不把小样本推算写成 10,000 局实测结果。
- [ ] 提交 `test: simulate and replay complete guandan deals`。

### T11：浏览器验收与交付，4–8 小时

**Files:** `frontend/playwright.config.ts`、`frontend/e2e/{game,recovery}.spec.ts`、`README.md`、`docs/test-report-v1.md`。

**Interfaces:** 消费真实 HTTP 服务与页面；测试模式通过测试进程内部注入固定 fixture，生产不新增万能调牌接口。

- [ ] 写真实后端 E2E：点击“开始一局”→确认“单局 · 两副牌 108 张 · 固定打 2”及 27 张手牌→提示→出牌→机器人事件→再次轮到真人；另用短手牌 fixture 验证真人先出完后机器人打完与结算，点击“再来一局”得到新 game_id。
- [ ] 写双击、声明选择、刷新恢复、响应丢失后原样重试、服务重启失效等流程；数据断言来自后端状态，不靠固定延时猜测。
- [ ] 补充真实浏览器场景：响应丢失→刷新 GET 已覆盖动作→原命令重试只确认且不重播；存在 pending 时成功新建另一局→旧请求/回调不提交或污染新局。默认关闭动画的测试之外，增加受控事件播放测试，用可观察状态/完成信号验证播放中禁用及新快照取消动画，不用固定 sleep。
- [ ] 运行 `npx playwright test`，确认未完成场景红灯；完成必要的页面连接与错误展示。
- [ ] 再运行 Playwright、全部后端测试、前端单测/typecheck/build；保存命令、环境、结果与已知限制。
- [ ] README 写明 JDK 17/Node 环境、Maven Wrapper 安装与前后端两进程启动方法、单 JVM 限制、关闭动画方式、如何复现失败；说明 v1 仅在本地进程生命周期内保留牌局及幂等记录，重启统一清空，暂不自动回收旧局。
- [ ] 提交 `docs: deliver verified guandan v1 MVP`；只在实际验证通过后标记 v1 完成。

**执行状态（2026-10-20）：T11 浏览器与交付验收通过，本地单局 v1 MVP 完成。** 真实 Spring HTTP + Edge 浏览器的 10 项 Playwright 用例覆盖上方主线、短手牌结算、双击、声明、刷新/响应丢失重试、后端进程重启、跨局隔离、事件播放和竖排手牌鼠标选择；标准、slow、performance 后端测试与前端单测、typecheck、build 均通过。测试专用固定局未进入生产 JAR。证据和边界见 `docs/test-report-v1.md`；上方清单保留原始执行步骤，不以勾选状态表示结果。

## 测试计划与预期矩阵

### A. 人工规则用例

下列为最低要求，不以总用例数量代替覆盖。`S/H/D/C` 分别为黑桃/红桃/方块/梅花，`S3@0` 指第一副黑桃 3；`JOKER_SMALL@0`、`JOKER_BIG@0` 指第一副小王/大王，避免与黑桃 J 的 `SJ@0` 混淆。每张实体都要写 `@0/@1`，不能重复引用同一个 CardId。T1 必须将 R01–R24 全部扩成可机读的完整预期，包含所选牌的全部有效声明或明确空集、比较结果及错误码；P01–P07 进一步固定容易遗漏的多声明集合。

完整语义声明集合按 `(kind, length, main_rank)` 比较；同一语义内的百搭展示绑定只保留按 CardId 和自然点数排序后的规范代表，`wild_bindings` 仍须单独核对。下表中的 `S/2` 表示百搭绑定为黑桃 2，`→2` 表示 rank=2 且 suit=null，`—` 表示无百搭绑定；仅同花顺需要替代花色，普通顺子等牌型的 suit 均为 null。`STRAIGHT_FLUSH` 与 `STRAIGHT` 是不同声明。除下表所列，不允许额外声明。

| ID | 精确选牌 | 完整预期声明集合（牌型/张数/主比较点数；展示绑定） |
|---|---|---|
| P01 | `S3@0 H3@0 S4@0 H2@0 H2@1` | `{FULL_HOUSE/5/3；H2@0→3、H2@1→4，FULL_HOUSE/5/4；H2@0→4、H2@1→4}`；首项带牌对子为 4，次项带牌对子为 3 |
| P02 | `S3@0 S4@0 S5@0 H2@0 H2@1` | `{STRAIGHT/5/5, STRAIGHT/5/6, STRAIGHT/5/7, STRAIGHT_FLUSH/5/5, STRAIGHT_FLUSH/5/6, STRAIGHT_FLUSH/5/7}`；端点 5 的绑定点数为 `H2@0→2, H2@1→A`，端点 6 为 `2,6`，端点 7 为 `6,7`；三个同花顺声明的 suit 均为 S，三个普通顺子声明的 suit 均为 null |
| P03 | `S3@0 H3@0 D3@0 H2@0 H2@1` | `{FULL_HOUSE/5/3；H2@0→2、H2@1→2，BOMB/5/3；H2@0→3、H2@1→3}`；三带对的带牌点数只留最小有效自然点数 2 作为展示绑定 |
| P04 | `S3@0 H3@0 D3@0 S4@0 H2@0 H2@1` | `{TRIPLE_RUN/6/4；H2@0→4、H2@1→4}` |
| P05 | `H2@0 H2@1` | `{PAIR/2/2；—}`；两张单独成对时不能声明其他对子 |
| P06（R15） | `S3@0 S4@0 S5@0 S6@0 H2@0` | `{STRAIGHT/5/6；H2@0→2，STRAIGHT/5/7；H2@0→7，STRAIGHT_FLUSH/5/6；H2@0→S/2，STRAIGHT_FLUSH/5/7；H2@0→S/7}` |
| P07 | `H2@0 H2@1 JOKER_SMALL@0` | `{}`；百搭不能补成三张王，也不能形成普通三同张 |

P01–P07 的集合均按实体 ID 一次性选出全部所列牌，不是从该手牌枚举任意子集。普通顺子的花色不影响比较；上表的同花顺需要黑桃绑定，其他牌型不凭空补花色。其他用例若存在多种绑定但只有同一语义声明，也须在 fixture 中写出规范代表及合并依据。

| ID | 用例 | 预期 |
|---|---|---|
| R01 | 两个不同实体的同花同点牌 | 可形成对子，分别选牌/扣牌 |
| R02 | S2 与 SA 单张 | S2 能压 SA |
| R03 | H2 单张 | 仅是级牌单张，不可声明为王 |
| R04 | 两张 H2 单独出 | 级牌对子 |
| R05 | 两小王、两大王、大小王混合 | 前两组合法对子，混合非法 |
| R06 | 四种不同花色/点数不连的普通牌 | 不会被误识别为炸弹 |
| R07 | A2345、10JQKA | 合法五张顺子，高端点分别为 5、A |
| R08 | KA234、四张/六张连续单牌 | 不是五张顺子 |
| R09 | AA2233、QQKKAA、KKAA22 | 前两组合法三连对，后一组非法 |
| R10 | AAA222、KKKAAA | 合法钢板；低 A 端点为 2 |
| R11 | 一张王混入顺子/连对/钢板 | 非法，百搭不能掩盖真实王 |
| R12 | 33344 与 33333 | 前者是三带对，后者为五张炸弹，不是三带对 |
| R13 | 333 + 两小王 | 合法三带对，比较三张的 3 |
| R14 | 333 + 小王 + H2 | 百搭不能补小王对子，因此不是该三带对 |
| R15 | S3@0 S4@0 S5@0 S6@0 H2@0 | 精确完整集合见 P06：两个顺子及两个同花顺声明 |
| R16 | 8 张真实 7 加两张 H2 | 可声明十张 7 炸弹；卡牌 ID 都不同 |
| R17 | 天王炸弹缺一张，用 H2 补 | 不能形成天王炸弹 |
| R18 | 4 张、5 张、6 张炸弹与同花顺 | 严格满足规则等级顺序 |
| R19 | 同张数炸弹中的级牌与 A | 级牌炸弹更大 |
| R20 | 同大小同花顺但不同花色 | 谁也不能压谁 |
| R21 | 单张与对子、三张与三带对 | 普通不同牌型不能互压 |
| R22 | 同组三带对改变带牌点数 | 主三张相同则不能靠带牌变大 |
| R23 | 相同 card_id 重复两次 | DUPLICATE_CARD_ID，不会当成对子 |
| R24 | 生成器选择相同牌面的副本 A，真人选择副本 B | 两者均能独立解析；提交只扣实际选择的 B |

### B. 状态与结算

| ID | 场景 | 预期 |
|---|---|---|
| S01 | 领出时 PASS | 拒绝，状态和 version 不变 |
| S02 | 玩家不是 current_player | 拒绝，不扣牌 |
| S03 | 使用别人的卡牌 | 拒绝，不扣牌 |
| S04 | 0 领出，1/2/3 都过 | 清空有效桌面牌，0 重新领出 |
| S05 | 0 最后一手出完，1/2/3 都过 | 仍持牌的搭档 2 接风 |
| S06 | 0 最后一手出完，1 压住，2/3 都过 | 1 领出，不给 2 自动接风 |
| S07 | 某人已经出完 | 轮转跳过；待响应人数按实际活跃玩家处理 |
| S08 | 1 曾过牌，2 又出牌 | 1 在后续轮转中仍可响应新牌 |
| S09 | 实际出完顺序 [0,2] | 立即结束，结果 ((0,),(2,),(1,3)) |
| S10 | 实际出完顺序 [0,1,2] | 结束，3 自动末游，手牌仍保留 |
| S11 | 实际出完顺序 [0,1,3] | 结束，2 自动末游，头游队为赢家 |
| S12 | 真人首先出完 | 后端机器人继续至结算，不等待真人继续操作 |
| S13 | 非法动作、多声明未选、重复实体 | state 与 version 均不变 |
| S14 | 任意合法步骤/终态 | 四人手牌与 discarded 合起来恰为 108 个不同 ID；当前 trick 牌已计入 discarded |

### C. 接口与浏览器

| ID | 场景 | 预期 |
|---|---|---|
| A01 | 创建牌局 | 201，真人 27 张，其他三人只有张数，固定打 2 |
| A02 | resolve 或 legal-actions | 不推进 state/version；只提供真人自己的动作 |
| A03 | 同 request_id 同请求重试 | 返回原响应，不再次执行任何动作 |
| A04 | 同 request_id 不同请求 | 409 REQUEST_ID_REUSED |
| A05 | 不同请求基于同 version 并发 | 只有一个提交；另一个 409 VERSION_CONFLICT |
| A06 | 机器人中途异常 | 整批回滚；不能部分扣牌或部分写事件 |
| A07 | 非法 token、跨局 token | 401/403；不暴露牌局内容 |
| A08 | malformed JSON、越界 ID、PASS 带牌、布尔值 ID | 都返回 422 SCHEMA_INVALID，错误格式一致；合法范围内的重复 ID 另返回 400 DUPLICATE_CARD_ID |
| A09 | 查看全部响应/错误 JSON | 无对手手牌、洗牌 seed、RNG seed 或 traceback |
| A10 | 网络超时后重试 | 原 ID/原请求体；页面最终状态与后端一致 |
| A11 | 收到旧版本/旧游戏响应 | 不覆盖新状态或新牌局 |
| A12 | 页面刷新 | GET 恢复；pending 命令可安全确认 |
| A13 | 服务重启 | 404，提示本局已失效，允许开始新一局 |
| A14 | 连续双击出牌 | 前端一次在途；后端仍有独立幂等保护 |
| A15 | 结算后再出牌 | 409 GAME_FINISHED；点击“再来一局”得到新 game_id |
| A16 | 响应丢失后刷新 GET，随后重试收到已被快照覆盖的事件 | 只确认 pending，不重播，不倒退牌数 |
| A17 | 同一批响应重复到达、同 version 存在多个事件 | 同局 seq 去重；同批多个事件均按序处理一次 |
| A18 | 播放中到达较新快照，或旧动画回调晚到 | 取消旧播放并采用新快照；旧回调不得回写 |
| A19 | 旧局 pending 遇到新局；旧请求确认晚于新 pending | 不跨局提交，不清除新 pending；新建失败保留旧局 pending |
| A20 | 动画/恢复/结果不明期间再次出牌 | 不创建另一条命令；确认并同步完成后才恢复操作 |
| A21 | 同进程新建另一局与进程重建 | 新局不破坏旧局幂等确认；重启后所有旧局记录统一消失 |

### D. 生成完整性与属性测试

1. **人工预期**：判定器首先通过人工确认的正反例，避免让 AI 自动抄自己的输出。
2. **生成合法性**：每个候选消耗自己的牌、无重复 ID、声明合法，跟牌时确实能压住当前牌。
3. **生成完整性**：n≤10 的小手牌，生产生成器与独立慢速参照做双向语义集合对照；等价副本归一化后比较，不能只比数量。
4. **属性测试**：QuickTheories 生成合法的卡牌子集和牌局前缀；随机性受控，失败输出可保存为 JSON fixture。
5. **完整对局**：四个合法随机机器人从 108 张牌开始模拟；每一步检查不变量；每局至多 512 个动作作为异常保护。
6. **固定 seed 不等于洗牌公平性证明**：v1 用标准洗牌算法保证可复现，不额外增加统计分布测试作为交付门槛。
7. **随机模拟不等于全规则证明**：不能用“10,000 局没报错”替代人工例子和小手牌对照。

### E. 测试分层与运行频率

| 层级 | 工具 | 何时运行 | 通过门槛 |
|---|---|---|---|
| 规则/引擎单测 | JUnit Jupiter | 每次相关修改 | 人工矩阵与回归全部通过 |
| 属性与小手牌参照 | QuickTheories + JUnit Jupiter | T4/T10、规则修改后 | 无漏动作、无非法候选、无不变量破坏 |
| 固定复杂手牌基准 | JUnit performance profile | T4c 建立、T10 复测 | 固定输入有效；报告真实候选数和时间；T10 按 G 节最终验收 |
| M2 少量整局初测 | JUnit slow profile | T6 完成时 | 20 局合法结算，记录逐局耗时与后续规模推算 |
| 服务/API | JUnit + MockMvc + 真实端口集成测试 | T7 与服务修改后 | 幂等、并发、回滚、可见性通过 |
| 前端状态与请求 | Vitest | T8/T9 与交互修改后 | ID 选牌、声明、重试与版本保护通过 |
| 全局模拟 | Java CLI | T10、影响规则/机器人后 | 1,000 快速局、10,000 验收局无错误 |
| 端到端 | Playwright + 真实服务 | 集成后、交付前 | 开始、出牌、恢复、结算流程通过 |
| 人工试玩 | 浏览器 | T11 | 两份相同牌能分选，提示可执行，动画和错误信息可理解 |

### F. 实际执行命令

后端开发检查（PowerShell，从 `backend/` 运行；Maven profile 由 T2 定义，普通 `test` 排除 `slow` 和 `performance`）：

```powershell
.\mvnw.cmd -q test
.\mvnw.cmd -q -Dtest=SmallHandOracleTest,LegalMoveGeneratorTest,GeneratorOracleTest test
.\mvnw.cmd -q -Pperformance -Dtest=GenerationBenchmarkTest test
.\mvnw.cmd -q -Pslow -Dtest=M2SmokeTest test
.\mvnw.cmd -q -Pslow -Dtest=GeneratorOracleTest,GamePropertiesTest test
.\mvnw.cmd -q -DskipTests package
java -jar target/guandan-v1.jar simulate --start-seed 0 --games 1000 --max-actions 512 --output artifacts/sim-1000.json
java -jar target/guandan-v1.jar simulate --start-seed 0 --games 10000 --max-actions 512 --output artifacts/sim-10000.json
java -jar target/guandan-v1.jar replay --input artifacts/failing-deal.json
java -jar target/guandan-v1.jar benchmark --output artifacts/benchmark.json
```

`simulate` 失败必须非零退出，输出失败局可重放的记录；replay 仅针对真实失败记录，不要求凭空生成 failing-deal.json。`slow` profile 运行大批模拟/参照，`performance` profile 运行性能测试；开发检查不随机器负载随机红灯。T2 须验证单类命令与 profile 在实际 Surefire 配置下均能运行，不能只在文档里约定。

前端检查：

```bash
npm run test:unit -- --run
npm run typecheck
npm run build
npx playwright test
```

端到端配置负责启动测试后端与前端，使用真实路由；默认关闭动画，专门的事件播放用例受控启用动画并等待可观察完成信号；fixture 注入只存在测试应用构造器中。

### G. 性能目标与验收条件

性能目标先记录环境再判断：CPU 型号、系统、JDK/JVM 参数、Node 版本、输入手牌、候选数量、测量轮数、预热、GC 与缓存条件；不要把目标写成已测结果。以下统一采用本地单局、无额外并发负载条件；指标是验收目标，不代表已完成测试。

- T4c 对 B01–B04 每例的领出/跟牌场景分别预热 10 次、测量 100 次；候选生成目标各场景 p95≤500ms。记录首次计算及优化后数据，不以结果缓存命中替代计算基准；未达标先定位索引、去重和分配开销，不删合法规则。
- M2 在完整 20 局初测中记录逐局计算耗时、动作数、生成耗时占比及 p50/p95/max，提前发现长尾并推算后续验收运行成本；该阶段不增加整局硬性耗时门槛，也不代替服务批次指标。
- 正常“真人动作 + 到下次真人回合”的本地 HTTP 批次目标 p95≤3s，包含计算和序列化，排除前端动画；legal-actions 同时报告候选数、响应字节数、生成及序列化耗时，响应大小暂不设硬阈值，不截断合法动作。
- 真人先出完后到本局结算的长批次单独记录，不混入正常回合指标；T10 测量样本的最长批次目标≤10s，并报告 p50/p95/max，未达标定位候选生成和模拟开销。
- 前端请求超时设为 20s，超时采用绑定原 game_id 的原命令重试；客户端超时不代表服务端取消，不自动另发新命令，后端不通过 sleep 制造机器人思考时间。
- 10,000 副自动对局无非法动作、牌数异常或死循环；种子 0–9999 及随机策略派生规则记录在报告。
- 全部人工预期、服务/API 和浏览器流程通过。
- T10 完成上述性能项目的正式测量及达标判断；T4/M2 仅有基线或推算时不能标记性能验收通过，未达标项目列为未完成。
- 提交报告必须列出真实结果与失败修复；若还有遗漏规则或已知不正确行为，不能标为 v1 完成。

## 工期、里程碑与执行方式

| 任务 | 估计有效开发小时 | 依赖 |
|---|---:|---|
| T1 规则与人工样例 | 4–7 | 无 |
| T2 实体牌与类型 | 5–9 | T1 |
| T3 判定与比较 | 12–20 | T2 |
| T4a 独立慢速参照 | 7–12 | T1、T3 |
| T4b 候选合法性与完整性 | 12–20 | T4a |
| T4c 固定基准与优化 | 7–12 | T4b |
| T5 回合状态机 | 12–20 | T3、T4 |
| T6 合法随机机器人 | 4–7 | T5 |
| T7 服务与 HTTP | 10–18 | T5、T6 |
| T8 牌桌界面 | 6–10 | T7 |
| T9 动作与恢复交互 | 6–12 | T8 |
| T10 模拟与复现 | 7–13 | T4、T5、T6；T7 已集成 |
| T11 浏览器与交付 | 4–8 | 全部 |
| **合计** | **96–168** | 含每个任务自身的测试，T4 合计 26–44 小时 |

上述是 JDK 17 + Spring Boot 方案的规划基线。考虑独立参照、异常恢复和首次集成的调试波动，首次开发预算预留约 30% 缓冲，取整建议约 125–220 个有效小时；每天投入 4 小时约 31–55 个开发日。不是要求连续满负荷投入，日期应按实际精力安排。机器跑模拟的等待时间单独记录；首版机器人减少了策略设计，但规则生成与验证仍是主要投入。M2 实测后重新估算剩余工作。

里程碑：

- **M1 / T1–T3**：实体牌和判定器可运行，人工牌型与比较用例通过。
- **M2 / T4a–T6**：T4 三步分别验收，无界面四机器人对局能结算，小手牌动作生成对照通过；固定复杂手牌基线和 20 副完整对局耗时报告已产生，未达性能目标的项目有明确记录。
- **M3 / T7**：后端通过接口操作一局，重复请求与异常回滚正确。
- **M4 / T8–T9**：真人可以在页面出牌、提示、刷新恢复与查看结果。
- **M5 / T10–T11**：自动对局与真实浏览器验收通过，交付运行和测试报告。

T4c 固定复杂手牌基准、M2 的 20 局耗时初测必须提前执行；1,000/10,000 局、完整日志、真实 HTTP 批次性能及最终性能验收仍归 T10，不在每个前期任务反复运行。

执行建议：按任务顺序由同一个执行者推进，每次只提交一个可验证任务。接口依赖较多，规则未稳定前不要并行修改判定器、生成器与状态机。每个任务结束提交代码、运行命令、测试结果与剩余问题；发现规则歧义先修改合同与 fixture，再修改实现。

可直接给执行 Codex 的启动指令：

> 阅读本计划全文。使用 JDK 17 + Spring Boot 3.5.16 后端，按“单局 · 两副牌 108 张 · 固定打 2”和合法随机机器人范围实现。使用 executing-plans 按 T1–T11 逐项执行，先做纯规则与测试，再接接口和页面。不要增加大模型、升级、贡还、多人房间或数据库。每个任务先落实人工预期和相关测试，报告实际命令结果并提交；任何规则调整同步修改规则合同与测试。完成后提供启动方式、完整模拟和端到端验证报告。

## v2 扩展边界

- 跨局升级与贡还：新增跨局 MatchState 和独立贡还阶段；复用单局判定、动作生成、机器人接口。
- 更好机器人：替换 RandomLegalPolicy，输入仍为 PlayerView + 合法动作；可逐步加入启发式、有限搜索或学习模型。
- 多人联机：增加房间/身份/座位控制与 WebSocket 推送；玩家命令仍调用服务与引擎入口；应改造当前“自动推进到真人”适配流程。
- 持久化/多进程：替换内存 store，并设计单局状态所有权、事务/恢复；不能直接增加 worker 数量。
- 生命周期管理：当前重启统一清空；未来在 store 边界新增整局过期/删除与清理策略，明确会话、事件和幂等响应一起失效的规则及重试有效期，避免局部回收破坏一致性。
- 离线模式：单独设计规则运行位置与保存方式，不让当前前端轻校验承担完整牌局。

## JDK 17 实施说明

后端固定使用 JDK 17、Spring Boot 3.5.16、Spring MVC、Jackson 与 Bean Validation；规则引擎保持纯 Java。Java 的运行效率不消除候选组合复杂度，T4 固定输入、M2 整局初测和 T10 正式性能验收仍须实际执行。Maven 依赖与插件版本在 T2 锁定，DTO 严格校验、内存事务、可复现随机性分别按上文合同验证。JDK 17 不以虚拟线程为默认方案，单局 CPU 计算按顺序执行。

## 资料与说明

- [合肥工业大学公布的掼蛋基本规则](https://wgyxy.hfut.edu.cn/2022/1213/c6444a287644/page.htm)：作为自然点数、牌型和炸弹体系参考；本文排除其跨局流程，首家及部分边界以本项目合同为准。
- [东南大学比赛规则 PDF](https://xxgk.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf)：作为接风、百搭和实体牌规则参考。
- [Spring Boot 3.5.16 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[QuickTheories 0.26](https://central.sonatype.com/artifact/org.quicktheories/quicktheories)。
- [Vue TypeScript](https://vuejs.org/guide/typescript/overview.html)、[Vite 运行环境](https://vite.dev/guide/)。
- [JUnit Jupiter](https://junit.org/junit5/docs/current/user-guide/)、[Vitest](https://vitest.dev/guide/)、[Playwright](https://playwright.dev/docs/intro)。

本文件已把需求、接口、任务和测试写成可执行计划；产品尚未开发，文中的命令和性能门槛需在实际执行时验证。
