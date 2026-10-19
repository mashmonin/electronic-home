import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearToken, setToken } from '../auth/session'
import { createRequest, downloadAttachment, sendMessage } from './requestsApi'

afterEach(() => {
  vi.restoreAllMocks()
  clearToken()
})

const respond = (status: number, body: unknown = {}) =>
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(body), { status }))

describe('sendMessage', () => {
  it('sends JSON when there are no files', async () => {
    const fetchMock = respond(201, { id: 1 })

    await sendMessage(7, 'Привет')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/requests/7/messages')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(init?.body))).toEqual({ text: 'Привет' })
  })

  it('sends a multipart form with the text and the files, and lets the browser set the boundary', async () => {
    setToken('abc')
    const fetchMock = respond(201, { id: 1 })
    const first = new File(['one'], 'схема.pdf', { type: 'application/pdf' })
    const second = new File(['two'], 'фото.jpg', { type: 'image/jpeg' })

    await sendMessage(7, 'Вот', [first, second])

    const [, init] = fetchMock.mock.calls[0]
    const form = init?.body as FormData
    expect(form).toBeInstanceOf(FormData)
    expect(form.get('text')).toBe('Вот')
    expect((form.getAll('files') as File[]).map((file) => file.name)).toEqual(['схема.pdf', 'фото.jpg'])
    const headers = new Headers(init?.headers)
    expect(headers.has('Content-Type')).toBe(false)
    expect(headers.get('Authorization')).toBe('Bearer abc')
  })

  it('sends an empty text with a file (a message of only a file)', async () => {
    const fetchMock = respond(201, { id: 1 })

    await sendMessage(7, '', [new File(['x'], 'a.pdf')])

    expect((fetchMock.mock.calls[0][1]?.body as FormData).get('text')).toBe('')
  })

  it('names the answers of the server', async () => {
    const expected: [number, string][] = [[400, 'invalid'], [413, 'too-large'], [415, 'unsupported'], [404, 'not-found'], [403, 'forbidden'], [500, 'error']]
    for (const [status, name] of expected) {
      respond(status)
      expect(await sendMessage(7, 'x')).toEqual({ status: name })
    }
  })

  it('reports a network failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await sendMessage(7, 'x', [new File(['x'], 'a.pdf')])).toEqual({ status: 'error' })
  })
})

describe('createRequest', () => {
  it('sends JSON without files, with the account when it is named', async () => {
    const fetchMock = respond(201, { id: 1 })

    await createRequest('Течёт', [], '000000000002')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/requests')
    expect(JSON.parse(String(init?.body))).toEqual({ text: 'Течёт', account_number: '000000000002' })
  })

  it('sends a multipart form with the files and the account', async () => {
    const fetchMock = respond(201, { id: 1 })

    await createRequest('Течёт', [new File(['x'], 'фото.jpg')], '000000000002')

    const form = fetchMock.mock.calls[0][1]?.body as FormData
    expect(form.get('text')).toBe('Течёт')
    expect(form.get('account_number')).toBe('000000000002')
    expect((form.getAll('files') as File[]).map((file) => file.name)).toEqual(['фото.jpg'])
  })

  it('names the answers of the server', async () => {
    const expected: [number, string][] = [[400, 'invalid'], [413, 'too-large'], [415, 'unsupported'], [403, 'forbidden'], [409, 'no-account'], [500, 'error']]
    for (const [status, name] of expected) {
      respond(status)
      expect(await createRequest('x')).toEqual({ status: name })
    }
  })
})

describe('downloadAttachment', () => {
  it('fetches the file of the request with the token', async () => {
    setToken('abc')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('%PDF-1.4'))

    const result = await downloadAttachment(7, 12)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/requests/7/attachments/12')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer abc')
    expect(result.status).toBe('ok')
    if (result.status === 'ok') expect(await result.blob.text()).toBe('%PDF-1.4')
  })

  it('reports an error for a refused request and for a network failure', async () => {
    respond(404)
    expect(await downloadAttachment(7, 12)).toEqual({ status: 'error' })
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    expect(await downloadAttachment(7, 12)).toEqual({ status: 'error' })
  })
})
