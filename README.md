# 掼蛋 v1

本项目是本地运行的单局掼蛋 MVP：两副牌共 108 张、固定打 2，1 名真人对战 3 名合法随机机器人。后端使用 JDK 17、Spring Boot 和 Maven Wrapper；前端使用 Vue 3、TypeScript 和 Vite。

**项目状态：**桌面浏览器本地可玩；公开在线试玩尚未上线。当前版本不需要账号，也不涉及玩家之间联机。

## 项目预览

> 牌桌截图待补充。截图应来自真实本地运行的游戏，并在展示前检查没有会话令牌、个人信息或调试窗口。

四名玩家分居牌桌四边；你的 27 张手牌按点数向上竖叠、底边对齐。各座位已出的公开牌会保留到本轮结束，机器人动作逐步播放，方便观察。

想自己运行一局，可直接看下面的[Windows 本地试玩](#windows-本地试玩)。实现与验收细节见[测试报告](docs/test-report-v1.md)；公开试玩前仍需完成[上线计划](docs/online-solo-launch-plan.md)。GitHub 展示与发布步骤见[展示预计划](docs/github-showcase-prelaunch.md)。

## Windows 本地试玩

1. 在 GitHub 仓库点击 **Code → Download ZIP**，解压后打开项目文件夹；已有本地项目文件夹可跳过下载。
2. 安装 **JDK 17** 和 **Node.js 22.12 或更高版本**（包含 npm）。首次运行需要联网下载项目依赖；不需要单独安装 Maven。
3. 在项目文件夹的**最外层**双击 [`start.cmd`](start.cmd)。脚本会检查环境、启动游戏服务，准备好后自动打开浏览器。第一次启动可能要等待依赖下载和编译；看到 `Guandan is ready` 即表示可访问 `http://localhost:5173/`。
4. 在网页点击“开始一局”。结束试玩后，回到项目文件夹双击 [`stop.cmd`](stop.cmd)，关闭由启动脚本开启的服务。

若启动失败，先看脚本窗口中的错误提示，再检查项目文件夹下 `.run/backend.err.log`、`.run/backend.out.log`、`.run/frontend.err.log` 和 `.run/frontend.out.log`。端口 8000 或 5173 被占用时，关闭占用端口的程序后重试。这个启动方式只在自己的电脑上运行，不会发布公网游戏服务。

## 如何试玩

点击“开始一局”后，页面会发给真人 27 张实体牌。轮到你时可点击手牌选择，按“提示”查看可出的组合，再按“出牌”；同一组实体牌有多种合法声明时，先在弹出的选项中选择牌型。需要跟牌且不想出时按“过牌”。

机器人行动会逐步显示在各自座位旁；本轮公开牌会保留到后端判定轮次结束。右上角可关闭“动画”以直接显示最终状态。遇到结果不明的请求时按“重试”；刷新页面会尝试恢复当前标签页中的牌局。结算后可按“再来一局”。

## 当前进度

截至 2026-10-20，**T1–T11 已通过阶段验收，本地单局 v1 MVP 完成**。当前可在桌面浏览器中从开始一局玩到结算；这不代表已具备公开上线所需的容量保护、移动端验收和生产运维能力。

| 阶段 | 已交付内容 | 验收依据 |
|---|---|---|
| T1–T7：规则与服务 | 实体牌和牌型判定、合法动作生成、轮转/接风/结算、随机机器人；带会话令牌、版本保护和幂等处理的 HTTP API。 | [MVP 计划](2026-10-06-guandan-v1-mvp.md)、[测试报告](docs/test-report-v1.md) |
| T8–T9 与 v1 体验优化：牌桌 | 四边座位（你在下、搭档在上），27 张手牌按点数向上竖叠且底边对齐；选牌、声明、提示、出牌/过牌、逐事件展示、刷新恢复和原命令重试。公开牌保留在各座位，后端发出轮次结束事件时清桌。 | [体验意见清单](docs/v1-experience-feedback.md) |
| T10：稳定性与性能 | 10,000 局自动对局、失败复现和本机 HTTP 性能测量。 | [测试报告](docs/test-report-v1.md) |
| T11：浏览器交付验收 | 真实后端与桌面 Edge 的 10 项浏览器用例，覆盖整局主线、结算、事件播放、响应丢失重试、跨局隔离和服务重启。 | [测试报告](docs/test-report-v1.md) |

**当前范围：**单机、单 JVM、单人对 3 名机器人；牌局、令牌与幂等记录保存在进程内存中，重启后失效。暂不支持真人联机、升级/贡还、跨局进度，也未完成移动端与多人并发验收。页面按“单局 · 两副牌 108 张 · 固定打 2”运行。

**下一阶段：**[小规模公开上线计划](docs/online-solo-launch-plan.md)仍是尚未实施的草案。优先补齐创建频率限制、活跃牌局上限与过期回收，再做部署环境中的移动端、并发和弱网验收，最后配置 HTTPS、监控和回滚。

## 启动脚本说明（进阶）

`start.cmd` 检查 JDK 17、Node.js 22.12+、npm 和 Maven Wrapper；首次缺少前端依赖时自动运行 `npm ci`，然后分别启动后端和前端。两个服务就绪后会打开 `http://localhost:5173/`。Maven 使用当前用户目录下的缓存；首次运行可能需要下载后端依赖。

服务在后台运行，输出保存在项目根目录的 `.run/*.log`。在项目根目录双击 `stop.cmd` 可一键关闭两个服务进程。关停脚本只处理由 `start.cmd` 记录的进程，不按端口查杀其他程序；手动启动的服务仍需在各自终端按 `Ctrl+C`。端口 8000 或 5173 已被占用、依赖安装失败或服务在 5 分钟内未就绪时，启动脚本会提示检查日志。也可运行 `start.cmd --check` 仅检查环境，或用 `start.cmd --no-browser` 启动服务但不自动打开浏览器。

## 开发者手动启动（Windows PowerShell）

在第一个终端启动后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

后端默认监听 `http://localhost:8000`。访问 `http://localhost:8000/api/v1/health`，正常响应为 `{"status":"UP"}`。

在第二个终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

打开 `http://localhost:5173/`，点击“开始一局”。Vite 将 `/api` 请求代理到后端 8000 端口；两个进程都需要保持运行。要停止服务，在各自终端按 `Ctrl+C`。

在 macOS/Linux 上，后端命令改为 `sh ./mvnw spring-boot:run`；前端命令相同。

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

普通后端测试不包含标记为 `slow` 或 `performance` 的测试；如需完整复验，在 `backend/` 依次运行 `.\mvnw.cmd -Pslow test` 和 `.\mvnw.cmd -Pperformance test`。前端生产构建输出到 `frontend/dist/`。后端也可运行 `.\mvnw.cmd package`，然后用 `java -jar target/guandan-v1.jar` 启动。最近一次阶段验收的环境、命令、结果与限制见[测试报告](docs/test-report-v1.md)。

浏览器测试使用 Playwright，在独立的 8001/5174 端口自动启动测试后端与页面，默认使用本机 Edge。首次运行先在 `frontend/` 执行 `npm ci`；没有 Edge 时先运行 `npx playwright install chromium`，再设置 `PLAYWRIGHT_CHANNEL=chromium`。测试在 `backend/src/test/java` 注入固定短手牌，不会向生产 JAR 添加调牌接口。失败时保留 `frontend/test-results/` 中的截图与 trace；可用 `npx playwright show-trace <trace.zip>` 复现查看。两个测试端口需空闲。

自动对局若产出失败记录，可在 `backend/` 运行 `java -jar target/guandan-v1.jar replay --input <失败记录.json>`，按记录的 seed 和动作重现。浏览器失败可用 `npx playwright test <spec.ts> --grep <用例名>` 单独重跑，再查看 trace。

## 接口与数据

游戏接口和错误码见 [API 合同](docs/api-v1.md)；机器可读的规范见 [OpenAPI](docs/openapi.json)。除创建游戏外，游戏接口需携带创建响应中的 `Authorization: Bearer <session_token>`。

牌局、令牌和幂等记录仅保存在后端进程内存中；重启后端会清空它们。前端使用当前浏览器标签页的 `sessionStorage` 保存 `game_id` 和令牌，以便刷新后尝试恢复；旧局失效时应重新开始。

本地服务使用单个 JVM，暂不自动回收已结束或被放弃的旧局。页面右上角“动画”复选框可关闭逐事件播放；关闭后命令响应会直接显示最终快照。

## 许可证

本项目代码采用 [MIT License](LICENSE)，版权署名为 `2026 federalchip`。仓库公开与在线试玩是两件事：许可证允许按其条款使用代码，当前 README 不提供可直接游玩的公网地址。
