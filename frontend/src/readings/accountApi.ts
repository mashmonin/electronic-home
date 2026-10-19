import { apiFetch } from '../auth/apiFetch'
import type { Me } from '../auth/authApi'

export interface AccountReading {
  value: number
  reading_date: string
  previous: { value: number; reading_date: string } | null
}

export interface AccountMeter {
  number: string
  water_type: 'HOT' | 'COLD'
  /** End of the verification period, `YYYY-MM-DD`; null when not known. */
  verification_date: string | null
  /** The reading taken in the current period, null if none (decided by the server). */
  current_period_reading: AccountReading | null
  /** True when a new reading may not be entered now (decided by the server). */
  input_locked: boolean
  /** Newest first. */
  readings: AccountReading[]
}

/** The current reading period; every field is decided by the server. */
export interface ReadingPeriod {
  start: string
  /** The last day of the period, `YYYY-MM-DD`; the period is named after its month. */
  end: string
  next_start: string
  all_submitted: boolean
  /** False while the lock is switched off for testing. */
  enforced: boolean
}

export interface Account {
  number: string
  address: string
  premise: { street: string; house: string; apartment: string }
  owners: { full_name: string }[]
  reading_period: ReadingPeriod
  meters: AccountMeter[]
}

export type AccountLoadResult =
  | { status: 'ok'; account: Account }
  | { status: 'not-found' }
  | { status: 'forbidden' }
  | { status: 'error' }

export async function fetchAccount(number: string): Promise<AccountLoadResult> {
  try {
    const response = await apiFetch(`/api/accounts/${encodeURIComponent(number)}`)
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', account: (await response.json()) as Account }
  } catch {
    return { status: 'error' }
  }
}

// The account is the first one of the user; an administrator may pick another with `?account=`.
export function resolveAccountNumber(search: string, user: Me): string | undefined {
  const fromUrl = user.role === 'ADMIN' ? new URLSearchParams(search).get('account')?.trim() : undefined
  return fromUrl || user.accounts[0]
}
