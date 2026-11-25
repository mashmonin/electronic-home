import { apiFetch } from '../auth/apiFetch'

export type BatchStatus = 'queued' | 'running' | 'done' | 'failed'

export interface Batch {
  id: number
  month: string
  status: BatchStatus
  total: number
  processed: number
  created: number
  replaced: number
  skipped: number
  failed: number
  error: string | null
  created_at: string
  finished_at: string | null
}

export interface BatchItem {
  account_number: string
  status: 'created' | 'replaced' | 'skipped' | 'failed'
  reason_code: string | null
  reason: string | null
}

export interface BatchDetail extends Batch {
  items: BatchItem[]
}

export type UploadResult = { status: 'accepted'; batch: Batch } | { status: 'refused'; message: string } | { status: 'error' }

// Uploads the statement of the charges (an .xls file). Never throws; a 401 ends the session inside `apiFetch`.
export async function uploadStatement(file: File): Promise<UploadResult> {
  const body = new FormData()
  body.append('file', file)
  try {
    const response = await apiFetch('/api/receipt-batches', { method: 'POST', body })
    if (response.status === 202) return { status: 'accepted', batch: (await response.json()) as Batch }
    if ([400, 413, 415, 422].includes(response.status)) {
      const message = ((await response.json().catch(() => null)) as { message?: string } | null)?.message
      return { status: 'refused', message: message || 'Файл не принят. Проверьте, что это ведомость начислений в формате .xls' }
    }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export async function fetchBatches(): Promise<{ status: 'ok'; batches: Batch[] } | { status: 'error' }> {
  try {
    const response = await apiFetch('/api/receipt-batches')
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { batches?: Batch[] }
    return Array.isArray(body.batches) ? { status: 'ok', batches: body.batches } : { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export async function fetchBatch(id: number): Promise<{ status: 'ok'; batch: BatchDetail } | { status: 'error' }> {
  try {
    const response = await apiFetch(`/api/receipt-batches/${id}`)
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', batch: (await response.json()) as BatchDetail }
  } catch {
    return { status: 'error' }
  }
}
