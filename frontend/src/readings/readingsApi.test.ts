import { afterEach, describe, expect, it, vi } from 'vitest'
import { submitReadings } from './readingsApi'

afterEach(() => vi.restoreAllMocks())

function respond(status: number, body?: unknown) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body), { status }),
  )
}

describe('submitReadings', () => {
  it('posts the meter numbers and numeric values to the account readings endpoint', async () => {
    const fetchMock = respond(201, { readings: [] })

    const result = await submitReadings('000000000001', [
      { meterNumber: 'Тест 1', value: '250.125' },
      { meterNumber: 'Тест 2', value: '520' },
    ])

    expect(result).toEqual({ status: 'ok' })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/accounts/000000000001/readings')
    expect(init?.method).toBe('POST')
    expect((init?.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect(JSON.parse(String(init?.body))).toEqual({
      readings: [
        { meter_number: 'Тест 1', value: 250.125 },
        { meter_number: 'Тест 2', value: 520 },
      ],
    })
  })

  it('encodes the account number in the URL', async () => {
    const fetchMock = respond(201, { readings: [] })
    await submitReadings('a/b', [{ meterNumber: 'M', value: '1' }])
    expect(fetchMock.mock.calls[0][0]).toBe('/api/accounts/a%2Fb/readings')
  })

  it('returns the server errors of a 400 answer', async () => {
    respond(400, {
      errors: [
        { meter_number: 'Тест 1', code: 'VALUE_LESS_THAN_PREVIOUS' },
        { meter_number: null, code: 'EMPTY_REQUEST' },
      ],
    })

    expect(await submitReadings('1', [{ meterNumber: 'Тест 1', value: '1' }])).toEqual({
      status: 'invalid',
      errors: [
        { meterNumber: 'Тест 1', code: 'VALUE_LESS_THAN_PREVIOUS' },
        { meterNumber: null, code: 'EMPTY_REQUEST' },
      ],
    })
  })

  it('returns no errors for a 400 answer with an unreadable body', async () => {
    respond(400, 'not json')
    expect(await submitReadings('1', [{ meterNumber: 'M', value: '1' }])).toEqual({ status: 'invalid', errors: [] })
  })

  it('maps 404 and 409 to their own results', async () => {
    respond(404)
    expect(await submitReadings('1', [{ meterNumber: 'M', value: '1' }])).toEqual({ status: 'not-found' })
    respond(409)
    expect(await submitReadings('1', [{ meterNumber: 'M', value: '1' }])).toEqual({ status: 'conflict' })
  })

  it('reports other statuses and network failures as an error without throwing', async () => {
    respond(500)
    expect(await submitReadings('1', [{ meterNumber: 'M', value: '1' }])).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await submitReadings('1', [{ meterNumber: 'M', value: '1' }])).toEqual({ status: 'error' })
  })
})
