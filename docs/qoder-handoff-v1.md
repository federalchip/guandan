# 掼蛋 v1：Qoder 开发交接与实践步骤

本任务单以 [v1 MVP 计划](../2026-10-06-guandan-v1-mvp.md) 的规则和接口合同为准。每个 v1 游戏均为“单局 · 两副牌 108 张 · 固定打 2”；后端固定 JDK 17 + Spring Boot 3.5.16，前端 Vue 3 + TypeScript。此文档只拆分交付边界，不更改规则。Qoder 开工前必须读主计划的“V1需求与规则合同”“架构与接口合同”“测试计划与预期矩阵”。

## 当前可用基础

- `backend/pom.xml`：Spring MVC、Validation、JUnit/MockMvc、QuickTheories；Maven Wrapper 3.3.4/Maven 3.9.11；`slow`/`performance` 标签配置；JDK 17 编译目标。
- `GuandanApplication`：Spring Boot 启动入口；`application.yml`：端口 8000。
- `HealthController` 和 `HealthControllerTest`：无会话的 `/api/v1/health`，已用真实 JAR 启动核对。
- 还没有牌目录、规则引擎、牌局、机器人、游戏 API、前端或 CLI。不要因为 `mvnw.cmd test` 通过就标记 T1/T2 或 MVP 完成。

## 交付顺序和可分配任务

| 包 | 建议负责人 | 输入与交付 | 验收 |
|---|---|---|---|
| A：T1 人工规则基线 | Qoder 可独立承担 | 以主计划规则合同写 `docs/rules-v1.md`、`backend/src/test/resources/fixtures/rule_cases.json`、`turn_cases.json`；P01–P07 写完整声明集合，R01–R24 扩为可机读预期；明确 joker 符号与 CardId | 人工逐项核对 108 张实体上限、低 A、级牌自然顺序、炸弹等级、双下；fixture 不能由待测判定器生成 |
| B：T2 牌与领域模型 | Qoder 在 A 之后承担 | `domain/model` 中的 record/enum、`Deck`、`RuleProfile`、测试辅助 `CardIds`、`DeckTest`；不得修改已固定的 `pom.xml` 版本，除非说明原因 | 先看 `DeckTest` 红灯，再用 `./mvnw.cmd -Dtest=DeckTest test` 变绿；108 个不同 ID、每人 27 张、两红桃 2 为 ID 1/55、同 seed 可复现、输入不被改写 |
| C：T3 选牌解析与比较 | Qoder 在 B 之后承担 | `rules/MoveClassifier.java`、`MoveComparator.java` 和对应测试；消费 A 的人工 fixture | `./mvnw.cmd -Dtest=MoveClassifierTest,MoveComparatorTest test`；P01–P07 必须**集合相等**，不能只查包含某一声明；非法实体和重复 ID 有确定错误 |
| D：T4a 独立参照 | 可交 Qoder，但须与 C 的实现者隔离审查 | 测试专用 `SmallHandOracle.java`，n≤10 穷举；不调用生产判定/比较/生成器 | `./mvnw.cmd -Dtest=SmallHandOracleTest test`；独立通过人工完整预期 |
| E：T4b–T4c 生成器与基准 | 在 C、D 之后交付 | `LegalMoveGenerator.java`，小手牌双向语义集合对照；B01–B04 固定 27 张手牌基准 | `./mvnw.cmd -Dtest=LegalMoveGeneratorTest,GeneratorOracleTest test`；`./mvnw.cmd -Pperformance -Dtest=GenerationBenchmarkTest test` 输出真实基线。T4b 正确性与 T4c 性能分别验收 |
| F：T5–T7 牌局、机器人、API | 在 E 后分阶段交付 | 状态机/接风/结算→合法随机机器人及 M2 20 局耗时→内存服务、DTO、幂等和 HTTP；每阶段依主计划单独验收 | 每个动作保持 108 张守恒；M2 输出整局耗时；服务端同 request_id 不重复扣牌、批次失败回滚、对手手牌不可见 |
| G：T8–T11 页面与完整验收 | API 合同稳定后交付 | Vue 牌桌、声明选择、按 `{game_id, command}` 恢复、浏览器 E2E、T10 模拟与性能 | 页面“开始一局”“再来一局”；T10 做完整性能验收，T11 做浏览器与交付验收 |

推荐先将 A–C 作为第一批 Qoder 任务，D/E 在 C 的接口稳定后领取。F/G 不宜与 C/E 同时改规则合同或共享类；若多人并行，只并行各自拥有的文件，接口变更先写入主计划和 fixture。当前目录不是 Git 仓库，提交记录要由项目仓库建立后再执行；不要把“提交成功”当作本地验收的前提。

## 每包统一实践

1. 阅读主计划对应 T 任务的 `Files`、`Interfaces`、验收条款和下方测试矩阵；列出本包负责的准确文件，不同时修改无关模块。
2. 先把人工预期写进 fixture/测试，运行指定单测，确认因功能缺失而失败；再实现最小逻辑，运行该单测变绿。
3. 运行 `./mvnw.cmd test` 做常规回归。标注 `slow`/`performance` 的测试按任务指定命令单独运行；记录命令、测试数量、失败、机器/JDK 版本和报告路径。
4. 新的规则歧义先改 `docs/rules-v1.md`、主计划合同和人工 fixture，再改代码。不要用生成器输出更新人工 expected，也不要靠减少合法候选数量改善性能。
5. 交付时附上变更文件、接口签名、测试结果、未解决问题和下个任务依赖。只有验收项全满足才标记该包完成。

## 必须保持的接口边界

- 实体 ID 0–107；普通牌 `deck_index * 54 + (rank - 2) * 4 + suit_index`，S/H/D/C 顺序；每副 52/53 为小王/大王。`H2@0`、`H2@1` 对应 1、55。
- 游戏状态只在 `engine` 中纯转移；机器人只接收 `PlayerView`、合法动作和独立 RNG。服务层持每局锁并复制工作状态，成功后一次提交。
- HTTP 统一 `/api/v1`，创建局与健康检查之外使用会话令牌；只返回白名单公开 DTO。浏览器保存 `{game_id, command}`，网络结果不明时原样重试。
- 不新增升级、贡还、跨局状态、数据库、多人联机或 Python 后端。性能基准 T4 先建立，M2 测整局耗时，T10 才做完整验收。

## 可直接交给 Qoder 的第一批指令

> 请在 `E:\professional_class\guandan` 中只完成本交接文档的 A（T1 人工规则基线）和 B（T2 牌与领域模型），不要开始 T3 或修改前端。先通读 `2026-10-06-guandan-v1-mvp.md` 的规则合同、架构合同、P01–P07/R01–R24 预期，以及 `backend/README.md`。A 的人工 expected 由规则合同逐项推导，不能运行判定器后反填；B 先写 `DeckTest` 并看到预期红灯，再实现纯 Java 模型和发牌，最后运行 `./mvnw.cmd -Dtest=DeckTest test` 与 `./mvnw.cmd test`。交付 `docs/rules-v1.md`、两个 JSON fixture、`domain/model`、`Deck`、`RuleProfile`、`CardIds` 和测试；附实际测试命令、通过数量、尚未解决的问题。现有 Spring Boot、Maven 版本与健康接口保留，若必须改动配置，先说明原因。当前目录不是 Git 仓库，不要声称已经提交代码。
