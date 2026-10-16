# 掼蛋 v1 HTTP API（api-v1）

后端权威接口，统一前缀 `/api/v1`，`Content-Type: application/json`。除 `POST /games`（创建）外，
所有路由需 `Authorization: Bearer <session_token>`。路由不接收客户端 `player_id`：真人固定座位 0，
机器人座位不对外开放。规则固定 `gd-v1-level2`（单局 · 两副牌 108 张 · 固定打 2 · 真人先出）。

本文档由 T7 实现核对生成，字段与 `com.guandan.api.dto` 一致。

## 严格性与约定

- JSON 字段统一 snake_case；enum 转字符串，List 转数组。
- 反序列化 **拒绝未知字段**、**不做标量强制转换**（`"4"`、`4.0`、`true` 不得当成整数），缺失/多余字段判为结构错误。
- `card_id` 与 `expected_version` 为严格整数；`expected_version` **必填**，缺失或为 `null` 判为 `422 SCHEMA_INVALID`（不当作 `0` 执行）；`request_id` 为 UUID 字符串。
- 动作是带 `kind` 的判别联合：`PLAY` 携带 `card_ids`（1–10 张）与可选 `interpretation_key`；`PASS` **不得**携带 `card_ids` 或 `interpretation_key`。**字段缺失**与**值为 `null`** 可区分：`{"kind":"PASS"}`（缺失，规范形）合法，而 `{"kind":"PASS","card_ids":null}` 或 `{"kind":"PASS","interpretation_key":null}`（显式携带）判为 `422 SCHEMA_INVALID`。
- 响应只含公开信息，**不泄露** `seed`、traceback 或其他座位手牌；对手仅以 `remaining_counts` 张数呈现。
- 进程重启后所有局清空，旧 `game_id` 一律按 `404 GAME_NOT_FOUND` 恢复。

## 路由

| 方法与路径 | 鉴权 | 请求 | 成功响应 |
|---|---|---|---|
| `POST /api/v1/games` | 无 | 空对象 `{}` | `201` CreatedGame |
| `GET /api/v1/games/{id}` | Bearer | 无 | `200` GameView |
| `GET /api/v1/games/{id}/legal-actions` | Bearer | 无 | `200` LegalActionsResponse |
| `POST /api/v1/games/{id}/resolve` | Bearer | ResolveRequest | `200` ResolveResponse（只解析不推进） |
| `POST /api/v1/games/{id}/actions` | Bearer | CommandRequest | `200` CommandResponse（真人 + 机器人批次） |
| `GET /api/v1/health` | 无 | 无 | `200 {"status":"UP"}` |

## 数据结构

### GameView
```json
{
  "game_id": "0d1c...e9",
  "own_hand": [
    {"card_id": 1, "deck_index": 0, "rank": 2, "suit": "H"},
    {"card_id": 4, "deck_index": 0, "rank": 3, "suit": "S"}
  ],
  "remaining_counts": [27, 27, 27, 27],
  "trick": null,
  "finish_order": [],
  "current_player": 0,
  "is_lead": true,
  "can_pass": false,
  "profile_id": "gd-v1-level2",
  "level_rank": 2,
  "phase": "PLAYING",
  "result": null,
  "version": 0
}
```
`trick` 为 `null` 表示当前玩家领出；否则 `{owner_id, move, cards}`。`result` 仅在 `phase="FINISHED"` 时非空：
`{"winner_team":0,"placement_groups":[[0],[2],[1,3]],"outcome":"DOUBLE_UP"}`。

### MoveView
```json
{
  "card_ids": [4, 1],
  "kind": "PAIR",
  "main_rank": 3,
  "length": 2,
  "wild_bindings": [{"card_id": 1, "rank": 3, "suit": null}],
  "interpretation_key": "gd-v1-level2|PAIR|2|3|1|4"
}
```
`wild_bindings` 为百搭（红桃级牌）解释：`suit` 仅同花顺为所声明花色（如 `"S"`），其余牌型为 `null`。
多声明选牌（如一手既可作顺子又可作同花顺）在 `resolve` 的 `choices` 返回多个 MoveView，提交时用 `interpretation_key` 指定。

### EventView
```json
{"seq": 1, "state_version": 1, "event_type": "PLAYED", "actor_id": 0,
 "payload": {"cards": [4], "kind": "SINGLE", "main_rank": 3, "length": 1,
             "interpretation_key": "gd-v1-level2|SINGLE|1|3|4", "wild_bindings": [], "remaining": 26}}
```
`event_type ∈ {PLAYED, PASSED, TRICK_CLOSED, PLAYER_FINISHED, GAME_FINISHED}`；`seq` 由服务层递增分配，
`state_version` 为该事件发生后的状态版本。payload 只含公开信息（PLAYED 为本次 cards/声明/行为者剩余张数）。

## 端点示例

### POST /api/v1/games → 201
```json
{
  "game_id": "0d1c...e9",
  "session_token": "9f2a...7b",
  "state": { "game_id": "0d1c...e9", "own_hand": [ ... ], "version": 0, "is_lead": true, "can_pass": false }
}
```
`game_id` 与 `state.game_id` 一致；`session_token` 不可预测，仅控制座位 0；洗牌与机器人种子不返回。

