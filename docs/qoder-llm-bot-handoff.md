# Qoder 交接：大模型机器人决策

日期：2026-11-17

状态：待实施任务单

> 将本文件与 [设计方案](llm-bot-decision-design.md) 一起交给 Qoder。当前只完成文档，没有实现异步命令或真实模型接入。模型提供商、协议与凭据尚未确定；Qoder 应先完成不依赖这些信息的架构与假网关验收，再单独报告真实接入所需信息。

## 工作位置与现状

- 当前文档位于 `codex/table-experience` 工作树。机器人策略属于独立后端功能；实施前从合适基线创建单独的 `codex/llm-bot-decision` 分支/工作树，并带入本设计和任务单，避免混入牌桌布局改动。
- 后端：JDK 17、Spring Boot 3.5.16、Maven Wrapper；前端：Vue 3、TypeScript、Vitest、Playwright。先阅读 `docs/rules-v1.md`、`docs/api-v1.md`、`docs/test-report-v1.md`，以及 `backend/src/main/java/com/guandan/service/GameService.java`、`GameRecord.java`、`engine/GameEngine.java`、`engine/PlayerViewFactory.java`、`bots/RandomLegalPolicy.java`、`frontend/src/state/game.ts`。
- 不修改固定打 2 的规则、合法动作语义、隐藏信息边界或原 `request_id` 重试要求。现有同步随机模式应继续可用。

## 建议接口与文件责任

以下是实施边界；签名可按现有包名微调，但改动应先反映到设计文档与 OpenAPI。

| 位置 | 责任 |
| --- | --- |
| `backend/.../service/GameRecord.java` | 增加单局当前命令任务及代次；所有任务字段在该局锁内访问。 |
| `backend/.../service/GameService.java` | 保留鉴权、请求指纹、版本和幂等入口；将异步登记、查询及最终提交委托给编排器。 |
| `backend/.../service/BotBatchRunner.java`（新） | 在私有工作状态中执行真人动作后的机器人批次；不持锁调用模型；统一时间预算、兜底与 512 步上限。 |
| `backend/.../service/CommandJob.java`（新） | 不可变任务 ID、指纹、起始版本、代次和截止时间；状态仅在受锁保护的记录中变化。 |
| `backend/.../bots/CandidateSelector.java`（新） | 从完整合法动作选出有限候选并建立临时 ID 到原 `Action` 的映射。 |
| `backend/.../bots/BotDecisionGateway.java`（新） | 异步 `decide(context)` 抽象；测试用可控假网关，真实提供商适配器在协议确定后实现。 |
| `backend/.../api/GameController.java`、`api/dto/`、`docs/openapi.json` | 增加 `202`、命令状态查询、处理中字段及错误码；保留原 `200 CommandResponse`。 |
| `frontend/src/api/game.ts`、`state/game.ts`、`App.vue`、`api/schema.d.ts` | 识别完成/处理中结果、轮询、刷新恢复、跨局取消、锁定操作及用户提示。 |

`CommandJob` 至少保存同 ID 重试所需的完整请求体指纹。异步执行器应为有界线程池；时钟、执行器和网关都要能注入测试替身，避免依赖真实等待和外部 API。

## 分阶段任务与验收

### A. API 合同与异步本地策略

1. 先补后端失败测试：同 ID 同体在处理中只登记一次；同 ID 异体仍 `REQUEST_ID_REUSED`；不同 ID 在处理中为 `GAME_BUSY`；版本冲突仍优先于新任务执行；`GET /commands/{id}` 只接受本局令牌，未知命令为 `404 COMMAND_NOT_FOUND`，旧局失效仍为 `GAME_NOT_FOUND`；同 ID 的终态失败不得再次执行。
2. 更新 OpenAPI 与 DTO：`POST /actions` 的 `200`/`202` 联合结果、`GET /commands/{request_id}`、`GameView.processing_request_id`。保留原同步随机实现作为默认路径。
3. 在测试开关下让本地随机策略走异步路径。验证整批完成后状态、事件序号和幂等结果一次提交，旧快照在处理中保持不变；前端此阶段可暂未启用异步模式。
4. 执行 `backend/` 下 `./mvnw.cmd -Dtest=GameServiceTest,OpenApiSpecTest test` 与 `./mvnw.cmd test`，记录测试数量和输出。

