import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../auth/authApi'
import { ADMIN, OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { type FakeRequests, fakeRequests, message, reply, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

const NEAR = (iso: string) => ({ created_at: iso })

function conversation() {
  return {
    requests: [request(1, 'О-191026-0001')],
    messages: {
      1: [
        message(11, 'Подтекает кран', NEAR('2026-10-19T10:00:00Z')),
        reply(12, 'Мастер придёт завтра'),
        message(13, 'Спасибо!'),
      ],
    },
  }
}

async function openChat(user: Me = OWNER, server: FakeRequests = fakeRequests({ ...conversation(), viewer: { role: user.role, login: user.login } })) {
  render(<RequestsTab user={user} search="?request=1" />)
  await screen.findByText('Подтекает кран')
  return server
}

const field = () => screen.getByLabelText('Сообщение') as HTMLTextAreaElement
const sendButton = () => screen.getByRole('button', { name: /Отправить/ })
const type = (text: string) => fireEvent.change(field(), { target: { value: text } })

describe('the messages', () => {
  it('come in order, with the author and the time, the own ones marked', async () => {
    await openChat()

    const feed = screen.getByTestId('chat-feed')
    expect(feed.textContent).toMatch(/Подтекает кран.*Мастер придёт завтра.*Спасибо!/)
    expect(screen.getAllByText(/^Вы · /)).toHaveLength(2)
    expect(screen.getByText(/^Администрация · /)).toBeInTheDocument()
    expect(screen.getAllByText(/\d{2}\.\d{2}, \d{2}:\d{2}/).length).toBeGreaterThan(0)
  })

  it('are read by an administrator under the login of the owner', async () => {
    const server = fakeRequests({ ...conversation(), viewer: { role: 'ADMIN', login: 'admin' } })
    server.messages[1] = [
      message(11, 'Подтекает кран', { mine: false }),
      message(12, 'Уже еду', { author: { login: 'admin', role: 'ADMIN' }, mine: true }),
    ]
    await openChat(ADMIN, server)

    expect(screen.getByText(/^owner · /)).toBeInTheDocument()
    expect(screen.getByText(/^Вы · /)).toBeInTheDocument()
    // in the list and in the header of the chat
    expect(screen.getAllByText('кв. 1 · owner')).toHaveLength(2)
  })

  it('show a message with a retry when they do not load', async () => {
    const server = fakeRequests(conversation())
    server.fail.messages = 500
    render(<RequestsTab user={OWNER} search="?request=1" />)
    expect(await screen.findByText('Не удалось загрузить сообщения')).toBeInTheDocument()

    delete server.fail.messages
    fireEvent.click(screen.getAllByRole('button', { name: 'Повторить' })[0])

    expect(await screen.findByText('Подтекает кран')).toBeInTheDocument()
  })
})

describe('sending', () => {
  it('puts the message into the feed, empties the field and reloads the list', async () => {
    const server = await openChat()
    const listCalls = server.calls('GET', /^\/api\/requests(\?|$)/).length

    type('  Жду мастера  ')
    fireEvent.click(sendButton())

    expect(await screen.findByText('Жду мастера')).toBeInTheDocument()
    expect(field().value).toBe('')
    const [[url, init]] = server.calls('POST', /\/messages$/)
    expect(url).toBe('/api/requests/1/messages')
    expect(JSON.parse(String(init?.body))).toEqual({ text: 'Жду мастера' })
    await waitFor(() => expect(server.calls('GET', /^\/api\/requests(\?|$)/).length).toBeGreaterThan(listCalls))
  })

  it('sends with Ctrl+Enter and with Cmd+Enter, but not with Enter alone', async () => {
    const server = await openChat()
    type('Первое')
    fireEvent.keyDown(field(), { key: 'Enter' })
    expect(server.calls('POST', /\/messages$/)).toHaveLength(0)

    fireEvent.keyDown(field(), { key: 'Enter', ctrlKey: true })
    expect(await screen.findByText('Первое')).toBeInTheDocument()

    type('Второе')
    fireEvent.keyDown(field(), { key: 'Enter', metaKey: true })
    expect(await screen.findByText('Второе')).toBeInTheDocument()
  })

  it('does not allow an empty text', async () => {
    await openChat()
    expect(sendButton()).toBeDisabled()
    type('   ')
    expect(sendButton()).toBeDisabled()
    type('Текст')
    expect(sendButton()).toBeEnabled()
  })

  it('does not allow a text over 2000 characters', async () => {
    await openChat()
    type('с'.repeat(2001))
    expect(screen.getByText('Сообщение длиннее 2000 символов')).toBeInTheDocument()
    expect(sendButton()).toBeDisabled()
    type('с'.repeat(2000))
    expect(screen.queryByText('Сообщение длиннее 2000 символов')).not.toBeInTheDocument()
    expect(sendButton()).toBeEnabled()
  })

  it.each([
    [404, 'Заявка недоступна'],
    [403, 'Недостаточно прав'],
    [500, 'Не удалось отправить сообщение. Попробуйте позже'],
    [0, 'Не удалось отправить сообщение. Попробуйте позже'],
  ])('shows the message for %s and keeps the text', async (status, text) => {
    const server = await openChat()
    server.fail.send = status
    type('Мой текст')

    fireEvent.click(sendButton())

    expect(await screen.findAllByText(text)).not.toHaveLength(0)
    expect(field().value).toBe('Мой текст')
  })

  it('does not send a second time while the first one is running', async () => {
    const server = await openChat()
    let finish: (response: Response) => void = () => {}
    server.fetchMock.mockImplementation((input, init) => {
      if (init?.method === 'POST') {
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      }
      return Promise.resolve(new Response(JSON.stringify({ requests: server.requests, messages: server.messages[1] })))
    })
    type('Один раз')

    fireEvent.click(sendButton())
    await waitFor(() => expect(sendButton()).toBeDisabled())
    fireEvent.click(sendButton())
    fireEvent.keyDown(field(), { key: 'Enter', ctrlKey: true })

    expect(server.calls('POST', /\/messages$/)).toHaveLength(1)
    finish(new Response(JSON.stringify(message(99, 'Один раз')), { status: 201 }))
    expect(await screen.findByText('Один раз', { selector: 'div' })).toBeInTheDocument()
  })
})

describe('there is no closing', () => {
  it('always lets the owner and the administrator write, there are no buttons to close or open a request', async () => {
    await openChat()
    expect(screen.getByLabelText('Сообщение')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Закрыть заявку' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Открыть снова' })).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('Заявка закрыта')
  })

  it('shows an administrator the same chat, with the field to answer in', async () => {
    const server = fakeRequests({ ...conversation(), viewer: { role: 'ADMIN', login: 'admin' } })
    await openChat(ADMIN, server)

    expect(screen.getByLabelText('Сообщение')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Закрыть заявку' })).not.toBeInTheDocument()
  })

  it('says the request is closed when the server refuses the message with a conflict', async () => {
    const server = await openChat()
    server.fail.send = 409
    type('Текст')

    fireEvent.click(sendButton())

    expect(await screen.findByText('Заявка закрыта, писать в неё нельзя')).toBeInTheDocument()
  })
})
