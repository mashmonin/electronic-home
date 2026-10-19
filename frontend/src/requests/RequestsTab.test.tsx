import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADMIN, OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { fakeRequests, message, reply, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

const OWN = [
  request(3, 'О-191026-0003', { last_message_at: '2026-10-19T12:00:00Z', awaiting_reply: true }),
  request(2, 'О-191026-0002', { last_message_at: '2026-10-19T11:00:00Z', awaiting_reply: true }),
  request(1, 'О-191026-0001', { last_message_at: '2026-10-19T10:00:00Z' }),
]
const MESSAGES = {
  1: [message(11, 'Подтекает кран'), reply(12, 'Мастер придёт завтра')],
  2: [message(21, 'Старый вопрос')],
  3: [message(31, 'Свежий вопрос')],
}

const rows = () => Array.from(document.querySelectorAll('[data-testid^="request-"]')).map((row) => row.textContent ?? '')

describe('the list', () => {
  it('shows the requests of an owner in the order of the API, with a hint to choose one', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)

    await screen.findByText('О-191026-0003')
    expect(rows()).toHaveLength(3)
    expect(rows()[0]).toContain('О-191026-0003')
    expect(rows()[1]).toContain('О-191026-0002')
    expect(rows()[2]).toContain('О-191026-0001')
    expect(rows()[0]).toContain('Начало О-191026-0003')
    expect(screen.getByText('Выберите заявку')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Новая заявка' })).toBeInTheDocument()
  })

  it('shows an administrator whose request it is, and offers no new request', async () => {
    fakeRequests({
      requests: [request(5, 'О-191026-0005', { account: { number: '000000000002', apartment: '2' }, author: { login: 'petrov' } })],
      viewer: { role: 'ADMIN', login: 'admin' },
    })
    render(<RequestsTab user={ADMIN} search="" />)

    await screen.findByText('О-191026-0005')
    expect(screen.getByText('кв. 2 · petrov')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Новая заявка' })).not.toBeInTheDocument()
  })

  it('shows a spinner while the list loads', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))
    const { container } = render(<RequestsTab user={OWNER} search="" />)
    expect(container.querySelector('.ant-spin')).toBeInTheDocument()
  })

  it('says that there are no requests, with a hint for an owner', async () => {
    fakeRequests()
    render(<RequestsTab user={OWNER} search="" />)
    expect(await screen.findByText(/Заявок пока нет/)).toBeInTheDocument()
    expect(screen.getByText(/Нажмите «Новая заявка»/)).toBeInTheDocument()
  })

  it('says that there are no requests for an administrator', async () => {
    fakeRequests()
    render(<RequestsTab user={ADMIN} search="" />)
    expect(await screen.findByText('Заявок пока нет')).toBeInTheDocument()
  })

  it('shows an error with a retry', async () => {
    const server = fakeRequests({ requests: OWN.map((item) => ({ ...item })) })
    server.fail.list = 500
    render(<RequestsTab user={OWNER} search="" />)
    expect(await screen.findByText('Не удалось загрузить заявки')).toBeInTheDocument()

    delete server.fail.list
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByText('О-191026-0003')).toBeInTheDocument()
  })
})

