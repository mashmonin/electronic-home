export interface Account {
  number: string
  address: string
  premise: { street: string; house: string; apartment: string }
  owners: { full_name: string }[]
}

export type AccountLoadResult =
  | { status: 'ok'; account: Account }
  | { status: 'not-found' }
  | { status: 'error' }

export async function fetchAccount(number: string): Promise<AccountLoadResult> {
  try {
    const response = await fetch(`/api/accounts/${encodeURIComponent(number)}`)
    if (response.status === 404) return { status: 'not-found' }
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', account: (await response.json()) as Account }
  } catch {
    return { status: 'error' }
  }
}

// The account number comes from `?account=`; development builds may fall back to a default one.
export function resolveAccountNumber(search: string, fallback?: string): string | undefined {
  const fromUrl = new URLSearchParams(search).get('account')?.trim()
  return fromUrl || fallback?.trim() || undefined
}
