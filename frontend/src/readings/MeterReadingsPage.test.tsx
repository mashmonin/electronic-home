import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account } from './accountApi'
import * as api from './readingsApi'
import { validateReading } from './validation'

const ACCOUNT: Account = {
  number: '000000000001',
  address: 'ул. Тестовая, д. Тест, кв. 1',
  premise: { street: 'ул. Тестовая', house: 'Тест', apartment: '1' },
  owners: [{ full_name: 'Тестовая Теста Тестовна' }, { full_name: 'Тестовый Тест Тестович' }],
}

function mockAccount(account: Account = ACCOUNT) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(account)))
}

beforeEach(() => {
  mockAccount()
})
afterEach(() => vi.restoreAllMocks())

async function renderPage(search = '?account=000000000001') {
  render(<MeterReadingsPage today={new Date(2026, 7, 10)} search={search} />)
  await screen.findByText('Ввод показаний')
}

describe('MeterReadingsPage', () => {
  it('shows header data from the account response, period, risers and contacts', async () => {
    await renderPage()
    expect(screen.getByText('ТСЖ «Комфорт»')).toBeInTheDocument()
    expect(screen.getByText('Показания')).toBeInTheDocument()
    expect(screen.getByText('Заявки')).toBeInTheDocument()
    expect(screen.getByText('ул. Тестовая, д. Тест, кв. 1')).toBeInTheDocument()
    expect(screen.getByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
    expect(screen.getByText(/Август 2026 · Передайте показания до 25 числа/)).toBeInTheDocument()
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
    expect(screen.getByText('Стояк 2')).toBeInTheDocument()
    expect(screen.getAllByPlaceholderText('0.000')).toHaveLength(4)
    expect(screen.getByText('Пред. показание: 245.300 м³')).toBeInTheDocument()
    expect(screen.getAllByText('Счётчик № —')).toHaveLength(2)
    expect(screen.getByText('Нужна помощь?')).toBeInTheDocument()
    expect(screen.getByText('Председатель правления ТСЖ')).toBeInTheDocument()
    expect(screen.getByText('+7 (999) 123-45-67')).toBeInTheDocument()
  })

  it('does not offer to switch the apartment', async () => {
    await renderPage()
    expect(screen.queryByRole('button', { name: 'Сменить адрес' })).not.toBeInTheDocument()
  })

  it('lists co-owners in a popover on the profile icon', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Профиль' }))
    expect(await screen.findByText('Тестовая Теста Тестовна')).toBeInTheDocument()
    expect(screen.getByText('Тестовый Тест Тестович')).toBeInTheDocument()
  })

  it('tells when the account has no co-owners', async () => {
    mockAccount({ ...ACCOUNT, owners: [] })
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Профиль' }))
    expect(await screen.findByText('Совладельцы не указаны')).toBeInTheDocument()
  })

  it('rejects a reading lower than the previous one', async () => {
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
  })

  it('adds a riser', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: '+ Добавить стояк' }))
    expect(screen.getByText('Стояк 3')).toBeInTheDocument()
  })

  it('does not submit an empty form', async () => {
    const submit = vi.spyOn(api, 'submitReadings')
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Введите хотя бы одно показание')).toBeInTheDocument()
    expect(submit).not.toHaveBeenCalled()
  })

  it('submits valid readings with a comma separator for the loaded account', async () => {
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue()
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '250,125' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith({
      accountNumber: '000000000001',
      risers: [{ hot: '250.125', cold: undefined }, { hot: undefined, cold: undefined }],
    })
  })

  it('clears values and errors on reset', async () => {
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    await screen.findByText('Показание не может быть меньше предыдущего')
    fireEvent.click(screen.getByRole('button', { name: 'Сбросить форму' }))
    await waitFor(() => {
      expect(screen.queryByText('Показание не может быть меньше предыдущего')).not.toBeInTheDocument()
    })
    expect(screen.getAllByLabelText('Горячая вода, м³')[0]).toHaveValue('')
  })
})

describe('MeterReadingsPage account loading', () => {
  it('requests the account given in the address bar', async () => {
    const fetchMock = mockAccount({ ...ACCOUNT, number: '000000000002' })
    await renderPage('?account=000000000002')
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000002')
    expect(screen.getByText('Лицевой счёт № 000000000002')).toBeInTheDocument()
  })

  it('falls back to the default account number when the URL has none', async () => {
    const fetchMock = mockAccount()
    render(<MeterReadingsPage search="" defaultAccountNumber="000000000001" />)
    await screen.findByText('Ввод показаний')
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000001')
  })

  it('shows a spinner and no content while loading', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))
    const { container } = render(<MeterReadingsPage search="?account=000000000001" />)
    expect(container.querySelector('.ant-spin')).toBeInTheDocument()
    expect(screen.queryByText('Ввод показаний')).not.toBeInTheDocument()
  })

  it('shows a message when the account is not found', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }))
    render(<MeterReadingsPage search="?account=999" />)
    expect(await screen.findByText('Лицевой счёт не найден')).toBeInTheDocument()
    expect(screen.queryByText('Ввод показаний')).not.toBeInTheDocument()
  })

  it('shows a message on a network error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    render(<MeterReadingsPage search="?account=000000000001" />)
    expect(await screen.findByText('Не удалось загрузить данные')).toBeInTheDocument()
    expect(screen.queryByText('Ввод показаний')).not.toBeInTheDocument()
  })

  it('shows a message on a server error', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 500 }))
    render(<MeterReadingsPage search="?account=000000000001" />)
    expect(await screen.findByText('Не удалось загрузить данные')).toBeInTheDocument()
  })

  it('asks for an account number when none is given', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
    render(<MeterReadingsPage search="" />)
    expect(await screen.findByText(/Лицевой счёт не указан/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('validateReading', () => {
  it('accepts empty and valid values', () => {
    expect(validateReading(undefined, 10)).toBeUndefined()
    expect(validateReading('', 10)).toBeUndefined()
    expect(validateReading('250.125', 245.3)).toBeUndefined()
    expect(validateReading('250,5', 245.3)).toBeUndefined()
    expect(validateReading('5', undefined)).toBeUndefined()
  })

  it('rejects non-numeric, negative and over-precise values', () => {
    expect(validateReading('abc', 0)).toBeDefined()
    expect(validateReading('-1', 0)).toBeDefined()
    expect(validateReading('1.2345', 0)).toBeDefined()
  })

  it('rejects values below the previous reading', () => {
    expect(validateReading('245.299', 245.3)).toBe('Показание не может быть меньше предыдущего')
  })
})