describe('the search', () => {
  const field = () => screen.getByLabelText('Поиск по номеру') as HTMLInputElement
  const typeInto = (text: string) => fireEvent.change(field(), { target: { value: text } })

  async function opened() {
    const server = fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')
    return server
  }

  it('has a field with a hint', async () => {
    await opened()
    expect(field()).toBeInTheDocument()
    expect(field().placeholder).toBe('Поиск по номеру заявки')
  })

  it('asks the server for a fragment of the number after a short pause', async () => {
    const server = await opened()

    typeInto('0002')

    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toContain('О-191026-0002')
    expect(server.calls('GET', /\?q=0002$/)).toHaveLength(1)
  })

  it('sends one request for a word typed quickly, not one for every letter', async () => {
    const server = await opened()

    for (const text of ['0', '00', '000', '0003']) typeInto(text)

    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(server.calls('GET', /\?q=/)).toHaveLength(1)
    expect(server.calls('GET', /\?q=0003$/)).toHaveLength(1)
  })

  it('finds a fragment from the middle of the number, ignoring the case and the Latin O', async () => {
    const server = await opened()

    typeInto('o-1910')

    await waitFor(() => expect(server.calls('GET', /\?q=o-1910$/)).toHaveLength(1))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('says when nothing is found, and not that there are no requests', async () => {
    await opened()

    typeInto('7777')

    expect(await screen.findByText('Ничего не найдено по запросу «7777»')).toBeInTheDocument()
    expect(screen.queryByText(/Заявок пока нет/)).not.toBeInTheDocument()
    expect(rows()).toHaveLength(0)
  })

  it('shows the whole list again when the field is cleared', async () => {
    const server = await opened()
    typeInto('0002')
    await waitFor(() => expect(rows()).toHaveLength(1))

    fireEvent.click(document.querySelector('.ant-input-clear-icon') as Element)

    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(field().value).toBe('')
    expect(server.calls('GET', /^\/api\/requests$/).length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the chat of the chosen request open while another number is searched', async () => {
    await opened()
    fireEvent.click(screen.getByTestId('request-1'))
    await screen.findByText('Мастер придёт завтра')

    typeInto('0003')
    await waitFor(() => expect(rows()).toHaveLength(1))

    expect(screen.getByText('Мастер придёт завтра')).toBeInTheDocument()
    expect(screen.queryByTestId('request-1')).not.toBeInTheDocument()
  })

  it('searches by the same fragment when the list is refreshed in the background', async () => {
    // only the intervals are faked, before the list is drawn, so that the refresh timer is the fake one
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = await opened()
    typeInto('0002')
    await waitFor(() => expect(rows()).toHaveLength(1))

    await act(async () => {
      vi.advanceTimersByTime(15000)
    })

    await waitFor(() => expect(server.calls('GET', /\?q=0002$/).length).toBeGreaterThanOrEqual(2))
  })

  it('is emptied when the owner creates a request, so that the new one is seen', async () => {
    await opened()
    typeInto('0002')
    await waitFor(() => expect(rows()).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: 'Новая заявка' }))
    fireEvent.change(await screen.findByLabelText('Сообщение'), { target: { value: 'Новый вопрос' } })
    fireEvent.click(screen.getByRole('button', { name: /Создать/ }))

    await waitFor(() => expect(field().value).toBe(''))
    await waitFor(() => expect(rows().length).toBeGreaterThanOrEqual(3))
  })
})

describe('the requests that wait for an answer', () => {
  it('are marked with a colour, a stripe and a tag, the answered ones are not', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')

    expect(screen.getByTestId('request-3')).toHaveAttribute('data-awaiting', 'true')
    expect(screen.getByTestId('request-2')).toHaveAttribute('data-awaiting', 'true')
    expect(screen.getByTestId('request-1')).toHaveAttribute('data-awaiting', 'false')
    expect(screen.getByTestId('request-3').textContent).toContain('Ждёт ответа')
    expect(screen.getByTestId('request-1').textContent).not.toContain('Ждёт ответа')
    const waiting = screen.getByTestId('request-3') as HTMLElement
    const answered = screen.getByTestId('request-1') as HTMLElement
    expect(waiting.style.background).not.toBe(answered.style.background)
    expect(waiting.style.borderLeft).toContain('solid')
    expect(waiting.style.borderLeft).not.toContain('transparent')
    expect(answered.style.borderLeft).toContain('transparent')
  })

  it('look the same to an administrator', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), viewer: { role: 'ADMIN', login: 'admin' } })
    render(<RequestsTab user={ADMIN} search="" />)
    await screen.findByText('О-191026-0003')

    expect(screen.getAllByText('Ждёт ответа')).toHaveLength(2)
  })

  it('keep the tag when chosen, with a darker background', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')
    const before = (screen.getByTestId('request-3') as HTMLElement).style.background

    fireEvent.click(screen.getByTestId('request-3'))

    await screen.findByText('Свежий вопрос', { selector: 'div' })
    expect(screen.getByTestId('request-3').textContent).toContain('Ждёт ответа')
    expect((screen.getByTestId('request-3') as HTMLElement).style.background).not.toBe(before)
  })

  it('lose the mark after the answer of the administrator and get it back after a new question', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')
    const tick = async () => {
      await act(async () => {
        vi.advanceTimersByTime(15000)
      })
    }

    server.requests.find((item) => item.id === 3)!.awaiting_reply = false
    await tick()
    await waitFor(() => expect(screen.getByTestId('request-3')).toHaveAttribute('data-awaiting', 'false'))
    expect(screen.getByTestId('request-3').textContent).not.toContain('Ждёт ответа')

    server.requests.find((item) => item.id === 3)!.awaiting_reply = true
    await tick()
    await waitFor(() => expect(screen.getByTestId('request-3')).toHaveAttribute('data-awaiting', 'true'))
  })
})

