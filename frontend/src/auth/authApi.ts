import { apiFetch } from './apiFetch'
import { setToken } from './session'

export type Role = 'ADMIN' | 'OWNER' | 'RESIDENT'

export interface Me {
  login: string
  role: Role
  /** The numbers of the personal accounts the user may open. */
  accounts: string[]
}

export type LoginResult = { status: 'ok' } | { status: 'invalid' } | { status: 'error' }
export type MeResult = { status: 'ok'; me: Me } | { status: 'unauthorized' } | { status: 'error' }

// Never throws; on success the token is kept in the session.
export async function login(loginName: string, password: string): Promise<LoginResult> {
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ login: loginName, password }),
    })
    if (response.status === 401 || response.status === 400) return { status: 'invalid' }
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { access_token?: string }
    if (!body.access_token) return { status: 'error' }
    setToken(body.access_token)
    return { status: 'ok' }
  } catch {
    return { status: 'error' }
  }
}

export async function fetchMe(): Promise<MeResult> {
  try {
    const response = await apiFetch('/api/auth/me')
    if (response.status === 401) return { status: 'unauthorized' }
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', me: (await response.json()) as Me }
  } catch {
    return { status: 'error' }
  }
}

export const ROLE_NAMES: Record<Role, string> = {
  ADMIN: 'Администратор',
  OWNER: 'Собственник',
  RESIDENT: 'Жилец',
}
