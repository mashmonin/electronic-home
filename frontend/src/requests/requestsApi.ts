import { apiFetch } from '../auth/apiFetch'
import type { Role } from '../auth/authApi'

export type RequestStatus = 'ACCEPTED' | 'IN_PROGRESS' | 'DONE'

/** GENERAL: written by an owner; VERIFICATION: made by the system when an owner changes the verification date of a meter. */
export type RequestKind = 'GENERAL' | 'VERIFICATION'

export interface RequestItem {
  id: number
  /** The name of the request, given by the server: О-ддммгг-NNNN (П-… for a verification). */
  number: string
  kind: RequestKind
  status: RequestStatus
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

/** One page of the list: the server orders the requests and cuts the page; `total` is the number of all the requests found. */
export type RequestsResult = { status: 'ok'; requests: RequestItem[]; page: number; pageSize: number; total: number } | { status: 'error' }

// Never throws: any failure (network, server, unreadable answer) is reported in the result.
// The server orders them (by the status, the earliest first) and cuts the page; `query` is a fragment of the number to look for.
export async function fetchRequests(query?: string, page = 1): Promise<RequestsResult> {
  try {
    const fragment = query?.trim()
    const params = new URLSearchParams()
    if (fragment) params.set('q', fragment)
    if (page > 1) params.set('page', String(page))
    const response = await apiFetch(params.size > 0 ? `/api/requests?${params.toString()}` : '/api/requests')
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { requests?: RequestItem[]; page?: number; page_size?: number; total?: number }
    const requests = body.requests ?? []
    return { status: 'ok', requests, page: body.page ?? page, pageSize: body.page_size ?? (requests.length || 5), total: body.total ?? requests.length }
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
  | { status: 'invalid' | 'too-large' | 'unsupported' | 'not-found' | 'forbidden' | 'closed' | 'error' }

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
    if (response.status === 409) return { status: 'closed' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export type StatusResult =
  | { status: 'ok'; request: RequestItem }
  | { status: 'conflict' | 'not-found' | 'forbidden' | 'error' }

// The administrator moves the request one step forward; the answer is the request with its new status. Never throws.
export async function changeRequestStatus(requestId: number, status: RequestStatus): Promise<StatusResult> {
  try {
    const response = await apiFetch(`/api/requests/${requestId}/status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
    if (response.ok) return { status: 'ok', request: (await response.json()) as RequestItem }
    if (response.status === 409) return { status: 'conflict' }
    if (response.status === 404) return { status: 'not-found' }
    if (response.status === 403) return { status: 'forbidden' }
    return { status: 'error' }
  } catch {
    return { status: 'error' }
  }
}

export type ReopenResult =
  | { status: 'ok'; request: RequestItem; message: ChatMessage }
  | { status: 'invalid' | 'conflict' | 'too-large' | 'unsupported' | 'not-found' | 'forbidden' | 'error' }

// The author returns a request that is done to the work: the message is required and goes with the change of the status.
export async function reopenRequest(requestId: number, text: string, files: File[] = []): Promise<ReopenResult> {
  try {
    const response = await apiFetch(`/api/requests/${requestId}/reopen`, { method: 'POST', ...messageBody(text, files) })
    if (response.status === 201) {
      const body = (await response.json()) as { request: RequestItem; message: ChatMessage }
      return { status: 'ok', request: body.request, message: body.message }
    }
    if (response.status === 400) return { status: 'invalid' }
    if (response.status === 409) return { status: 'conflict' }
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