describe('there is no closing', () => {
  it('shows no statuses, no filter by status and no buttons to close or open a request', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES, viewer: { role: 'ADMIN', login: 'admin' } })
    render(<RequestsTab user={ADMIN} search="?request=1" />)
    await screen.findByText('Мастер придёт завтра')

    for (const text of ['Открыта', 'Закрыта', 'Открытые', 'Закрытые', 'Закрыть заявку', 'Открыть снова']) {
      expect(screen.queryByText(text)).not.toBeInTheDocument()
    }
    expect(document.body.textContent).not.toContain('Заявка закрыта')
  })
})

describe('choosing a request', () => {
  it('opens its chat and marks it in the list and in the address', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')

    fireEvent.click(screen.getByTestId('request-1'))

    expect(await screen.findByText('Мастер придёт завтра')).toBeInTheDocument()
    expect(screen.getByTestId('request-1')).toHaveAttribute('aria-current', 'true')
    expect(screen.getByTestId('request-3')).not.toHaveAttribute('aria-current')
    expect(window.location.search).toBe('?request=1')
    expect(screen.queryByText('Выберите заявку')).not.toBeInTheDocument()
  })

  it('opens the request of the address at once', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="?tab=requests&request=2" />)

    expect(await screen.findByText('Старый вопрос')).toBeInTheDocument()
    expect(screen.getByTestId('request-2')).toHaveAttribute('aria-current', 'true')
  })

  it('shows only the hint when the request of the address is not there', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="?request=999" />)

    await screen.findByText('О-191026-0003')
    expect(screen.getByText('Выберите заявку')).toBeInTheDocument()
    expect(screen.queryByText('Не удалось загрузить сообщения')).not.toBeInTheDocument()
  })
})

describe('a narrow screen', () => {
  beforeEach(() => setWideScreen(false))

  it('shows the list alone, then the chat alone with a way back', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0003')
    expect(screen.queryByText('Выберите заявку')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('request-1'))

    expect(await screen.findByText('Подтекает кран')).toBeInTheDocument()
    expect(screen.queryByTestId('request-3')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /К списку/ }))

    expect(await screen.findByTestId('request-3')).toBeInTheDocument()
    expect(screen.queryByText('Подтекает кран')).not.toBeInTheDocument()
    expect(window.location.search).toBe('')
  })

  it('opens the chat of the address at once', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="?request=1" />)

    expect(await screen.findByText('Подтекает кран')).toBeInTheDocument()
    expect(screen.queryByTestId('request-3')).not.toBeInTheDocument()
  })
})

describe('a wide screen', () => {
  it('shows the list and the chat together and has no way back', async () => {
    fakeRequests({ requests: OWN.map((item) => ({ ...item })), messages: MESSAGES })
    render(<RequestsTab user={OWNER} search="?request=1" />)

    expect(await screen.findByText('Подтекает кран')).toBeInTheDocument()
    expect(screen.getByTestId('request-3')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /К списку/ })).not.toBeInTheDocument()
  })
})