### GET /api/v1/games/{id}/legal-actions → 200
```json
{"version": 0, "moves": [ {"card_ids":[4],"kind":"SINGLE","main_rank":3,"length":1,"wild_bindings":[],"interpretation_key":"..."} ], "can_pass": false}
```
领出时 `can_pass=false` 且 `moves` 只含 PLAY 候选；跟牌时 `can_pass=true`。结束后 `moves=[]`、`can_pass=false`。

### POST /api/v1/games/{id}/resolve → 200
请求：`{"expected_version": 0, "card_ids": [4, 8, 12, 16, 1]}`
```json
{"version": 0, "choices": [ {"card_ids":[1,4,8,12,16],"kind":"STRAIGHT", ...}, {"...":"STRAIGHT_FLUSH"} ]}
```
只解析、不推进状态；多解释返回多个 `choices`；无可压声明返回空数组。

### POST /api/v1/games/{id}/actions → 200
请求：
```json
{"request_id": "6f1e...c2", "expected_version": 0,
 "action": {"kind": "PLAY", "card_ids": [4], "interpretation_key": "gd-v1-level2|SINGLE|1|3|4"}}
```
响应：
```json
{"request_id": "6f1e...c2", "accepted_version": 1, "state": { "...": "GameView（批次提交后）", "version": 4 },
 "events": [ {"seq":1,"state_version":1,"event_type":"PLAYED","actor_id":0,"payload":{"...":"..."}},
             {"seq":2,"state_version":2,"event_type":"PASSED","actor_id":1,"payload":{}} ]}
```
`accepted_version` 是**真人单个动作后**的版本，非机器人批次最终版本；`state.version` 为批次提交后的最终版本。
过牌：`{"request_id":"...","expected_version":4,"action":{"kind":"PASS"}}`。

## 幂等、版本与事务

- `CommandRequest = {request_id, expected_version, action}`。请求体指纹 = `expected_version + action`（不含 `request_id`）。
- 同 `request_id` + **同请求体** → 返回原 `CommandResponse`，不重复扣牌（幂等命中先于版本检查）。
- 同 `request_id` + **不同请求体** → `409 REQUEST_ID_REUSED`。
- `expected_version` 与当前版本不符 → `409 VERSION_CONFLICT`。
- 每局 `ReentrantLock` 串行化；批次执行真人动作后依次执行机器人动作，直到轮到真人且真人仍持牌，或 `phase=FINISHED`。
- 成功一次提交工作状态、事件与幂等响应；批次中任何规则/机器人异常**整批回滚**（不会出现只扣真人牌）。
- 每命令机器人步数上限 512，超限 `500 BOT_STEP_LIMIT` 并回滚。

## 错误响应

统一 `{"code", "message", "details"}`。

| 状态码 | code | 触发 |
|---|---|---|
| 400 | `DUPLICATE_CARD_ID` | 选牌含重复实体 |
| 400 | `CARD_NOT_OWNED` | 选牌不属于该玩家 |
| 400 | `INVALID_MOVE` | 无有效牌型或压不过桌面 |
| 400 | `INVALID_DECLARATION` | `interpretation_key` 与选牌不匹配 |
| 400 | `PASS_NOT_ALLOWED` | 领出时过牌 |
| 401 | `SESSION_INVALID` | 缺少令牌或令牌无效 |
| 403 | `SESSION_FORBIDDEN` | 令牌属于另一局 |
| 404 | `GAME_NOT_FOUND` | 游戏不存在或服务重启丢失 |
| 409 | `VERSION_CONFLICT` | `expected_version` 不符 |
| 409 | `NOT_YOUR_TURN` | 非当前玩家 |
| 409 | `GAME_FINISHED` | 结束后提交动作 |
| 409 | `REQUEST_ID_REUSED` | 同 id 不同请求体 |
| 409 | `AMBIGUOUS_DECLARATION` | 多声明未带键（`details.choices` 给出有效声明） |
| 422 | `SCHEMA_INVALID` | 字段类型/ID 范围/请求结构不合法（含判别联合违规、未知字段、标量强制、域级 `CARD_ID_OUT_OF_RANGE` 规范化） |
| 500 | `BOT_STEP_LIMIT` | 机器人步数超上限，批次回滚 |
| 500 | `INTERNAL_ERROR` | 未预期异常，批次回滚 |

错误优先级：缺少令牌先 `401`；携带令牌但游戏不存在先 `404`；游戏存在时再区分无效令牌 `401` 与其他局的有效令牌 `403`。会话鉴权由前置拦截器在**请求体解析之前**完成，故不仅语义校验、连 JSON 语法/类型/未知字段等**解析期结构错误**在无令牌时也一律先 `401`（而非 `422`）；令牌有效时结构错误仍为 `422`。`POST /api/v1/games`（创建）与 `/api/v1/health` 不鉴权。

`AMBIGUOUS_DECLARATION` 示例：
```json
{"code": "AMBIGUOUS_DECLARATION", "message": "所选牌有多个声明，需指定 interpretation_key",
 "details": {"choices": [ {"card_ids":[1,4,8,12,16],"kind":"STRAIGHT","...":"..."},
                          {"card_ids":[1,4,8,12,16],"kind":"STRAIGHT_FLUSH","...":"..."} ]}}
```
