import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account, AccountMeter } from './accountApi'
import { ADMIN } from '../testUsers'

function meter(number: string, type: 'HOT' | 'COLD', ...values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: null,
    current_period_reading: null,
    input_locked: false,
    readings: values.map((value, index) => ({
      value,
      reading_date: `2026-0${9 - index}-25`,
      previous: index + 1 < values.length ? { value: values[index + 1], reading_date: `2026-0${8 - index}-25` } : null,
    })),
  }
}

const ACCOUNT: Account = {
  number: '000000000001',
  address: 'ул. Тестовая, д. Тест, кв. 1',
  premise: { street: 'ул. Тестовая', house: 'Тест', apartment: '1' },
  owners: [],
  reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true },
  meters: [meter('Тест 1', 'HOT', 245.3, 230), meter('Тест 2', 'COLD', 512.1, 498.5)],
}

interface Posted {
  readings: { meter_number: string; value: number }[]
}

interface FakeServer {
  account: Account
  posts: Posted[]
  gets: number
  failGets: boolean
}

type PostHandler = (body: Posted, server: FakeServer) => Response | Promise<Response>

// Answers GET with the server's current account and POST with the given handler.
function fakeServer(post: PostHandler, account: Account = ACCOUNT): FakeServer {
  const server: FakeServer = { account, posts: [], gets: 0, failGets: false }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as Posted
      server.posts.push(body)
      return post(body, server)
    }
    server.gets += 1
    if (server.failGets && server.gets > 1) throw new Error('network')
    return new Response(JSON.stringify(server.account))
  })
  return server
}

// Saves the given values the way the backend does: the meter gets a reading of the current period and is locked.
function saved(body: Posted, server: FakeServer) {
  const meters = server.account.meters.map((m) => {
    const item = body.readings.find((r) => r.meter_number === m.number)
    if (!item) return m
    const head = m.readings[0]
    const reading = {
      value: item.value,
      reading_date: '2026-10-18',
      previous: head && { value: head.value, reading_date: head.reading_date },
    }
    return { ...m, readings: [reading, ...m.readings], current_period_reading: reading, input_locked: true }
  })
  server.account = {
    ...server.account,
    reading_period: { ...server.account.reading_period, all_submitted: meters.every((m) => m.current_period_reading !== null) },
    meters,
  }
  return new Response(JSON.stringify({ readings: [] }), { status: 201 })
}

function errorsResponse(status: number, errors: { meter_number: string | null; code: string }[]) {
  return new Response(JSON.stringify({ errors }), { status })
}

afterEach(() => vi.restoreAllMocks())

async function renderPage() {
  render(<MeterReadingsPage user={ADMIN} today={new Date(2026, 9, 18)} search="?account=000000000001" />)
  await screen.findByRole('heading', { level: 1 })
}

const card = (name: string) => screen.getByRole('heading', { name }).closest('.ant-card') as HTMLElement
const hot = () => within(card('Тест 1')).getByLabelText('Горячая вода, м³')
const cold = () => within(card('Тест 2')).getByLabelText('Холодная вода, м³')
const send = () => fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
const type = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } })

