import { apiFetch } from '../auth/apiFetch'
import type { Role } from '../auth/authApi'

export interface RequestItem {
  id: number
  /** The name of the request, given by the server: О-ддммгг-NNNN. */
  number: string
  /** True while the last message is the owner's: the administrator has not answered it yet (decided by the server). */
  awaiting_reply: boolean
  created_at: string
  last_message_at: string
  last_message: { text: string; author_role: Role }
  account: { number: string; apartment: string }
  author: { login: string }
}

export interface ChatMessage {
  id: number
  text: string
  created_at: string
  author: { login: string; role: Role }
  mine: boolean
}

export type RequestsResult = { status: 'ok'; requests: RequestItem[] } | { status: 'error' }

// Never throws: any failure (network, server, unreadable answer) is reported in the result.
// The server orders them (those without an answer first); `query` is a fragment of the number to look for.
export async function fetchRequests(query?: string): Promise<RequestsResult> {
  try {
    const fragment = query?.trim()
    const response = await apiFetch(fragment ? `/api/requests?q=${encodeURIComponent(fragment)}` : '/api/requests')
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { requests?: RequestItem[] }
    return { status: 'ok', requests: body.requests ?? [] }
  } catch {
    return { status: 'error' }
  }
}

export type CreateResult =
  | { status: 'ok'; request: RequestItem }
  | { status: 'invalid' | 'forbidden' | 'no-account' | 'error' }

export async function createRequest(text: string, accountNumber?: string): Promise<CreateResult> {
  try {
    const response = await apiFetch('/api/requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, ...(accountNumber ? { account_number: accountNumber } : {}) }),
    })
    if (response.status === 201) return { status: 'ok', request: (await response.json()) as RequestItem }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 403) return { status: 'forbidden' }
    if (response.status === 409) return { status: 'no-account' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export type MessagesResult = { status: 'ok'; messages: ChatMessage[] } | { status: 'not-found' | 'error' }

// The messages of the request; with `after` only those that are newer than the message with that number.
export async function fetchMessages(requestId: number, after?: number): Promise<MessagesResult> {
  try {
    const query = after === undefined ? '' : `?after=${after}`
    const response = await apiFetch(`/api/requests/${requestId}/messages${query}`)
    if (response.status === 404) return { status: 'not-found' }
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { messages?: ChatMessage[] }
    return { status: 'ok', messages: body.messages ?? [] }
  } catch {
    return { status: 'error' }
  }
}

export type SendResult =
  | { status: 'ok'; message: ChatMessage }
  | { status: 'invalid' | 'not-found' | 'forbidden' | 'error' }

export async function sendMessage(requestId: number, text: string): Promise<SendResult> {
  try {
    const response = await apiFetch(`/api/requests/${requestId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    })
    if (response.status === 201) return { status: 'ok', message: (await response.json()) as ChatMessage }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}
