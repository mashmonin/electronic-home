import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReceiptsTab from './ReceiptsTab'
import type { Receipt } from './receiptsApi'

afterEach(() => vi.restoreAllMocks())

const NBSP = ' '
const published = (day: number) => new Date(2026, 9, day, 15, 0).toISOString()

const RECEIPTS: Receipt[] = [
  { month: '2026-10', size: 3145744, capital_repair_amount: 1842.5, utilities_amount: 6318.27, published_at: published(18) },
  { month: '2026-09', size: 250880, capital_repair_amount: null, utilities_amount: null, published_at: published(19) },
  { month: '2026-08', size: 512, capital_repair_amount: 0, utilities_amount: 1234567.8, published_at: published(20) },
]

function mockList(receipts: Receipt[]) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ receipts })))
}

// The header of a panel is a role="tab" element with aria-expanded (antd renders the accordion as a tab list).
const panel = (month: string) => screen.getByText(month).closest('[role="tab"]') as HTMLElement
const isOpen = (month: string) => panel(month).getAttribute('aria-expanded') === 'true'

describe('ReceiptsTab as an accordion', () => {
  it('shows a panel per month, newest first, with the newest one open', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)

    await screen.findByText('Октябрь 2026')
    const headers = Array.from(document.querySelectorAll('[role="tab"][aria-expanded]'))
    expect(headers.map((h) => h.textContent)).toEqual(['Октябрь 2026', 'Сентябрь 2026', 'Август 2026'])
    expect(isOpen('Октябрь 2026')).toBe(true)
    expect(isOpen('Сентябрь 2026')).toBe(false)
    expect(isOpen('Август 2026')).toBe(false)
  })

  it('shows the two amounts inside the open panel', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)

    const capital = await screen.findByText('Капитальный ремонт')
    const utilities = screen.getByText('Коммунальные услуги')
    expect(capital.parentElement?.textContent).toBe(`Капитальный ремонт1${NBSP}842,50${NBSP}₽`)
    expect(utilities.parentElement?.textContent).toBe(`Коммунальные услуги6${NBSP}318,27${NBSP}₽`)
  })

  it('keeps one panel open: opening another closes the first', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')

    fireEvent.click(panel('Сентябрь 2026'))

    expect(isOpen('Сентябрь 2026')).toBe(true)
    expect(isOpen('Октябрь 2026')).toBe(false)
  })

  it('shows a dash when the amounts are not known', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')

    fireEvent.click(panel('Сентябрь 2026'))

    const rows = (await screen.findAllByText('—')).map((dash) => dash.closest('.ant-flex')?.textContent)
    expect(rows).toEqual(['Капитальный ремонт—', 'Коммунальные услуги—'])
    expect(screen.getAllByRole('link', { name: 'Скачать PDF' }).length).toBeGreaterThan(0)
  })

  it('shows zero and big amounts', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')

    fireEvent.click(panel('Август 2026'))

    // getByText collapses non-breaking spaces of the page but not of the text it is given, so match the exact text
    const amount = (text: string) => screen.findByText((_, el) => el?.tagName === 'STRONG' && el.textContent === text)
    expect(await amount(`0,00${NBSP}₽`)).toBeInTheDocument()
    expect(await amount(`1${NBSP}234${NBSP}567,80${NBSP}₽`)).toBeInTheDocument()
  })

  it('does not show the size of the file or the date of publication', async () => {
    mockList(RECEIPTS)
    const { container } = render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')
    fireEvent.click(panel('Сентябрь 2026'))
    fireEvent.click(panel('Август 2026'))

    const text = container.textContent ?? ''
    expect(text).not.toMatch(/\d\s?(Б|КБ|МБ)\b/)
    expect(text).not.toMatch(/\d{2}\.\d{2}\.\d{4}/)
    expect(text).not.toMatch(/опубликован/i)
  })

  it('puts the download link inside the panel with the address of its month', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')

    const link = screen.getByRole('link', { name: 'Скачать PDF' })
    expect(link).toHaveAttribute('href', '/api/accounts/000000000001/receipts/2026-10')
    expect(link).toHaveAttribute('download')

    fireEvent.click(panel('Сентябрь 2026'))
    const links = await screen.findAllByRole('link', { name: 'Скачать PDF' })
    expect(links.map((l) => l.getAttribute('href'))).toContain('/api/accounts/000000000001/receipts/2026-09')
  })

  it('has the title "Квитанции" as the heading of the page, aligned left', async () => {
    mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000001" />)

    const title = await screen.findByRole('heading', { level: 1, name: 'Квитанции' })
    expect(title).toHaveStyle({ fontSize: '26px' })
    expect(title).toHaveStyle({ textAlign: 'left' })
  })

  it('asks the API for the receipts of the account only once', async () => {
    const fetchMock = mockList(RECEIPTS)
    render(<ReceiptsTab accountNumber="000000000002" />)
    await screen.findByText('Октябрь 2026')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000002/receipts')
  })
})

describe('ReceiptsTab states', () => {
  it('shows a spinner while the list is loading', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))
    const { container } = render(<ReceiptsTab accountNumber="000000000001" />)

    expect(container.querySelector('.ant-spin')).toBeInTheDocument()
    expect(screen.queryByText('Квитанции пока не опубликованы')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Скачать PDF' })).not.toBeInTheDocument()
  })

  it('says so when there are no receipts', async () => {
    mockList([])
    render(<ReceiptsTab accountNumber="000000000001" />)

    expect(await screen.findByText('Квитанции пока не опубликованы')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Скачать PDF' })).not.toBeInTheDocument()
    expect(screen.queryByText('Капитальный ремонт')).not.toBeInTheDocument()
  })

  it('shows an error with a retry button, and the retry loads the list', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue(new Response(JSON.stringify({ receipts: RECEIPTS })))
    render(<ReceiptsTab accountNumber="000000000001" />)

    expect(await screen.findByText('Не удалось загрузить квитанции')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByText('Октябрь 2026')).toBeInTheDocument()
    expect(screen.queryByText('Не удалось загрузить квитанции')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('shows an error for a server error answer', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 500 }))
    render(<ReceiptsTab accountNumber="000000000001" />)

    expect(await screen.findByText('Не удалось загрузить квитанции')).toBeInTheDocument()
  })

  it('has no controls to publish, replace or delete a receipt', async () => {
    mockList(RECEIPTS)
    const { container } = render(<ReceiptsTab accountNumber="000000000001" />)
    await screen.findByText('Октябрь 2026')

    expect(container.querySelector('input[type="file"]')).toBeNull()
    for (const name of [/Загрузить/, /Заменить/, /Удалить/, /Опубликовать/]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(within(container).queryByText(/Скачать PDF/)).toBeInTheDocument()
  })
})
