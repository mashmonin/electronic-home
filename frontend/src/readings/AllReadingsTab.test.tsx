import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADMIN, OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import MeterReadingsPage from './MeterReadingsPage'
import type { OverviewApartment, OverviewMonth, OverviewPeriod } from './readingsOverviewApi'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

const PERIODS: OverviewPeriod[] = [
  { month: '2026-10', start: '2026-09-26', end: '2026-10-25' },
  { month: '2026-09', start: '2026-08-26', end: '2026-09-25' },
  { month: '2026-08', start: '2026-07-26', end: '2026-08-25' },
]

function flat(apartment: string, submitted: number, total: number, meters: OverviewApartment['meters']): OverviewApartment {
  return { account_number: `0000000000${apartment.padStart(2, '0')}`, apartment, address: `ул. Тестовая, д. 1, кв. ${apartment}`, submitted, total, meters }
}

const reading = (value: number, date: string, previous: number | null) => ({
  value,
  reading_date: date,
  previous_value: previous,
  consumption: previous === null ? null : Number((value - previous).toFixed(3)),
})

function month(key: string, apartments: OverviewApartment[]): OverviewMonth {
  return { month: key, start: '2026-09-26', end: '2026-10-25', apartments }
}

const MONTHS: Record<string, OverviewMonth> = {
  '2026-10': month('2026-10', [
    flat('1', 2, 2, [
      { number: 'Тест 1', water_type: 'HOT', reading: reading(245.3, '2026-09-25', 230) },
      { number: 'Тест 2', water_type: 'COLD', reading: reading(512.1, '2026-10-02', null) },
    ]),
    flat('2', 1, 2, [
      { number: 'А', water_type: 'HOT', reading: reading(10, '2026-10-03', 5) },
      { number: 'Б', water_type: 'COLD', reading: null },
    ]),
    flat('10', 0, 1, [{ number: 'В', water_type: 'HOT', reading: null }]),
  ]),
  '2026-09': month('2026-09', [flat('1', 1, 1, [{ number: 'Тест 1', water_type: 'HOT', reading: reading(230, '2026-08-25', null) }])]),
  '2026-08': month('2026-08', []),
}

interface Fake {
  calls: string[]
  failMonth: Set<string>
  failPeriods: boolean
}

function fakeServer(): Fake {
  const fake: Fake = { calls: [], failMonth: new Set(), failPeriods: false }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    fake.calls.push(url)
    if (url === '/api/readings/periods') {
      return fake.failPeriods ? new Response(null, { status: 500 }) : new Response(JSON.stringify({ periods: PERIODS }))
    }
    const requested = /^\/api\/readings\?month=(.+)$/.exec(url)
    if (requested) {
      const key = decodeURIComponent(requested[1])
      return fake.failMonth.has(key) ? new Response(null, { status: 500 }) : new Response(JSON.stringify(MONTHS[key]))
    }
    return new Response(null, { status: 404 })
  })
  return fake
}

async function open(search = '?tab=all-readings') {
  window.history.replaceState(null, '', `/${search}`)
  render(<MeterReadingsPage user={ADMIN} search={search} />)
  await screen.findByRole('heading', { level: 1, name: 'Все показания' })
}

const monthHeader = (name: string) => screen.getByText(name)
const monthCalls = (fake: Fake) => fake.calls.filter((url) => url.startsWith('/api/readings?month='))

describe('the tab "Все показания"', () => {
  it('has its item in the navigation of the administrator between the upload and the requests', async () => {
    fakeServer()
    render(<MeterReadingsPage user={ADMIN} search="" />)
    await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })

    const items = screen.getAllByRole('button').map((button) => button.textContent)
    const upload = items.indexOf('Выставить квитанции')
    expect(items.indexOf('Все показания')).toBe(upload + 1)
    expect(items.indexOf('Заявки')).toBe(upload + 2)
  })

  it('opens from the item and writes the tab into the address', async () => {
    fakeServer()
    render(<MeterReadingsPage user={ADMIN} search="" />)
    await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })

    fireEvent.click(screen.getByRole('button', { name: 'Все показания' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Все показания' })).toBeInTheDocument()
    expect(window.location.search).toBe('?tab=all-readings')
  })

  it('lists the months newest first and opens the first one', async () => {
    const fake = fakeServer()
    await open()

    await screen.findByText('Октябрь 2026')
    const headers = Array.from((document.querySelector('.ant-collapse') as HTMLElement).querySelectorAll(':scope > .ant-collapse-item > .ant-collapse-header')).map(
      (node) => node.textContent,
    )
    expect(headers).toEqual(['Октябрь 2026', 'Сентябрь 2026', 'Август 2026'])
    await screen.findByText('кв. 1')
    expect(monthCalls(fake)).toEqual(['/api/readings?month=2026-10'])
  })

  it('shows an error with a retry when the months cannot be loaded', async () => {
    const fake = fakeServer()
    fake.failPeriods = true
    await open()

    expect(await screen.findByText('Не удалось загрузить показания')).toBeInTheDocument()
    fake.failPeriods = false
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(await screen.findByText('Октябрь 2026')).toBeInTheDocument()
  })
})