### B. 并发、截止时间与恢复

1. 先用可控 `CompletableFuture`/假时钟写失败测试：模型等待时同局 `GET /games` 能返回处理中状态；执行器拒绝任务后不留下 `PENDING`；超时由本地策略完成；迟到旧代次结果不能二次提交；模型可用时仅提交一次；工作异常不会卡死牌局。
2. 实现有界执行器、每局单任务、单次/整批截止时间和看门狗；不在持锁路径中等待网络。超时重算必须从未提交的起点开始，旧工作副本不能覆盖新结果。牌局回收时取消任务并清理命令终态，避免内存无限增长。
3. 验证 512 步上限、非法动作导致整批回滚、108 张实体牌守恒、`trick` 属于已出牌、事件 `seq` 单调且不重复。内部规则故障应保留证据并告警，不能静默伪装成模型超时。

### C. 前端异步状态与真实浏览器

1. 先补单测：`202` 后 pending 不清除且操作锁定；查询完成才清除；查询失败保持原命令；刷新后从 `processing_request_id` 恢复；旧局查询的迟到成功/失败都不改写新局。
2. 处理 `frontend/src/api/game.ts` 的 `200`/`202` 联合类型；为 `state/game.ts` 增加命令状态轮询和可取消代次。完成时复用现有事件播放；`FAILED` 时恢复可编辑状态并展示错误，不能误报出牌成功。
3. Playwright 覆盖整局、双击/重复提交、响应丢失、刷新、模型超时兜底及结算。测试后端用固定延迟/故障注入，不访问真实提供商。运行 `npm run test:unit -- --run`、`npm run typecheck`、`npm run build`、`npm run test:e2e`。

### D. 候选整理与模型网关

1. 先补纯单测：候选全来自完整合法列表；`PASS` 合法时必在候选内，领出时绝不出现；不足上限时不丢动作；顺序/ID 对同一状态稳定；多种牌型和百搭消耗有代表；未知或过期 ID 被拒绝。
2. 实现 `CandidateSelector`，初始上限 48，可配置。候选描述只包含机器人自己的牌和公开信息；网关只收这些字段，测试用序列化结果断言其他玩家手牌、令牌与游戏种子不外泄。
3. 假网关测试正确选择、非法 JSON、未知 ID、超时、429、断路器开启和预算耗尽；每种失败均用完整合法列表中的本地动作继续。记录每个决策是否使用模型、兜底原因、耗时、候选数量和模型版本。
4. **真实提供商适配器是独立验收门**：先确认供应商、API 形态、密钥管理和数据处理要求。不得把假网关通过写成真实接入完成，也不得在测试或仓库中放真实密钥。

### E. 评测、文档与交付

1. 在固定种子和同一规则版本上，与当前随机策略比较完整对局率、非法动作数、胜率、p50/p95/最大等待时间、兜底率和每局费用；保留采用的动作序列以便不依赖模型复盘。
2. 根据实测调整候选上限、超时和并发数；更新 `docs/api-v1.md`、`docs/development.md`、本设计方案及测试报告，明确同步/异步模式和重启失效边界。
3. 提交时列出文件清单、API 示例、精确测试结果、未通过项、真实模型是否接入、费用与隐私边界、仍需用户决定的提供商信息。仅当 A～D 的相关验收通过时，才声明异步模型决策可用。

## Qoder 可直接使用的任务说明

> 请阅读 `docs/llm-bot-decision-design.md` 和本任务单，在独立 `codex/llm-bot-decision` 工作树按 A→E 顺序实施。每阶段先写能因缺失能力而失败的测试，再完成实现并运行该阶段和全量回归。保持 `GameEngine` 为唯一规则裁决者，模型只返回本轮合法候选 ID；网络调用不持有牌局锁。先完成本地策略的异步链路与前端恢复，再接可注入的模型网关。若尚未提供供应商协议与凭据，交付假网关及完整故障注入测试，明确标记真实提供商适配器待接入。提交每阶段结果和准确测试输出，不把文档设计当成已实现功能。
