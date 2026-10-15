# 掼蛋 v1 后端工程

此目录使用 JDK 17、Spring Boot 3.5.16 和 Maven 3.9.11。当前已交付 A–F：规则合同与人工 fixture、108 张牌及领域模型、牌型解析与比较、独立参照 `SmallHandOracle`、生产生成器 `LegalMoveGenerator` 与 T4c 基准、纯牌局状态机 `GameEngine` + `PlayerViewFactory`、随机合法机器人 `RandomLegalPolicy` + M2 耗时基线、内存服务 `GameService`/`InMemoryGameStore` 与 `/api/v1/games` HTTP 接口（会话/幂等/每局锁/错误映射，见 [api-v1](../docs/api-v1.md)）；另有应用启动和 `GET /api/v1/health`。前端页面与 CLI 尚未实现。完整行为合同见 [v1 MVP 计划](../2026-10-06-guandan-v1-mvp.md)，后续开发交接见 [Qoder 任务单](../docs/qoder-handoff-v1.md)。

## 环境

- JDK 17（`java -version` 应显示 17）；Maven 使用仓库中的 Wrapper，无需全局安装。
- Windows PowerShell 从 `backend/` 运行 `./mvnw.cmd`；macOS/Linux 运行 `sh ./mvnw`。
- Wrapper 锁定 Maven 3.9.11、Wrapper 3.3.4；首次运行会下载 Maven 和依赖。
- Spring Boot 的依赖及 Maven 插件版本由 `spring-boot-starter-parent:3.5.16` 管理；QuickTheories 固定为 0.26。

## 运行与检查

```powershell
./mvnw.cmd test
./mvnw.cmd package
java -jar target/guandan-v1.jar
```

服务默认监听 8000 端口。另开终端访问 `http://127.0.0.1:8000/api/v1/health`，应收到 `{"status":"UP"}`。游戏接口 `POST /api/v1/games`（创建）、`GET /api/v1/games/{id}`、`/legal-actions`、`POST /resolve`、`POST /actions` 均已实现，除创建外需 `Authorization: Bearer <session_token>`；字段与错误码见 [api-v1](../docs/api-v1.md)。健康检查不能代替游戏功能验收。

回合 fixture 的 108 张守恒口径：四家手牌与 `discarded` 互不重叠且合计 108 个不同 CardId；当前 `trick` 的牌已在 `discarded` 中，不再另加一次。短手牌 fixture 中未参与的牌也放入 `discarded`。

JUnit Jupiter 测试默认排除 `@Tag("slow")` 和 `@Tag("performance")`；`-Pslow` 运行普通及 slow 测试，`-Pperformance` 运行普通及 performance 测试。可用 `-Dtest=ClassName` 单独选类。测试报告位于 `target/surefire-reports/`，T4/T10 的性能与模拟报告按主计划写入 `artifacts/`。新的性能测试必须打 `performance` 标签，批量模拟测试必须打 `slow` 标签。

## 边界

`com.guandan.domain`、`rules`、`engine`、`bots` 保持纯 Java，不导入 Spring、Jackson 或 HTTP DTO。`api` 只负责请求/响应与校验，`service` 负责会话、幂等、每局锁和命令批次。领域实体用 JDK 17 record/enum；嵌套集合仍需防御性复制。所有用户动作最终由后端裁决。
