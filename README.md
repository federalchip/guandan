# 掼蛋 v1

本项目是本地运行的单局掼蛋 MVP：两副牌共 108 张、固定打 2，真人固定座位 0，另有 3 名合法随机机器人。后端使用 JDK 17、Spring Boot 和 Maven Wrapper；前端使用 Vue 3、TypeScript 和 Vite。

当前前端已实现牌桌展示、按实体牌 ID 选牌、手牌级牌序展示和刷新恢复；出牌、提示、声明选择等交互属于后续 T9，当前页面还不能完成一整局。

## 环境准备

- JDK 17：`java -version` 应显示 17。
- Node.js 22.12 或更高版本，以及 npm。
- 首次执行 Maven Wrapper 或 `npm ci` 时，需要下载依赖。无需全局安装 Maven。

## 本地启动（Windows PowerShell）

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
```

普通后端测试不包含标记为 `slow` 或 `performance` 的测试；专项命令见 [MVP 计划](2026-10-06-guandan-v1-mvp.md)。前端生产构建输出到 `frontend/dist/`。后端也可运行 `.\mvnw.cmd package`，然后用 `java -jar target/guandan-v1.jar` 启动。

## 接口与数据

游戏接口和错误码见 [API 合同](docs/api-v1.md)；机器可读的规范见 [OpenAPI](docs/openapi.json)。除创建游戏外，游戏接口需携带创建响应中的 `Authorization: Bearer <session_token>`。

牌局、令牌和幂等记录仅保存在后端进程内存中；重启后端会清空它们。前端使用当前浏览器标签页的 `sessionStorage` 保存 `game_id` 和令牌，以便刷新后尝试恢复；旧局失效时应重新开始。
