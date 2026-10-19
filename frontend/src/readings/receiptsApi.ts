import { apiFetch } from '../auth/apiFetch'

export interface Receipt {
  /** The month the receipt is for, `YYYY-MM`. */
  month: string
  /** Size of the PDF in bytes (not shown on the screen). */
  size: number
  /** Amount for capital repair in roubles; null when unknown. */
  capital_repair_amount: number | null
  /** Amount for utilities in roubles; null when unknown. */
  utilities_amount: number | null
  /** When it was published, an ISO timestamp (not shown on the screen). */
  published_at: string
}

export type ReceiptsResult = { status: 'ok'; receipts: Receipt[] } | { status: 'error' }

// Never throws: any failure (network, server, unreadable answer) is reported as `error`.
export async function fetchReceipts(accountNumber: string): Promise<ReceiptsResult> {
  try {
    const response = await apiFetch(`/api/accounts/${encodeURIComponent(accountNumber)}/receipts`)
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { receipts?: Receipt[] }
    return { status: 'ok', receipts: body.receipts ?? [] }
  } catch {
    return { status: 'error' }
  }
}

export type DownloadResult = { status: 'ok'; blob: Blob; filename: string } | { status: 'error' }

// The file is fetched with the token (a plain link could not carry it); never throws.
export async function downloadReceipt(accountNumber: string, month: string): Promise<DownloadResult> {
  try {
    const response = await apiFetch(`/api/accounts/${encodeURIComponent(accountNumber)}/receipts/${month}`)
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', blob: await response.blob(), filename: `receipt-${accountNumber}-${month}.pdf` }
  } catch {
    return { status: 'error' }
  }
}

export interface Amounts {
  capitalRepair: number
  utilities: number
}

export type PublishResult =
  | { status: 'created' | 'replaced' }
  | { status: 'invalid' | 'too-large' | 'not-found' | 'forbidden' | 'error' }

// Publishes (or replaces) the receipt of the month; the file is the whole body, the amounts are optional parameters.
// Never throws; a 401 ends the session inside `apiFetch`.
export async function publishReceipt(
  accountNumber: string,
  month: string,
  file: File,
  amounts: Amounts | null,
): Promise<PublishResult> {
  const query = amounts
    ? `?capital_repair=${amounts.capitalRepair.toFixed(2)}&utilities=${amounts.utilities.toFixed(2)}`
    : ''
  try {
    const response = await apiFetch(`/api/accounts/${encodeURIComponent(accountNumber)}/receipts/${month}${query}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf' },
      body: file,
    })
    if (response.status === 201) return { status: 'created' }
    if (response.status === 200) return { status: 'replaced' }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 413) return { status: 'too-large' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}