describe('the months', () => {
  it('load when opened, once', async () => {
    const fake = fakeServer()
    await open()
    await screen.findByText('кв. 1')

    fireEvent.click(monthHeader('Сентябрь 2026'))
    await waitFor(() => expect(monthCalls(fake)).toHaveLength(2))
    expect(monthCalls(fake)[1]).toBe('/api/readings?month=2026-09')
    fireEvent.click(monthHeader('Сентябрь 2026'))
    fireEvent.click(monthHeader('Сентябрь 2026'))

    expect(monthCalls(fake)).toHaveLength(2)
  })

  it('say when a month has no apartments with meters', async () => {
    fakeServer()
    await open()
    await screen.findByText('кв. 1')

    fireEvent.click(monthHeader('Август 2026'))

    expect(await screen.findByText('Нет квартир со счётчиками')).toBeInTheDocument()
  })

  it('show an error of their own with a retry', async () => {
    const fake = fakeServer()
    fake.failMonth.add('2026-10')
    await open()

    expect(await screen.findByText('Не удалось загрузить месяц')).toBeInTheDocument()
    fake.failMonth.clear()
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(await screen.findByText('кв. 1')).toBeInTheDocument()
  })
})

describe('the apartments', () => {
  it('are marked as handed in, partly handed in or not handed in', async () => {
    fakeServer()
    await open()

    expect(await screen.findByText('Сдано 2 из 2')).toBeInTheDocument()
    expect(screen.getByText('Сдано 1 из 2')).toBeInTheDocument()
    expect(screen.getAllByText('Не передано')).toHaveLength(1)
  })

  it('show the table of the meters when opened', async () => {
    fakeServer()
    await open()
    fireEvent.click(await screen.findByText('кв. 1'))

    const row = (await screen.findByText('Тест 1')).closest('tr') as HTMLElement
    expect(within(row).getByText('Горячая')).toBeInTheDocument()
    expect(within(row).getByText('245.300')).toBeInTheDocument()
    expect(within(row).getByText('25.09.2026')).toBeInTheDocument()
    expect(within(row).getByText('15.300')).toBeInTheDocument()
  })

  it('show a dash instead of the consumption of the first reading', async () => {
    fakeServer()
    await open()
    fireEvent.click(await screen.findByText('кв. 1'))

    const row = (await screen.findByText('Тест 2')).closest('tr') as HTMLElement
    expect(within(row).getByText('Холодная')).toBeInTheDocument()
    expect(within(row).getByText('512.100')).toBeInTheDocument()
    expect(within(row).getByText('—')).toBeInTheDocument()
  })

  it('say "Не передано" in the row of a meter without a reading', async () => {
    fakeServer()
    await open()
    fireEvent.click(await screen.findByText('кв. 2'))

    const row = (await screen.findByText('Б')).closest('tr') as HTMLElement
    expect(within(row).getByText('Не передано')).toBeInTheDocument()
    expect(within(row).getAllByText('—')).toHaveLength(2)
  })
})

describe('the search by the apartment', () => {
  it('keeps the apartments with the fragment and asks for nothing', async () => {
    const fake = fakeServer()
    await open()
    await screen.findByText('кв. 10')
    const before = fake.calls.length

    fireEvent.change(screen.getByLabelText('Поиск по квартире'), { target: { value: '1' } })

    expect(screen.getByText('кв. 1')).toBeInTheDocument()
    expect(screen.getByText('кв. 10')).toBeInTheDocument()
    expect(screen.queryByText('кв. 2')).not.toBeInTheDocument()
    expect(fake.calls).toHaveLength(before)
  })

  it('says that nothing was found and shows everything again when cleared', async () => {
    fakeServer()
    await open()
    await screen.findByText('кв. 10')

    fireEvent.change(screen.getByLabelText('Поиск по квартире'), { target: { value: '77' } })
    expect(await screen.findByText('Ничего не найдено по запросу «77»')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Поиск по квартире'), { target: { value: '' } })
    expect(await screen.findByText('кв. 2')).toBeInTheDocument()
  })
})

describe('for an owner', () => {
  it('has no such item and the address opens the usual screen', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ number: '000000000001', address: 'a', premise: { street: 's', house: 'h', apartment: '1' }, owners: [], reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true }, meters: [] })))
    render(<MeterReadingsPage user={OWNER} search="?tab=all-readings" />)

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button', { name: 'Все показания' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Все показания' })).not.toBeInTheDocument()
  })
})