describe('submitting readings', () => {
  it('sends the meter numbers and shows the submitted reading as a locked one after saving', async () => {
    const server = fakeServer(saved)
    await renderPage()
    expect(screen.getByText('Передайте показания до 25 октября')).toBeInTheDocument()

    type(hot(), '250,125')
    send()

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(server.posts).toEqual([{ readings: [{ meter_number: 'Тест 1', value: 250.125 }] }])
    await waitFor(() => expect(hot()).toBeDisabled())
    expect(hot()).toHaveValue('250.125')
    expect(within(card('Тест 1')).getByText('Предыдущее показание: 245.300 м³')).toBeInTheDocument()
    expect(cold()).toBeEnabled()
    expect(screen.getByRole('heading', { name: 'Тест 2' })).toBeInTheDocument()
  })

  it('switches the caption to "submitted" and disables the buttons when everything is sent', async () => {
    fakeServer(saved)
    await renderPage()
    type(hot(), '250')
    type(cold(), '520')
    send()

    expect(await screen.findByText('Показания сданы, новые можно будет отправить после 25 октября')).toBeInTheDocument()
    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(hot()).toBeDisabled()
    expect(cold()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Отправить показания' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Сбросить форму' })).toBeDisabled()
  })

  it('says that a part is submitted when one meter was sent', async () => {
    fakeServer(saved)
    await renderPage()
    type(hot(), '250')
    send()

    expect(await screen.findByText('Часть показаний сдана. Передайте остальные до 25 октября')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Отправить показания' })).toBeEnabled()
  })

  it('does not show the full-screen spinner while the data is being refreshed', async () => {
    const server = fakeServer(saved)
    const { container } = render(<MeterReadingsPage user={ADMIN} search="?account=000000000001" />)
    await screen.findByRole('heading', { level: 1 })
    type(hot(), '250')
    send()
    await screen.findByText('Показания переданы')
    expect(container.querySelector('.ant-spin-spinning')).not.toBeInTheDocument()
    await waitFor(() => expect(server.gets).toBe(2))
  })

  it('sends only the filled fields', async () => {
    const server = fakeServer(saved)
    await renderPage()
    type(cold(), '520')
    send()
    await screen.findByText('Показания переданы')
    expect(server.posts[0].readings).toEqual([{ meter_number: 'Тест 2', value: 520 }])
  })

  it('sends both meters at once', async () => {
    const server = fakeServer(saved)
    await renderPage()
    type(hot(), '250')
    type(cold(), '520')
    send()
    await screen.findByText('Показания переданы')
    expect(server.posts[0].readings).toEqual([
      { meter_number: 'Тест 1', value: 250 },
      { meter_number: 'Тест 2', value: 520 },
    ])
  })

  it('does not send a meter that is already locked', async () => {
    const account: Account = {
      ...ACCOUNT,
      meters: [
        {
          ...meter('Тест 1', 'HOT', 250, 245.3),
          current_period_reading: { value: 250, reading_date: '2026-10-18', previous: { value: 245.3, reading_date: '2026-09-25' } },
          input_locked: true,
        },
        meter('Тест 2', 'COLD', 512.1, 498.5),
      ],
    }
    const server = fakeServer(saved, account)
    await renderPage()
    type(cold(), '520')
    send()

    await screen.findByText('Показания переданы')
    expect(server.posts[0].readings).toEqual([{ meter_number: 'Тест 2', value: 520 }])
  })

  it('checks the other meter against its own previous reading after one was saved', async () => {
    fakeServer(saved)
    await renderPage()
    type(hot(), '250')
    send()
    await screen.findByText('Показания переданы')

    type(cold(), '512.099')
    send()
    expect(await within(card('Тест 2')).findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
  })

  it('warns when the readings are saved but the data cannot be reloaded', async () => {
    const server = fakeServer((body, s) => {
      s.failGets = true
      return saved(body, s)
    })
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Показания сохранены, но данные на экране не обновились. Обновите страницу')).toBeInTheDocument()
    expect(server.posts).toHaveLength(1)
  })
})

