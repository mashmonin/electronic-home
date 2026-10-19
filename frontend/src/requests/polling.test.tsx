import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { type FakeRequests, fakeRequests, message, reply, request } from './fakeRequestsServer'

// Only the intervals are faked: the waiting of Testing Library keeps working on the real timers.
beforeEach(() => {
  setWideScreen(true)
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
})

function server(): FakeRequests {
  return fakeRequests({
    requests: [request(1, 'О-191026-0001')],
    messages: { 1: [message(11, 'Подтекает кран')] },
  })
}

async function open(fake = server()) {
  const view = render(<RequestsTab user={OWNER} search="?request=1" />)
  await screen.findByText('Подтекает кран')
  return { fake, view }
}

const tick = async (ms: number) => {
  await act(async () => {
    vi.advanceTimersByTime(ms)
  })
}

describe('the chat', () => {
  it('takes new messages by itself, every 5 seconds, asking only for the newer ones', async () => {
    const { fake } = await open()
    fake.messages[1].push(reply(12, 'Мастер придёт завтра'))

    await tick(5000)

    expect(await screen.findByText('Мастер придёт завтра')).toBeInTheDocument()
    const calls = fake.calls('GET', /\/messages\?after=/)
    expect(calls[0][0]).toBe('/api/requests/1/messages?after=11')
  })

  it('shows no spinner and keeps the text that is being typed', async () => {
    const { fake, view } = await open()
    fireEvent.change(screen.getByLabelText('Сообщение'), { target: { value: 'Недописанное' } })
    fake.messages[1].push(reply(12, 'Мастер придёт завтра'))

    await tick(5000)
    await screen.findByText('Мастер придёт завтра')

    expect(view.container.querySelector('.ant-spin')).not.toBeInTheDocument()
    expect((screen.getByLabelText('Сообщение') as HTMLTextAreaElement).value).toBe('Недописанное')
  })

  it('does not repeat a message that came by itself and by sending', async () => {
    const { fake } = await open()
    fireEvent.change(screen.getByLabelText('Сообщение'), { target: { value: 'Моё' } })
    fireEvent.click(screen.getByRole('button', { name: /Отправить/ }))
    await within(screen.getByTestId('chat-feed')).findByText('Моё')

    await tick(5000)

    expect(within(screen.getByTestId('chat-feed')).getAllByText('Моё')).toHaveLength(1)
    expect(fake.calls('GET', /\/messages\?after=/).length).toBeGreaterThan(0)
  })

  it('does not scroll down when the reader looks at old messages', async () => {
    const { fake } = await open()
    const feed = screen.getByTestId('chat-feed')
    Object.defineProperty(feed, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(feed, 'clientHeight', { configurable: true, value: 200 })
    Object.defineProperty(feed, 'scrollTop', { configurable: true, writable: true, value: 100 })
    fake.messages[1].push(reply(12, 'Новое'))

    await tick(5000)
    await screen.findByText('Новое')

    expect(feed.scrollTop).toBe(100)
  })

  it('scrolls down when the reader is at the end', async () => {
    const { fake } = await open()
    const feed = screen.getByTestId('chat-feed')
    Object.defineProperty(feed, 'scrollHeight', { configurable: true, value: 1000 })
    Object.defineProperty(feed, 'clientHeight', { configurable: true, value: 200 })
    Object.defineProperty(feed, 'scrollTop', { configurable: true, writable: true, value: 790 })
    fake.messages[1].push(reply(12, 'Новое'))

    await tick(5000)
    await screen.findByText('Новое')

    expect(feed.scrollTop).toBe(1000)
  })

  it('skips a failed poll without a message', async () => {
    const { fake } = await open()
    fake.fail.messages = 500

    await tick(5000)

    expect(screen.queryByText('Не удалось загрузить сообщения')).not.toBeInTheDocument()
    expect(screen.getByText('Подтекает кран')).toBeInTheDocument()
  })
})

describe('the list', () => {
  it('takes new requests by itself, every 15 seconds, and keeps the chosen one', async () => {
    const { fake } = await open()
    fake.requests.unshift(request(2, 'О-191026-0003'))

    await tick(14000)
    expect(screen.queryByText('О-191026-0003')).not.toBeInTheDocument()
    await tick(1000)

    expect(await screen.findByText('О-191026-0003')).toBeInTheDocument()
    expect(screen.getByTestId('request-1')).toHaveAttribute('aria-current', 'true')
  })
})

describe('stopping', () => {
  it('asks for nothing while the page is hidden', async () => {
    const { fake } = await open()
    const before = fake.fetchMock.mock.calls.length
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })

    await tick(60000)

    expect(fake.fetchMock.mock.calls.length).toBe(before)
  })

  it('asks at once when the page becomes visible again', async () => {
    const { fake } = await open()
    fake.messages[1].push(reply(12, 'Пока вас не было'))
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })

    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(await screen.findByText('Пока вас не было')).toBeInTheDocument()
  })

  it('stops when the tab is left', async () => {
    const { fake, view } = await open()
    view.unmount()
    const before = fake.fetchMock.mock.calls.length

    await tick(60000)

    expect(fake.fetchMock.mock.calls.length).toBe(before)
  })

  it('stops asking for the messages of a request after another one is chosen', async () => {
    const fake = fakeRequests({
      requests: [request(2, 'О-191026-0002'), request(1, 'О-191026-0001')],
      messages: { 1: [message(11, 'Подтекает кран')], 2: [message(21, 'Другая переписка')] },
    })
    await open(fake)
    fireEvent.click(screen.getByTestId('request-2'))
    await screen.findByText('Другая переписка')
    const before = fake.calls('GET', /\/api\/requests\/1\/messages\?after=/).length

    await tick(5000)

    expect(fake.calls('GET', /\/api\/requests\/1\/messages\?after=/).length).toBe(before)
    expect(fake.calls('GET', /\/api\/requests\/2\/messages\?after=/).length).toBeGreaterThan(0)
  })
})
