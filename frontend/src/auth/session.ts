const KEY = 'electronic-home.token'

// Kept in memory as well: the browser may refuse to give a page the session storage (private mode, blocked data).
let memoryToken: string | null = null
const expiredListeners = new Set<() => void>()

export function getToken(): string | null {
  if (memoryToken) return memoryToken
  try {
    return window.sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function setToken(token: string): void {
  memoryToken = token
  try {
    window.sessionStorage.setItem(KEY, token)
  } catch {
    // the token stays in memory for this page only
  }
}

export function clearToken(): void {
  memoryToken = null
  try {
    window.sessionStorage.removeItem(KEY)
  } catch {
    // nothing to clear
  }
}

// The server refused the token: it is dropped and everyone who listens is told.
export function expireSession(): void {
  clearToken()
  expiredListeners.forEach((listener) => listener())
}

export function onSessionExpired(listener: () => void): () => void {
  expiredListeners.add(listener)
  return () => {
    expiredListeners.delete(listener)
  }
}
