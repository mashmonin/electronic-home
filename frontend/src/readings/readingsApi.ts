import type { ReadingServerError, ReadingToSend, SubmitResult } from './types'

// Saves new readings of the account's meters; never throws, failures are reported in the result.
export async function submitReadings(accountNumber: string, readings: ReadingToSend[]): Promise<SubmitResult> {
  try {
    const response = await fetch(`/api/accounts/${encodeURIComponent(accountNumber)}/readings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        readings: readings.map(({ meterNumber, value }) => ({ meter_number: meterNumber, value: Number(value) })),
      }),
    })
    if (response.ok) return { status: 'ok' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 409) return { status: 'conflict' }
    if (response.status === 400) return { status: 'invalid', errors: await parseErrors(response) }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

async function parseErrors(response: Response): Promise<ReadingServerError[]> {
  try {
    const body = (await response.json()) as { errors?: { meter_number?: string | null; code: string }[] }
    return (body.errors ?? []).map((error) => ({ meterNumber: error.meter_number ?? null, code: error.code }))
  } catch {
    return []
  }
}
