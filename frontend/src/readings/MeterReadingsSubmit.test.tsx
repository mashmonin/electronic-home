import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account, AccountMeter } from './accountApi'

function meter(number: string, type: 'HOT' | 'COLD', ...values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: null,
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

// Saves the given values as the newest readings of the meters, like the real backend would.
function saved(body: Posted, server: FakeServer) {
  server.account = {
    ...server.account,
    meters: server.account.meters.map((m) => {
      const item = body.readings.find((r) => r.meter_number === m.number)
      if (!item) return m
      const previous = m.readings[0]
      return {
        ...m,
        readings: [
          { value: item.value, reading_date: '2026-10-18', previous: previous && { value: previous.value, reading_date: previous.reading_date } },
          ...m.readings,
        ],
      }
    }),
  }
  return new Response(JSON.stringify({ readings: [] }), { status: 201 })
}

function errorsResponse(status: number, errors: { meter_number: string | null; code: string }[]) {
  return new Response(JSON.stringify({ errors }), { status })
}

afterEach(() => vi.restoreAllMocks())

async function renderPage() {
  render(<MeterReadingsPage today={new Date(2026, 9, 18)} search="?account=000000000001" />)
  await screen.findByText('Ввод показаний')
}

const hot = () => screen.getAllByLabelText('Горячая вода, м³')[0]
const cold = () => screen.getAllByLabelText('Холодная вода, м³')[0]
const send = () => fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
const type = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } })

describe('submitting readings', () => {
  it('sends the meter numbers and refreshes the previous readings after saving', async () => {
    const server = fakeServer(saved)
    await renderPage()
    expect(screen.getByText('Пред. показание: 245.300 м³')).toBeInTheDocument()

    type(hot(), '250,125')
    send()

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(server.posts).toEqual([{ readings: [{ meter_number: 'Тест 1', value: 250.125 }] }])
    expect(await screen.findByText('Пред. показание: 250.125 м³')).toBeInTheDocument()
    expect(screen.queryByText('Пред. показание: 245.300 м³')).not.toBeInTheDocument()
    expect(hot()).toHaveValue('')
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
  })

  it('does not show the full-screen spinner while the data is being refreshed', async () => {
    const server = fakeServer(saved)
    const { container } = render(<MeterReadingsPage search="?account=000000000001" />)
    await screen.findByText('Ввод показаний')
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

  it('sends both meters of a riser at once', async () => {
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

  it('accepts a new reading higher than the one just saved and rejects a lower one', async () => {
    fakeServer(saved)
    await renderPage()
    type(hot(), '250')
    send()
    await screen.findByText('Пред. показание: 250.000 м³')

    type(hot(), '249')
    send()
    expect(await screen.findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
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
    expect(await screen.findByText('Пред. показание: 300.000 м³')).toBeInTheDocument()
    expect(server.posts).toHaveLength(1)
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

describe('fields without a meter', () => {
  it('blocks sending when a value is entered in a field of a manually added riser', async () => {
    const server = fakeServer(saved)
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: '+ Добавить стояк' }))
    type(screen.getAllByLabelText('Горячая вода, м³')[1], '10')
    send()

    expect(await screen.findByText('Для этого поля нет счётчика')).toBeInTheDocument()
    expect(server.posts).toHaveLength(0)
  })

  it('does not let an empty field without a meter get in the way of the others', async () => {
    const account = { ...ACCOUNT, meters: [meter('Г-1', 'HOT', 10)] }
    const server = fakeServer(saved, account)
    await renderPage()
    type(hot(), '11')
    send()

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(server.posts[0].readings).toEqual([{ meter_number: 'Г-1', value: 11 }])
  })

  it('blocks a value in the field of an unpaired riser side', async () => {
    const account = { ...ACCOUNT, meters: [meter('Г-1', 'HOT', 10)] }
    const server = fakeServer(saved, account)
    await renderPage()
    type(cold(), '5')
    send()

    expect(await screen.findByText('Для этого поля нет счётчика')).toBeInTheDocument()
    expect(server.posts).toHaveLength(0)
  })
})
