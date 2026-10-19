import { expireSession, getToken } from './session'

// `fetch` for the API: adds the token and ends the session when the server answers 401.
export async function apiFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = getToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(input, { ...init, headers })
  if (response.status === 401 && token) expireSession()
  return response
}
