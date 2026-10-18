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
    const response = await fetch(`/api/accounts/${encodeURIComponent(accountNumber)}/receipts`)
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { receipts?: Receipt[] }
    return { status: 'ok', receipts: body.receipts ?? [] }
  } catch {
    return { status: 'error' }
  }
}

// The PDF is fetched by the browser itself from this address (Content-Disposition: attachment).
export function receiptUrl(accountNumber: string, month: string): string {
  return `/api/accounts/${encodeURIComponent(accountNumber)}/receipts/${month}`
}
