import type { components } from './schema'

type CreatedGame = components['schemas']['CreatedGame']
type GameView = components['schemas']['GameView']
type CommandRequest = components['schemas']['CommandRequest']
type CommandResponse = components['schemas']['CommandResponse']
type ResolveResponse = components['schemas']['ResolveResponse']
type LegalActionsResponse = components['schemas']['LegalActionsResponse']
type ErrorBody = { code?: string; message?: string; details?: unknown }

const BASE = '/api/v1'

/** 后端统一错误体 {code,message,details} → 携带 HTTP 状态的异常，供 UI 按 code 决策恢复。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function toResult<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as ErrorBody
    throw new ApiError(res.status, body.code ?? 'HTTP_ERROR', body.message ?? res.statusText, body.details)
  }
  return (await res.json()) as T
}

/** 游戏 HTTP 客户端。fetch 可注入以便测试；所有游戏路由（除创建）带 Bearer 令牌。 */
export interface GameApi {
  createGame(): Promise<CreatedGame>
  getGame(gameId: string, token: string): Promise<GameView>
  resolveSelection(gameId: string, token: string, expectedVersion: number, cardIds: number[]): Promise<ResolveResponse>
  getLegalActions(gameId: string, token: string): Promise<LegalActionsResponse>
  submitCommand(gameId: string, token: string, command: CommandRequest): Promise<CommandResponse>
}

export function createGameApi(fetchImpl: typeof fetch = fetch): GameApi {
  const auth = (token: string): Record<string, string> => ({
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  })
  return {
    async createGame(): Promise<CreatedGame> {
      const res = await fetchImpl(`${BASE}/games`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      return toResult<CreatedGame>(res)
    },
    async getGame(gameId: string, token: string): Promise<GameView> {
      const res = await fetchImpl(`${BASE}/games/${encodeURIComponent(gameId)}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return toResult<GameView>(res)
    },
    async resolveSelection(gameId, token, expectedVersion, cardIds): Promise<ResolveResponse> {
      const res = await fetchImpl(`${BASE}/games/${encodeURIComponent(gameId)}/resolve`, {
        method: 'POST',
        headers: auth(token),
        body: JSON.stringify({ expected_version: expectedVersion, card_ids: cardIds }),
      })
      return toResult<ResolveResponse>(res)
    },
    async getLegalActions(gameId: string, token: string): Promise<LegalActionsResponse> {
      const res = await fetchImpl(`${BASE}/games/${encodeURIComponent(gameId)}/legal-actions`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return toResult<LegalActionsResponse>(res)
    },
    async submitCommand(gameId, token, command): Promise<CommandResponse> {
      const res = await fetchImpl(`${BASE}/games/${encodeURIComponent(gameId)}/actions`, {
        method: 'POST',
        headers: auth(token),
        body: JSON.stringify(command),
      })
      return toResult<CommandResponse>(res)
    },
  }
}
