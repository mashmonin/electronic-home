import { type MockInstance, vi } from 'vitest'
import type { Role } from '../auth/authApi'
import type { Attachment, ChatMessage, RequestItem } from './requestsApi'

// A stateful stand-in for /api/requests, for the tests: it keeps the requests and the messages in memory, answers like
// the real server and lets a test make a call fail with a status.

export function request(id: number, number: string, overrides: Partial<RequestItem> = {}): RequestItem {
  return {
    id,
    number,
    kind: 'GENERAL',
    status: 'ACCEPTED',
    awaiting_reply: false,
    created_at: '2026-10-19T09:00:00Z',
    last_message_at: `2026-10-19T10:${String(id % 60).padStart(2, '0')}:00Z`,
    last_message: { text: `Начало ${number}`, author_role: 'OWNER' },
    account: { number: '000000000001', apartment: '1' },
    author: { login: 'owner' },
    ...overrides,
  }
}

export function message(id: number, text: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id,
    text,
    created_at: '2026-10-19T10:00:00Z',
    author: { login: 'owner', role: 'OWNER' },
    mine: true,
    attachments: [],
    ...overrides,
  }
}

export function reply(id: number, text: string): ChatMessage {
  return message(id, text, { author: { login: 'admin', role: 'ADMIN' }, mine: false })
}

export interface FakeRequests {
  requests: RequestItem[]
  messages: Record<number, ChatMessage[]>
  /** Makes the next calls of that kind answer with the status (0 means a network failure). */
  fail: Partial<Record<'list' | 'messages' | 'send' | 'create' | 'download' | 'status', number>>
  fetchMock: MockInstance<typeof fetch>
  calls: (method: string, pattern: RegExp) => [string, RequestInit | undefined][]
}

interface Options {
  requests?: RequestItem[]
  messages?: Record<number, ChatMessage[]>
  /** Who asks: decides the `mine` flag of the messages that are sent. */
  viewer?: { role: Role; login: string }
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

interface SentMessage {
  text: string
  files: File[]
  accountNumber?: string
}

// A message arrives as JSON, or as a multipart form when it has files.
function parseSent(body: BodyInit | null | undefined): SentMessage {
  if (body instanceof FormData) {
    return {
      text: String(body.get('text') ?? ''),
      files: body.getAll('files') as File[],
      accountNumber: body.get('account_number') ? String(body.get('account_number')) : undefined,
    }
  }
  const parsed = JSON.parse(String(body)) as { text: string; account_number?: string }
  return { text: parsed.text, files: [], accountNumber: parsed.account_number }
}

const preview = (sent: SentMessage): string =>
  sent.text || (sent.files.length === 1 ? `Вложение: ${sent.files[0].name}` : `Вложений: ${sent.files.length}`)

let nextAttachmentId = 5000

/** The attachments the server would answer with for the files that were sent. */
function attachmentsOf(files: File[]): Attachment[] {
  return files.map((file) => ({ id: nextAttachmentId++, name: file.name, size: file.size, content_type: file.type || 'application/octet-stream' }))
}

export function fakeRequests({ requests = [], messages = {}, viewer = { role: 'OWNER', login: 'owner' } }: Options = {}): FakeRequests {
  let nextId = 1000
  const fake = { requests, messages, fail: {} } as FakeRequests

  const failure = (kind: keyof FakeRequests['fail']): Response | undefined => {
    const status = fake.fail[kind]
    if (status === undefined) return undefined
    if (status === 0) throw new Error('network')
    return new Response(null, { status })
  }

  fake.fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'

    if (method === 'GET' && /^\/api\/requests(\?|$)/.test(url)) {
      const refused = failure('list')
      if (refused) return refused
      // like the server: a fragment of the number, in any case, the Latin O being the Cyrillic one; the order is the test's
      const fragment = new URL(url, 'http://x').searchParams.get('q')?.trim().toUpperCase().replace('O', '\u041E') ?? ''
      return json({ requests: fake.requests.filter((item) => item.number.includes(fragment)) })
    }

    if (method === 'POST' && url === '/api/requests') {
      const refused = failure('create')
      if (refused) return refused
      const sent = parseSent(init?.body)
      const created = request(nextId++, `О-191026-${String(fake.requests.length + 1).padStart(4, '0')}`, {
        awaiting_reply: viewer.role === 'OWNER',
        last_message: { text: preview(sent), author_role: viewer.role },
        account: { number: sent.accountNumber ?? '000000000001', apartment: '1' },
      })
      fake.requests.unshift(created)
      fake.messages[created.id] = [message(nextId++, sent.text, { attachments: attachmentsOf(sent.files) })]
      return json(created, 201)
    }

    const statusUrl = /^\/api\/requests\/(\d+)\/status$/.exec(url)
    if (statusUrl && method === 'PUT') {
      const refused = failure('status')
      if (refused) return refused
      const item = fake.requests.find((candidate) => candidate.id === Number(statusUrl[1]))
      if (!item) return new Response(null, { status: 404 })
      const wanted = (JSON.parse(String(init?.body)) as { status: RequestItem['status'] }).status
      const order: RequestItem['status'][] = ['ACCEPTED', 'IN_PROGRESS', 'DONE', 'CLOSED']
      if (order.indexOf(wanted) !== order.indexOf(item.status) + 1) return new Response(null, { status: 409 })
      item.status = wanted
      if (wanted === 'CLOSED') item.awaiting_reply = false
      return json(item)
    }

    const messagesUrl = /^\/api\/requests\/(\d+)\/messages(\?after=(\d+))?$/.exec(url)
    if (messagesUrl && method === 'GET') {
      const refused = failure('messages')
      if (refused) return refused
      const after = messagesUrl[3] ? Number(messagesUrl[3]) : 0
      return json({ messages: (fake.messages[Number(messagesUrl[1])] ?? []).filter((item) => item.id > after) })
    }
    if (messagesUrl && method === 'POST') {
      const refused = failure('send')
      if (refused) return refused
      const id = Number(messagesUrl[1])
      const body = parseSent(init?.body)
      const sent = message(nextId++, body.text, {
        author: { login: viewer.login, role: viewer.role },
        mine: true,
        attachments: attachmentsOf(body.files),
      })
      fake.messages[id] = [...(fake.messages[id] ?? []), sent]
      const item = fake.requests.find((candidate) => candidate.id === id)
      if (item) {
        item.last_message = { text: preview(body), author_role: viewer.role }
        item.awaiting_reply = viewer.role === 'OWNER'
      }
      return json(sent, 201)
    }

    const file = /^\/api\/requests\/(\d+)\/attachments\/(\d+)$/.exec(url)
    if (file && method === 'GET') {
      const refused = failure('download')
      if (refused) return refused
      return new Response(`content of ${file[2]}`, { headers: { 'Content-Type': 'application/octet-stream' } })
    }

    return new Response(null, { status: 404 })
  })

  fake.calls = (method, pattern) =>
    (fake.fetchMock.mock.calls as [RequestInfo | URL, RequestInit | undefined][])
      .filter(([url, init]) => (init?.method ?? 'GET') === method && pattern.test(String(url)))
      .map(([url, init]) => [String(url), init])
  return fake
}
