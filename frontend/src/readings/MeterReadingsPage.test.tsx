import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account, AccountMeter } from './accountApi'
import * as api from './readingsApi'
import { validateReading } from './validation'

// Readings are given newest first; each one points to the next (older) one as its previous reading.
function meter(number: string, type: 'HOT' | 'COLD', values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
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
  owners: [{ full_name: 'Тестовая Теста Тестовна' }, { full_name: 'Тестовый Тест Тестович' }],
  meters: [
    meter('Тест 1', 'HOT', [245.3, 230]),
    meter('Тест 2', 'COLD', [512.1, 498.5]),
  ],
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
    expect(screen.queryByText('Стояк 2')).not.toBeInTheDocument()
    expect(screen.getAllByPlaceholderText('0.000')).toHaveLength(2)
    expect(screen.getByText('Счётчик № Тест 1')).toBeInTheDocument()
    expect(screen.getByText('Счётчик № Тест 2')).toBeInTheDocument()
    expect(screen.getByText('Пред. показание: 245.300 м³')).toBeInTheDocument()
    expect(screen.getByText('Пред. показание: 512.100 м³')).toBeInTheDocument()
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
    expect(screen.getByText('Стояк 2')).toBeInTheDocument()
  })

  it('does not submit an empty form', async () => {
    const submit = vi.spyOn(api, 'submitReadings')
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Введите хотя бы одно показание')).toBeInTheDocument()
    expect(submit).not.toHaveBeenCalled()
  })

  it('submits valid readings with a comma separator for the loaded account', async () => {
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue({ status: 'ok' })
    await renderPage()
    const [hot] = screen.getAllByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '250,125' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith('000000000001', [{ meterNumber: 'Тест 1', value: '250.125' }])
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

describe('MeterReadingsPage meters', () => {
  it('builds a riser card per pair of hot and cold meters', async () => {
    mockAccount({
      ...ACCOUNT,
      meters: [
        meter('Г-1', 'HOT', [10]),
        meter('Х-1', 'COLD', [20]),
        meter('Г-2', 'HOT', [30]),
        meter('Х-2', 'COLD', [40]),
      ],
    })
    await renderPage()
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
    expect(screen.getByText('Стояк 2')).toBeInTheDocument()
    expect(screen.getByText('Счётчик № Г-2')).toBeInTheDocument()
    expect(screen.getByText('Счётчик № Х-2')).toBeInTheDocument()
    expect(screen.getByText('Пред. показание: 40.000 м³')).toBeInTheDocument()
  })

  it('shows dashes for an unpaired meter', async () => {
    mockAccount({ ...ACCOUNT, meters: [meter('Г-1', 'HOT', [10])] })
    await renderPage()
    expect(screen.getByText('Счётчик № Г-1')).toBeInTheDocument()
    expect(screen.getByText('Счётчик № —')).toBeInTheDocument()
    expect(screen.getByText('Пред. показание: —')).toBeInTheDocument()
  })

  it('shows no cards for an apartment without meters but lets the user add one', async () => {
    mockAccount({ ...ACCOUNT, meters: [] })
    await renderPage()
    expect(screen.queryByText('Стояк 1')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '+ Добавить стояк' }))
    expect(screen.getByText('Стояк 1')).toBeInTheDocument()
  })

  it('shows dashes and skips the minimum check for a meter without readings', async () => {
    mockAccount({ ...ACCOUNT, meters: [{ number: 'Г-1', water_type: 'HOT', readings: [] }] })
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue({ status: 'ok' })
    await renderPage()
    expect(screen.getByText('Счётчик № Г-1')).toBeInTheDocument()
    expect(screen.getAllByText('Пред. показание: —')).toHaveLength(2)
    fireEvent.change(screen.getByLabelText('Горячая вода, м³'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalled()
  })

  it('checks the reading against the latest value of its own meter', async () => {
    await renderPage()
    fireEvent.change(screen.getByLabelText('Холодная вода, м³'), { target: { value: '512.099' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
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
