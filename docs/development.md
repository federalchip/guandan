# 开发与排障说明

本文集中记录本地开发、验证和实现范围。初次试玩请先看[项目首页](../README.md)的 Windows 本地试玩步骤。

## 当前实现范围

这是单局掼蛋 v1 MVP：两副牌共 108 张、固定打 2，一名真人对战三名合法随机机器人。游戏在单个 JVM 中运行，牌局、会话令牌和幂等记录保存在进程内存中，重启后失效。暂不支持真人联机、升级/贡还或跨局进度。

截至 2026-10-20，T1–T11 已完成本地阶段验收。桌面浏览器可以从开始一局玩到结算；移动端、多人并发及公开服务的运维验收尚未完成。

| 阶段 | 已交付内容 | 依据 |
|---|---|---|
| T1–T7：规则与服务 | 实体牌与牌型判定、合法动作生成、轮转/接风/结算、随机机器人；带会话令牌、版本保护和幂等处理的 HTTP API | [MVP 计划](../2026-10-06-guandan-v1-mvp.md)、[测试报告](test-report-v1.md) |
| T8–T9 与 v1 体验优化 | 四边座位、向上竖叠且底边对齐的 27 张手牌、选牌与提示、逐事件播放、刷新恢复和原命令重试；公开牌在本轮结束时清桌 | [体验意见清单](v1-experience-feedback.md) |
| T10：稳定性与性能 | 10,000 局自动对局、失败复现和本机 HTTP 性能测量 | [测试报告](test-report-v1.md) |
| T11：浏览器交付验收 | 真实后端与桌面 Edge 的浏览器用例，覆盖整局、结算、事件播放、响应丢失重试、跨局隔离和服务重启 | [测试报告](test-report-v1.md) |

公开试玩仍需按[小规模公开上线计划](online-solo-launch-plan.md)补齐容量保护、目标环境验收、HTTPS、监控和回滚。

## Windows 启动脚本

项目根目录的 [`start.cmd`](../start.cmd) 检查 JDK 17、Node.js 22.12+、npm 和 Maven Wrapper。首次缺少前端依赖时会运行 `npm ci`，随后分别启动后端和前端；两个服务就绪后自动打开 <http://localhost:5173/>。首次运行可能需要下载后端依赖。

脚本在后台运行服务，输出保存在根目录的 `.run/*.log`。双击 [`stop.cmd`](../stop.cmd) 可关闭由启动脚本记录的两个服务进程；手动启动的服务仍需在各自终端按 `Ctrl+C`。可运行 `start.cmd --check` 只检查环境，或运行 `start.cmd --no-browser` 启动服务而不自动打开浏览器。

如果启动失败，先查看脚本窗口中的提示，再检查 `.run/backend.err.log`、`.run/backend.out.log`、`.run/frontend.err.log` 和 `.run/frontend.out.log`。端口 8000 或 5173 被占用、依赖安装失败或服务在 5 分钟内未就绪时，脚本会提示错误。

## 手动启动

在第一个 Windows PowerShell 终端启动后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

后端默认监听 <http://localhost:8000>；访问 <http://localhost:8000/api/v1/health> 应得到 `{"status":"UP"}`。

在第二个终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

打开 <http://localhost:5173/>。Vite 将 `/api` 请求代理到后端 8000 端口，两个进程都需要保持运行。在 macOS/Linux 上，后端命令改为 `sh ./mvnw spring-boot:run`，前端命令相同。

## 验证

分别在对应目录运行：

```powershell
# backend/
.\mvnw.cmd test

# frontend/
npm run test:unit -- --run
npm run typecheck
npm run build
npm run test:e2e
```

普通后端测试不包含标记为 `slow` 或 `performance` 的测试。完整复验可在 `backend/` 依次运行 `.\mvnw.cmd -Pslow test` 和 `.\mvnw.cmd -Pperformance test`。后端可用 `.\mvnw.cmd package` 打包，再以 `java -jar target/guandan-v1.jar` 启动；前端生产构建输出到 `frontend/dist/`。最近一次阶段验收的环境、命令、结果与限制见[测试报告](test-report-v1.md)。

浏览器测试使用 Playwright，在独立的 8001/5174 端口启动测试后端与页面，默认使用本机 Edge。首次运行先在 `frontend/` 执行 `npm ci`；没有 Edge 时运行 `npx playwright install chromium`，并设置 `PLAYWRIGHT_CHANNEL=chromium`。测试在 `backend/src/test/java` 注入固定短手牌，不会向生产 JAR 添加调牌接口。失败时保留 `frontend/test-results/` 中的截图与 trace，可用 `npx playwright show-trace <trace.zip>` 查看。

自动对局若产出失败记录，可在 `backend/` 运行 `java -jar target/guandan-v1.jar replay --input <失败记录.json>`，按记录的 seed 和动作复现。浏览器失败可用 `npx playwright test <spec.ts> --grep <用例名>` 单独重跑。

## 接口与数据

接口和错误码见 [API 合同](api-v1.md)，机器可读的规范见 [OpenAPI](openapi.json)。除创建游戏外，游戏接口需携带创建响应中的 `Authorization: Bearer <session_token>`。

前端在当前浏览器标签页的 `sessionStorage` 中保存 `game_id` 和令牌，以便刷新后尝试恢复；旧局失效时应重新开始。本地服务暂不自动回收已结束或被放弃的旧局。页面右上角可关闭“动画”，让命令响应直接显示最终快照。