describe('server errors', () => {
  it('shows a rejected value at its field, keeps the input and refreshes the previous reading', async () => {
    const server = fakeServer((_body, s) => {
      // somebody else saved 300 in the meantime
      s.account = { ...s.account, meters: [meter('Тест 1', 'HOT', 300, 245.3), s.account.meters[1]] }
      return errorsResponse(400, [{ meter_number: 'Тест 1', code: 'VALUE_LESS_THAN_PREVIOUS' }])
    })
    await renderPage()
    type(hot(), '250')
    send()

    expect(await screen.findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
    expect(hot()).toHaveValue('250')
    expect(await screen.findByText('Предыдущее показание: 300.000 м³')).toBeInTheDocument()
    expect(server.posts).toHaveLength(1)
  })

  it('warns that the readings are already submitted and shows them locked after the reload', async () => {
    fakeServer((_body, s) => {
      // somebody else submitted the readings of this period in the meantime
      saved({ readings: [{ meter_number: 'Тест 1', value: 260 }] }, s)
      return errorsResponse(400, [{ meter_number: 'Тест 1', code: 'ALREADY_SUBMITTED' }])
    })
    await renderPage()
    type(hot(), '250')
    send()

    expect(await screen.findByText('Показания за этот период уже сданы')).toBeInTheDocument()
    await waitFor(() => expect(hot()).toBeDisabled())
    expect(hot()).toHaveValue('260.000')
  })

  it('shows the message of INVALID_VALUE at the field', async () => {
    fakeServer(() => errorsResponse(400, [{ meter_number: 'Тест 2', code: 'INVALID_VALUE' }]))
    await renderPage()
    type(cold(), '520')
    send()
    expect(await screen.findByText('Введите число, не более трёх знаков после запятой')).toBeInTheDocument()
    expect(cold()).toHaveValue('520')
  })

  it('shows the message of METER_NOT_FOUND at the field', async () => {
    fakeServer(() => errorsResponse(400, [{ meter_number: 'Тест 1', code: 'METER_NOT_FOUND' }]))
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Счётчик не найден')).toBeInTheDocument()
  })

  it('reports an error that cannot be put at a field as a general message', async () => {
    fakeServer(() => errorsResponse(400, [{ meter_number: null, code: 'EMPTY_REQUEST' }]))
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Показания не приняты сервером')).toBeInTheDocument()
  })

  it('tells about a conflict, keeps the input and reloads the data', async () => {
    const server = fakeServer(() => new Response(null, { status: 409 }))
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Показания не сохранены из-за одновременного изменения. Повторите отправку')).toBeInTheDocument()
    expect(hot()).toHaveValue('250')
    await waitFor(() => expect(server.gets).toBe(2))
  })

  it('tells when the account is not found and keeps the input', async () => {
    fakeServer(() => new Response(null, { status: 404 }))
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Лицевой счёт не найден')).toBeInTheDocument()
    expect(hot()).toHaveValue('250')
  })

  it('tells about a network error and keeps the input', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      if (init?.method === 'POST') throw new Error('network')
      return new Response(JSON.stringify(ACCOUNT))
    })
    await renderPage()
    type(hot(), '250')
    send()
    expect(await screen.findByText('Не удалось отправить показания, попробуйте позже')).toBeInTheDocument()
    expect(hot()).toHaveValue('250')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('several meters of the same type', () => {
  const twoHot: Account = { ...ACCOUNT, meters: [meter('Г-1', 'HOT', 10), meter('Г-2', 'HOT', 20)] }
  const field = (name: string) => within(card(name)).getByLabelText('Горячая вода, м³')

  it('sends the reading of the card it was entered in', async () => {
    const server = fakeServer(saved, twoHot)
    await renderPage()
    type(field('Г-2'), '25')
    send()

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(server.posts[0].readings).toEqual([{ meter_number: 'Г-2', value: 25 }])
  })

  it('locks only the card that was saved', async () => {
    fakeServer(saved, twoHot)
    await renderPage()
    type(field('Г-1'), '11')
    send()

    await waitFor(() => expect(field('Г-1')).toBeDisabled())
    expect(field('Г-1')).toHaveValue('11.000')
    expect(field('Г-2')).toBeEnabled()
    expect(within(card('Г-2')).getByText('Предыдущее показание: 20.000 м³')).toBeInTheDocument()
  })

  it('shows a server error only in the card of its meter', async () => {
    fakeServer(() => errorsResponse(400, [{ meter_number: 'Г-2', code: 'INVALID_VALUE' }]), twoHot)
    await renderPage()
    type(field('Г-1'), '11')
    type(field('Г-2'), '25')
    send()

    expect(await within(card('Г-2')).findByText('Введите число, не более трёх знаков после запятой')).toBeInTheDocument()
    expect(within(card('Г-1')).queryByText('Введите число, не более трёх знаков после запятой')).not.toBeInTheDocument()
  })
})
