import { type ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { type Me, fetchMe, login as requestLogin } from './authApi'
import { clearToken, getToken, onSessionExpired } from './session'

export const SESSION_EXPIRED = 'Сессия истекла. Войдите снова'

type State =
  | { status: 'loading' }
  | { status: 'anonymous'; notice?: string }
  | { status: 'authenticated'; me: Me }

export type SignInResult = 'ok' | 'invalid' | 'error'

interface Auth {
  state: State
  signIn: (login: string, password: string) => Promise<SignInResult>
  signOut: () => void
}

const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth must be used inside AuthProvider')
  return auth
}

// Keeps who is signed in: restores the session of the tab on start, ends it when the server refuses the token.
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>(() => (getToken() ? { status: 'loading' } : { status: 'anonymous' }))

  useEffect(() => onSessionExpired(() => setState({ status: 'anonymous', notice: SESSION_EXPIRED })), [])

  useEffect(() => {
    if (state.status !== 'loading') return
    let cancelled = false
    fetchMe().then((result) => {
      if (cancelled) return
      if (result.status === 'ok') setState({ status: 'authenticated', me: result.me })
      else if (result.status === 'unauthorized') setState({ status: 'anonymous', notice: SESSION_EXPIRED })
      else setState({ status: 'anonymous' })
    })
    return () => {
      cancelled = true
    }
  }, [state.status])

  const signIn = useCallback(async (login: string, password: string): Promise<SignInResult> => {
    const result = await requestLogin(login, password)
    if (result.status !== 'ok') return result.status
    const me = await fetchMe()
    if (me.status !== 'ok') {
      clearToken()
      return 'error'
    }
    setState({ status: 'authenticated', me: me.me })
    return 'ok'
  }, [])

  const signOut = useCallback(() => {
    clearToken()
    setState({ status: 'anonymous' })
  }, [])

  const value = useMemo(() => ({ state, signIn, signOut }), [state, signIn, signOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
