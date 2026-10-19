import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './apiFetch'
import { clearToken, getToken, onSessionExpired, setToken } from './session'

afterEach(() => {
  clearToken()
  vi.restoreAllMocks()
})

describe('apiFetch', () => {
  it('adds the token and keeps the other headers and options', async () => {
    setToken('abc')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'))

    await apiFetch('/api/x', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })

    const [url, init] = fetchMock.mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(url).toBe('/api/x')
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe('{}')
    expect(headers.get('Authorization')).toBe('Bearer abc')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('sends no Authorization header without a session', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'))
    await apiFetch('/api/x')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).has('Authorization')).toBe(false)
  })

  it('ends the session on 401', async () => {
    setToken('abc')
    const listener = vi.fn()
    onSessionExpired(listener)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))

    const response = await apiFetch('/api/x')

    expect(response.status).toBe(401)
    expect(getToken()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('keeps the session on 403', async () => {
    setToken('abc')
    const listener = vi.fn()
    const off = onSessionExpired(listener)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 403 }))

    await apiFetch('/api/x')

    expect(getToken()).toBe('abc')
    expect(listener).not.toHaveBeenCalled()
    off()
  })
})
