import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account } from './accountApi'
import type { Receipt } from './receiptsApi'
import { ADMIN, OWNER, RESIDENT } from '../testUsers'

const ACCOUNT: Account = {
  number: '000000000001',
  address: 'ул. Тестовая, д. Тест, кв. 1',
  premise: { street: 'ул. Тестовая', house: 'Тест', apartment: '1' },
  owners: [],
  reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true },
  meters: [
    {
      number: 'Тест 1',
      water_type: 'HOT',
      verification_date: null,
      current_period_reading: null,
      input_locked: false,
      readings: [{ value: 245.3, reading_date: '2026-09-25', previous: null }],
    },
  ],
}

const RECEIPTS: Receipt[] = [
  { month: '2026-10', size: 3145744, capital_repair_amount: 1842.5, utilities_amount: 6318.27, published_at: new Date(2026, 9, 18, 15, 0).toISOString() },
  { month: '2026-09', size: 250880, capital_repair_amount: null, utilities_amount: null, published_at: new Date(2026, 9, 19, 15, 0).toISOString() },
]

interface Server {
  receiptCalls: number
  accountStatus: number
  receipts: () => Response
}

// Answers the account and the receipts of the account by the address that is asked.
function fakeServer(overrides: Partial<Server> = {}): Server {
  const server: Server = {
    receiptCalls: 0,
    accountStatus: 200,
    receipts: () => new Response(JSON.stringify({ receipts: RECEIPTS })),
    ...overrides,
  }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input)
    if (url.endsWith('/receipts')) {
      server.receiptCalls += 1
      return server.receipts()
    }
    return server.accountStatus === 200
      ? new Response(JSON.stringify(ACCOUNT))
      : new Response(null, { status: server.accountStatus })
  })
  return server
}

beforeEach(() => window.history.replaceState(null, '', '/?account=000000000001'))
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

const nav = (name: string) => screen.getByRole('button', { name })
const isActive = (name: string) => nav(name).getAttribute('aria-current') === 'page'

async function openPage(search?: string) {
  render(<MeterReadingsPage user={OWNER} today={new Date(2026, 9, 18)} {...(search === undefined ? {} : { search })} />)
  await screen.findByText('Лицевой счёт № 000000000001')
}

