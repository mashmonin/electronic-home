import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchMe, login } from './authApi'
import { clearToken, getToken, setToken } from './session'

afterEach(() => {
  clearToken()
  vi.restoreAllMocks()
})

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

describe('login', () => {
  it('posts the credentials and keeps the token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ access_token: 'abc', token_type: 'Bearer' }))

    expect(await login('owner', 'secret')).toEqual({ status: 'ok' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/auth/login')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ login: 'owner', password: 'secret' })
    expect(getToken()).toBe('abc')
  })

  it('reports wrong credentials', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))
    expect(await login('owner', 'x')).toEqual({ status: 'invalid' })
    expect(getToken()).toBeNull()
  })

  it('reports a failure of the server or the network', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 500 }))
    expect(await login('owner', 'x')).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await login('owner', 'x')).toEqual({ status: 'error' })
  })
})

describe('fetchMe', () => {
  const me = { login: 'owner', role: 'OWNER', accounts: ['000000000001'] }

  it('returns the user', async () => {
    setToken('abc')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(me))
    expect(await fetchMe()).toEqual({ status: 'ok', me })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/me')
  })

  it('tells when the token is refused', async () => {
    setToken('abc')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))
    expect(await fetchMe()).toEqual({ status: 'unauthorized' })
  })

  it('reports other failures', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 500 }))
    expect(await fetchMe()).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await fetchMe()).toEqual({ status: 'error' })
  })
})
