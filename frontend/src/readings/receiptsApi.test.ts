import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, setToken } from '../auth/session'
import { downloadReceipt, fetchReceipts } from './receiptsApi'

afterEach(() => {
  vi.restoreAllMocks()
  clearToken()
})

describe('fetchReceipts', () => {
  it('asks for the receipts of the account and returns them in the order of the API', async () => {
    const receipts = [
      { month: '2026-10', size: 3145744, capital_repair_amount: 1842.5, utilities_amount: 6318.27, published_at: '2026-10-18T12:00:00Z' },
      { month: '2026-09', size: 512, capital_repair_amount: null, utilities_amount: null, published_at: '2026-09-20T12:00:00Z' },
    ]
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ receipts })))

    expect(await fetchReceipts('000000000001')).toEqual({ status: 'ok', receipts })
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000001/receipts', expect.anything())
  })

  it('encodes the account number', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ receipts: [] })))
    await fetchReceipts('a/b')
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/a%2Fb/receipts', expect.anything())
  })

  it('treats an answer without a list as an empty list', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'))
    expect(await fetchReceipts('1')).toEqual({ status: 'ok', receipts: [] })
  })

  it('reports a server error, a missing account and a network failure as an error without throwing', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 500 }))
    expect(await fetchReceipts('1')).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }))
    expect(await fetchReceipts('1')).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await fetchReceipts('1')).toEqual({ status: 'error' })
  })

  it('reports an unreadable answer as an error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('not json'))
    expect(await fetchReceipts('1')).toEqual({ status: 'error' })
  })
})

describe('downloadReceipt', () => {
  it('fetches the PDF of the month and names the file after the account and the month', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('%PDF-1.4'))

    const result = await downloadReceipt('000000000001', '2026-10')

    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000001/receipts/2026-10', expect.anything())
    expect(result.status).toBe('ok')
    if (result.status === 'ok') {
      expect(result.filename).toBe('receipt-000000000001-2026-10.pdf')
      expect(await result.blob.text()).toBe('%PDF-1.4')
    }
  })

  it('encodes the account number', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('x'))
    await downloadReceipt('a/b', '2026-10')
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/a%2Fb/receipts/2026-10', expect.anything())
  })

  it('sends the token of the session', async () => {
    setToken('abc')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('x'))
    await downloadReceipt('1', '2026-10')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer abc')
  })

  it('reports an error for a refused or failed request', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }))
    expect(await downloadReceipt('1', '2026-10')).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await downloadReceipt('1', '2026-10')).toEqual({ status: 'error' })
  })
})