describe('tabs', () => {
  it('opens the readings first, with the navigation of the three sections', async () => {
    fakeServer()
    await openPage()

    expect(nav('Показания')).toBeInTheDocument()
    expect(nav('Квитанции')).toBeInTheDocument()
    expect(screen.getByText('Заявки')).toBeInTheDocument()
    expect(isActive('Показания')).toBe(true)
    expect(isActive('Квитанции')).toBe(false)
    expect(screen.getByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
  })

  it('switches to the receipts on a click and writes the tab into the address, keeping the account', async () => {
    const server = fakeServer()
    await openPage()

    fireEvent.click(nav('Квитанции'))

    expect(await screen.findByText('Октябрь 2026')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Квитанции' })).toBeInTheDocument()
    expect(isActive('Квитанции')).toBe(true)
    expect(isActive('Показания')).toBe(false)
    expect(window.location.search).toBe('?account=000000000001&tab=receipts')
    expect(server.receiptCalls).toBe(1)
  })

  it('highlights the open section in the main colour', async () => {
    fakeServer()
    await openPage()
    fireEvent.click(nav('Квитанции'))
    await screen.findByText('Октябрь 2026')

    expect(nav('Квитанции')).toHaveStyle({ color: '#ffb370', fontWeight: '700' })
    expect(nav('Показания')).not.toHaveStyle({ color: '#ffb370' })
  })

  it('returns to the readings and removes the tab from the address, keeping the account', async () => {
    fakeServer()
    await openPage()
    fireEvent.click(nav('Квитанции'))
    await screen.findByText('Октябрь 2026')

    fireEvent.click(nav('Показания'))

    expect(await screen.findByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
    expect(screen.queryByText('Октябрь 2026')).not.toBeInTheDocument()
    expect(window.location.search).toBe('?account=000000000001')
    expect(isActive('Показания')).toBe(true)
  })

  it('opens the receipts at once for the address with tab=receipts', async () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=receipts')
    const server = fakeServer()
    await openPage()

    expect(await screen.findByText('Октябрь 2026')).toBeInTheDocument()
    expect(isActive('Квитанции')).toBe(true)
    expect(screen.queryByRole('heading', { name: 'Показания за октябрь' })).not.toBeInTheDocument()
    expect(server.receiptCalls).toBe(1)
  })

  it('opens the readings for an unknown tab', async () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=unknown')
    const server = fakeServer()
    await openPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
    expect(isActive('Показания')).toBe(true)
    expect(server.receiptCalls).toBe(0)
  })

  it('does not load the receipts while the readings are open', async () => {
    const server = fakeServer()
    await openPage()

    expect(server.receiptCalls).toBe(0)
  })

  it('offers the download of the receipts of the requested account', async () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=receipts')
    fakeServer()
    await openPage()

    expect((await screen.findAllByRole('button', { name: 'Скачать PDF' })).length).toBeGreaterThan(0)
  })

  it('gives an administrator the one tab "Выставить квитанции" and no readings or receipts', async () => {
    window.history.replaceState(null, '', '/?tab=receipts')
    const server = fakeServer()
    render(<MeterReadingsPage user={ADMIN} today={new Date(2026, 9, 18)} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })).toBeInTheDocument()
    expect(isActive('Выставить квитанции')).toBe(true)
    expect(screen.queryByRole('button', { name: 'Показания' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Квитанции' })).not.toBeInTheDocument()
    expect(server.receiptCalls).toBe(0)
  })

  it.each([
    ['owner', OWNER],
    ['resident', RESIDENT],
  ])('has no upload tab for the %s', async (_name, user) => {
    window.history.replaceState(null, '', '/?tab=upload')
    fakeServer()
    render(<MeterReadingsPage user={user} today={new Date(2026, 9, 18)} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Выставить квитанции' })).not.toBeInTheDocument()
    expect(screen.queryByText('Опубликовать квитанцию')).not.toBeInTheDocument()
  })

  it('opens the requests when "Заявки" is clicked, and goes back to the readings', async () => {
    fakeServer()
    await openPage()
    expect(isActive('Заявки')).toBe(false)

    fireEvent.click(nav('Заявки'))

    expect(await screen.findByRole('button', { name: 'Новая заявка' })).toBeInTheDocument()
    expect(isActive('Заявки')).toBe(true)
    expect(isActive('Показания')).toBe(false)
    expect(window.location.search).toBe('?account=000000000001&tab=requests')

    fireEvent.click(nav('Показания'))
    expect(window.location.search).toBe('?account=000000000001')
  })

  it('opens the requests of an owner by the address', async () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=requests')
    fakeServer()
    await openPage()

    expect(await screen.findByRole('button', { name: 'Новая заявка' })).toBeInTheDocument()
    expect(isActive('Заявки')).toBe(true)
  })

  it('gives an administrator the tab "Заявки" without the button of a new request', async () => {
    fakeServer()
    render(<MeterReadingsPage user={ADMIN} today={new Date(2026, 9, 18)} />)
    await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })

    fireEvent.click(nav('Заявки'))

    expect(await screen.findByText('Заявок пока нет')).toBeInTheDocument()
    expect(isActive('Заявки')).toBe(true)
    expect(window.location.search).toContain('tab=requests')
    expect(screen.queryByRole('button', { name: 'Новая заявка' })).not.toBeInTheDocument()

    fireEvent.click(nav('Выставить квитанции'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })).toBeInTheDocument()
    expect(window.location.search).not.toContain('tab=')
  })

  it('has no tab "Заявки" for a resident, and the address with tab=requests opens the readings', async () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=requests')
    fakeServer()
    render(<MeterReadingsPage user={RESIDENT} today={new Date(2026, 9, 18)} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Заявки' })).not.toBeInTheDocument()
  })

  it('shows the same header on both tabs', async () => {
    fakeServer()
    await openPage()
    fireEvent.click(nav('Квитанции'))
    await screen.findByText('Октябрь 2026')

    expect(screen.getByText('ТСЖ «Комфорт»')).toBeInTheDocument()
    expect(screen.getByText('ул. Тестовая, д. Тест, кв. 1')).toBeInTheDocument()
    expect(screen.getByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
  })

  it('does not ask for the receipts when the account is not found', async () => {
    window.history.replaceState(null, '', '/?account=999&tab=receipts')
    const server = fakeServer({ accountStatus: 404 })
    render(<MeterReadingsPage user={OWNER} today={new Date(2026, 9, 18)} />)

    expect(await screen.findByText('Лицевой счёт не найден')).toBeInTheDocument()
    expect(server.receiptCalls).toBe(0)
    expect(screen.queryByRole('button', { name: 'Квитанции' })).not.toBeInTheDocument()
  })

  it('keeps the header and the readings working when the receipts do not load', async () => {
    fakeServer({ receipts: () => new Response(null, { status: 500 }) })
    await openPage()

    fireEvent.click(nav('Квитанции'))

    expect(await screen.findByText('Не удалось загрузить квитанции')).toBeInTheDocument()
    expect(screen.getByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
    fireEvent.click(nav('Показания'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Показания за октябрь' })).toBeInTheDocument()
    expect(within(screen.getByRole('heading', { name: 'Тест 1' }).closest('.ant-card') as HTMLElement).getByLabelText('Горячая вода, м³')).toBeEnabled()
  })

  it('shows the list again after the readings tab (the list is loaded when the tab opens)', async () => {
    const server = fakeServer()
    await openPage()
    fireEvent.click(nav('Квитанции'))
    await screen.findByText('Октябрь 2026')
    fireEvent.click(nav('Показания'))
    await screen.findByRole('heading', { name: 'Показания за октябрь' })

    fireEvent.click(nav('Квитанции'))

    expect(await screen.findByText('Октябрь 2026')).toBeInTheDocument()
    await waitFor(() => expect(server.receiptCalls).toBe(2))
  })
})
