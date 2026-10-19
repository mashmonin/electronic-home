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

/** A file attached to a message; the content is fetched with {@link downloadAttachment}. */
export interface Attachment {
  id: number
  name: string
  size: number
  content_type: string
}

export interface ChatMessage {
  id: number
  /** Empty for a message that is only files. */
  text: string
  created_at: string
  author: { login: string; role: Role }
  mine: boolean
  attachments: Attachment[]
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
  | { status: 'invalid' | 'too-large' | 'unsupported' | 'forbidden' | 'no-account' | 'error' }

// With files the message goes as a multipart form (the browser sets its boundary), without them as JSON.
function messageBody(text: string, files: File[], extra: Record<string, string> = {}): { body: BodyInit; headers?: Record<string, string> } {
  if (files.length === 0) {
    return { body: JSON.stringify({ text, ...extra }), headers: { 'Content-Type': 'application/json' } }
  }
  const form = new FormData()
  form.append('text', text)
  Object.entries(extra).forEach(([name, value]) => form.append(name, value))
  files.forEach((file) => form.append('files', file, file.name))
  return { body: form }
}

export async function createRequest(text: string, files: File[] = [], accountNumber?: string): Promise<CreateResult> {
  try {
    const response = await apiFetch('/api/requests', {
      method: 'POST',
      ...messageBody(text, files, accountNumber ? { account_number: accountNumber } : {}),
    })
    if (response.status === 201) return { status: 'ok', request: (await response.json()) as RequestItem }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 413) return { status: 'too-large' }
    if (response.status === 415) return { status: 'unsupported' }
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
  | { status: 'invalid' | 'too-large' | 'unsupported' | 'not-found' | 'forbidden' | 'error' }

export async function sendMessage(requestId: number, text: string, files: File[] = []): Promise<SendResult> {
  try {
    const response = await apiFetch(`/api/requests/${requestId}/messages`, {
      method: 'POST',
      ...messageBody(text, files),
    })
    if (response.status === 201) return { status: 'ok', message: (await response.json()) as ChatMessage }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 413) return { status: 'too-large' }
    if (response.status === 415) return { status: 'unsupported' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export type DownloadResult = { status: 'ok'; blob: Blob } | { status: 'error' }

// The file goes through the backend, which checks the rights; a plain link would not carry the token. Never throws.
export async function downloadAttachment(requestId: number, attachmentId: number): Promise<DownloadResult> {
  try {
    const response = await apiFetch(`/api/requests/${requestId}/attachments/${attachmentId}`)
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', blob: await response.blob() }
  } catch {
    return { status: 'error' }
  }
}
