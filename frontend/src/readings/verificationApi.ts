import { apiFetch } from '../auth/apiFetch'

export const ACT_EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png']
export const ACT_ACCEPT = ACT_EXTENSIONS.map((extension) => `.${extension}`).join(',')
export const ACT_MAX_BYTES = 10 * 1024 * 1024

/** Why a file cannot be the act, decided before anything is sent; null when it fits. */
export function actProblem(file: Pick<File, 'name' | 'size'>): string | null {
  const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : ''
  if (!ACT_EXTENSIONS.includes(extension)) return 'Допустимы файлы PDF, JPG и PNG'
  if (file.size === 0) return 'Файл пустой'
  if (file.size > ACT_MAX_BYTES) return 'Файл больше 10 МБ'
  return null
}

export type UploadActResult =
  | { status: 'ok'; fileKey: string }
  | { status: 'invalid' | 'too-large' | 'unsupported' | 'not-found' | 'forbidden' | 'error' }

const meterPath = (account: string, meter: string) =>
  `/api/accounts/${encodeURIComponent(account)}/meters/${encodeURIComponent(meter)}`

// The act goes to the storage at once, when it is attached; the answer carries the key that the change of the date sends back.
export async function uploadVerificationAct(account: string, meter: string, file: File): Promise<UploadActResult> {
  try {
    const form = new FormData()
    form.append('file', file, file.name)
    const response = await apiFetch(`${meterPath(account, meter)}/verification-act-uploads`, { method: 'POST', body: form })
    if (response.status === 201) return { status: 'ok', fileKey: ((await response.json()) as { file_key: string }).file_key }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 413) return { status: 'too-large' }
    if (response.status === 415) return { status: 'unsupported' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export type ChangeDateResult =
  // applied: the date is in force (an administrator); otherwise `requestNumber` names the request that waits for the confirmation
  | { status: 'ok'; applied: boolean; requestNumber: string | null }
  | { status: 'invalid' | 'not-found' | 'forbidden' | 'conflict' | 'error' }

export async function changeVerificationDate(account: string, meter: string, date: string, actFileKey: string): Promise<ChangeDateResult> {
  try {
    const response = await apiFetch(`${meterPath(account, meter)}/verification-date`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ verification_date: date, act_file_key: actFileKey }),
    })
    if (response.ok) {
      const body = (await response.json()) as { applied: boolean; request: { number: string } | null }
      return { status: 'ok', applied: body.applied, requestNumber: body.request?.number ?? null }
    }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    if (response.status === 409) return { status: 'conflict' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}
