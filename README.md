# 掼蛋 v1

本项目是本地运行的单局掼蛋 MVP：两副牌共 108 张、固定打 2，真人固定座位 0，另有 3 名合法随机机器人。后端使用 JDK 17、Spring Boot 和 Maven Wrapper；前端使用 Vue 3、TypeScript 和 Vite。

截至 2026-10-20，T1–T11 已通过阶段验收，本地单局 v1 MVP 完成：后端具备规则判定、合法动作生成、牌局状态机、随机机器人，以及带会话令牌和幂等处理的 HTTP 接口；前端具备四边牌桌与实体牌选择、声明选择、出牌/过牌、提示、刷新恢复、原命令重试和事件播放。T10 实测 10,000 局自动对局、复现及本机 HTTP 性能；T11 使用真实后端和浏览器验证核心流程，详见 [测试报告](docs/test-report-v1.md)。页面按“单局 · 两副牌 108 张 · 固定打 2”运行。

此版本面向本机单人对战 3 名机器人；不包含联机、升级/贡还或跨局进度。

## 环境准备

- JDK 17：`java -version` 应显示 17。
- Node.js 22.12 或更高版本，以及 npm。
- 首次执行 Maven Wrapper 或 `npm ci` 时，需要下载依赖。无需全局安装 Maven。

## 一键启动（Windows）

在项目根目录双击 `start.cmd`。脚本检查 JDK 17、Node.js 22.12+、npm 和 Maven Wrapper；首次缺少前端依赖时自动运行 `npm ci`，然后分别启动后端和前端。两个服务就绪后会打开 `http://localhost:5173/`。Maven 使用当前用户目录下的缓存；首次运行可能需要下载后端依赖。

服务在后台运行，输出保存在项目根目录的 `.run/*.log`。在项目根目录双击 `stop.cmd` 可一键关闭两个服务进程。关停脚本只处理由 `start.cmd` 记录的进程，不按端口查杀其他程序；手动启动的服务仍需在各自终端按 `Ctrl+C`。端口 8000 或 5173 已被占用、依赖安装失败或服务在 5 分钟内未就绪时，启动脚本会提示检查日志。也可运行 `start.cmd --check` 仅检查环境，或用 `start.cmd --no-browser` 启动服务但不自动打开浏览器。

## 手动启动（Windows PowerShell）

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

普通后端测试不包含标记为 `slow` 或 `performance` 的测试；专项命令见 [MVP 计划](2026-10-06-guandan-v1-mvp.md)。前端生产构建输出到 `frontend/dist/`。后端也可运行 `.\mvnw.cmd package`，然后用 `java -jar target/guandan-v1.jar` 启动。

浏览器测试使用 Playwright，在独立的 8001/5174 端口自动启动测试后端与页面，默认使用本机 Edge。首次运行先在 `frontend/` 执行 `npm ci`；没有 Edge 时先运行 `npx playwright install chromium`，再设置 `PLAYWRIGHT_CHANNEL=chromium`。测试在 `backend/src/test/java` 注入固定短手牌，不会向生产 JAR 添加调牌接口。失败时保留 `frontend/test-results/` 中的截图与 trace；可用 `npx playwright show-trace <trace.zip>` 复现查看。两个测试端口需空闲。

自动对局若产出失败记录，可在 `backend/` 运行 `java -jar target/guandan-v1.jar replay --input <失败记录.json>`，按记录的 seed 和动作重现。浏览器失败可用 `npx playwright test <spec.ts> --grep <用例名>` 单独重跑，再查看 trace。

## 接口与数据

游戏接口和错误码见 [API 合同](docs/api-v1.md)；机器可读的规范见 [OpenAPI](docs/openapi.json)。除创建游戏外，游戏接口需携带创建响应中的 `Authorization: Bearer <session_token>`。

牌局、令牌和幂等记录仅保存在后端进程内存中；重启后端会清空它们。前端使用当前浏览器标签页的 `sessionStorage` 保存 `game_id` 和令牌，以便刷新后尝试恢复；旧局失效时应重新开始。

本地服务使用单个 JVM，暂不自动回收已结束或被放弃的旧局。页面右上角“动画”复选框可关闭逐事件播放；关闭后命令响应会直接显示最终快照。
