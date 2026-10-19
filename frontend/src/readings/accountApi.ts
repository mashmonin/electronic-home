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

// The account of the screen is the first one of the user; `?account=` is not used.
export function resolveAccountNumber(user: Me): string | undefined {
  return user.accounts[0]
}

export interface AccountListItem {
  number: string
  address: string
  apartment: string
}

export type AccountListResult =
  | { status: 'ok'; accounts: AccountListItem[] }
  | { status: 'forbidden' }
  | { status: 'error' }

// Every personal account with its address, for an administrator; never throws.
export async function fetchAccounts(): Promise<AccountListResult> {
  try {
    const response = await apiFetch('/api/accounts')
    if (response.status === 403) return { status: 'forbidden' }
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { accounts?: AccountListItem[] }
    return { status: 'ok', accounts: body.accounts ?? [] }
  } catch {
    return { status: 'error' }
  }
}
