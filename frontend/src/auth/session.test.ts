import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, expireSession, getToken, onSessionExpired, setToken } from './session'

afterEach(() => {
  clearToken()
  vi.restoreAllMocks()
})

describe('session', () => {
  it('keeps the token in the session storage of the tab only', () => {
    setToken('abc')
    expect(getToken()).toBe('abc')
    expect(window.sessionStorage.length).toBe(1)
    expect(window.localStorage.length).toBe(0)
  })

  it('forgets the token', () => {
    setToken('abc')
    clearToken()
    expect(getToken()).toBeNull()
    expect(window.sessionStorage.length).toBe(0)
  })

  it('still works when the storage is not available', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    setToken('abc')
    expect(getToken()).toBe('abc')
  })

  it('tells the listeners when the session expires, and drops the token', () => {
    const listener = vi.fn()
    const off = onSessionExpired(listener)
    setToken('abc')

    expireSession()

    expect(listener).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()
    off()
    expireSession()
    expect(listener).toHaveBeenCalledTimes(1)
  })
})
