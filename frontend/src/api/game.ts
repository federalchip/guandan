import type { components } from './schema'

type CreatedGame = components['schemas']['CreatedGame']
type GameView = components['schemas']['GameView']
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

/** T8 只需创建与恢复；resolve/legal-actions/submit 在 T9 增加。fetch 可注入以便测试。 */
export interface GameApi {
  createGame(): Promise<CreatedGame>
  getGame(gameId: string, token: string): Promise<GameView>
}

export function createGameApi(fetchImpl: typeof fetch = fetch): GameApi {
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
  }
}
