import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account, AccountMeter } from './accountApi'
import * as api from './readingsApi'
import { validateReading } from './validation'

// Readings are given newest first; each one points to the next (older) one as its previous reading.
function meter(number: string, type: 'HOT' | 'COLD', values: number[], verificationDate: string | null = null): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: verificationDate,
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
  owners: [{ full_name: 'Тестовая Теста Тестовна' }, { full_name: 'Тестовый Тест Тестович' }],
  reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true },
  meters: [
    meter('Тест 1', 'HOT', [245.3, 230], '2028-01-01'),
    meter('Тест 2', 'COLD', [512.1, 498.5], '2026-10-17'),
  ],
}

function mockAccount(account: Account = ACCOUNT) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(account)))
}

beforeEach(() => {
  mockAccount()
})
afterEach(() => vi.restoreAllMocks())

async function renderPage(search = '?account=000000000001', today = new Date(2026, 7, 10)) {
  render(<MeterReadingsPage today={today} search={search} />)
  await screen.findByRole('heading', { level: 1 })
}

// The card of a meter, found by its title.
function card(name: string): HTMLElement {
  return screen.getByRole('heading', { name }).closest('.ant-card') as HTMLElement
}

describe('MeterReadingsPage', () => {
  it('shows header data from the account response, period and contacts', async () => {
    await renderPage()
    expect(screen.getByText('ТСЖ «Комфорт»')).toBeInTheDocument()
    expect(screen.getByText('Показания')).toBeInTheDocument()
    expect(screen.getByText('Заявки')).toBeInTheDocument()
    expect(screen.getByText('ул. Тестовая, д. Тест, кв. 1')).toBeInTheDocument()
    expect(screen.getByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(screen.getByText('Передайте показания до 25 октября')).toBeInTheDocument()
    expect(screen.getByText('Нужна помощь?')).toBeInTheDocument()
    expect(screen.getByText('Председатель правления ТСЖ')).toBeInTheDocument()
    expect(screen.getByText('+7 (999) 123-45-67')).toBeInTheDocument()
  })

  it('has no "Ввод показаний" heading: the period caption is the title of the page', async () => {
    await renderPage()

    expect(screen.queryByText('Ввод показаний')).not.toBeInTheDocument()
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings.map((h) => h.textContent)).toEqual(['Показания за октябрь'])
    expect(headings[0]).toHaveStyle({ fontSize: '26px' })
    expect(screen.getByText('Передайте показания до 25 октября').tagName).toBe('STRONG')
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
})

describe('meter cards', () => {
  it('shows every meter as its own card with its title, date and readings block', async () => {
    await renderPage()

    const hot = within(card('Тест 1'))
    expect(hot.getByLabelText('Горячая вода, м³')).toHaveAttribute('placeholder', '0.000')
    expect(hot.getByText('Дата проверки: 01.01.2028')).toBeInTheDocument()
    expect(hot.getByText('Предыдущее показание: 245.300 м³')).toBeInTheDocument()
    expect(hot.queryByLabelText('Холодная вода, м³')).not.toBeInTheDocument()

    const cold = within(card('Тест 2'))
    expect(cold.getByLabelText('Холодная вода, м³')).toHaveAttribute('placeholder', '0.000')
    expect(cold.getByText('Дата проверки: 17.10.2026')).toBeInTheDocument()
    expect(cold.getByText('Предыдущее показание: 512.100 м³')).toBeInTheDocument()
    expect(screen.getAllByPlaceholderText('0.000')).toHaveLength(2)
  })

  it('puts the title and the date on the left and the readings block on the right', async () => {
    await renderPage()

    const title = screen.getByRole('heading', { name: 'Тест 1' })
    const left = title.closest('.ant-col') as HTMLElement
    // antd draws its own row and columns inside a form item, so the right column is the parent of the item
    const input = within(card('Тест 1')).getByLabelText('Горячая вода, м³')
    const right = (input.closest('.ant-form-item') as HTMLElement).parentElement!.closest('.ant-col') as HTMLElement

    expect(left).not.toBe(right)
    expect(left.closest('.ant-row')).toBe(right.closest('.ant-row'))
    expect(within(left).getByText('Дата проверки: 01.01.2028')).toBeInTheDocument()
    expect(within(left).queryByLabelText('Горячая вода, м³')).not.toBeInTheDocument()
    expect(within(right).getByText('Предыдущее показание: 245.300 м³')).toBeInTheDocument()
    // the title comes first in the document, the date right under it
    expect(title.compareDocumentPosition(within(left).getByText('Дата проверки: 01.01.2028'))).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('has no risers, manual adding or separate meter number line', async () => {
    await renderPage()
    expect(screen.queryByText(/Стояк/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Добавить стояк' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Счётчик №/)).not.toBeInTheDocument()
  })

  it('keeps the order of the API and does not pair meters', async () => {
    mockAccount({
      ...ACCOUNT,
      meters: [meter('А', 'COLD', [1]), meter('Б', 'HOT', [2]), meter('В', 'HOT', [3])],
    })
    await renderPage()

    expect(screen.getAllByRole('heading', { level: 5 }).map((h) => h.textContent)).toEqual(['А', 'Б', 'В'])
    expect(screen.getAllByLabelText('Горячая вода, м³')).toHaveLength(2)
    expect(screen.getAllByLabelText('Холодная вода, м³')).toHaveLength(1)
  })

  it('shows a dash instead of the previous reading for a meter without readings', async () => {
    mockAccount({ ...ACCOUNT, meters: [{ number: 'Г-1', water_type: 'HOT', verification_date: null, current_period_reading: null, input_locked: false, readings: [] }] })
    await renderPage()
    expect(within(card('Г-1')).getByText('Предыдущее показание: —')).toBeInTheDocument()
  })

  it('shows a message instead of the form when there are no meters', async () => {
    mockAccount({ ...ACCOUNT, meters: [] })
    await renderPage()

    expect(screen.getByText('Для этого лицевого счёта счётчики не найдены')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Отправить показания' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сбросить форму' })).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText('0.000')).not.toBeInTheDocument()
  })
})

describe('entering readings', () => {
  it('rejects a reading lower than the previous one', async () => {
    await renderPage()
    fireEvent.change(within(card('Тест 1')).getByLabelText('Горячая вода, м³'), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await within(card('Тест 1')).findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
    expect(within(card('Тест 2')).queryByText('Показание не может быть меньше предыдущего')).not.toBeInTheDocument()
  })

  it('checks a reading against the latest value of its own meter', async () => {
    await renderPage()
    fireEvent.change(within(card('Тест 2')).getByLabelText('Холодная вода, м³'), { target: { value: '512.099' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await within(card('Тест 2')).findByText('Показание не может быть меньше предыдущего')).toBeInTheDocument()
  })

  it('skips the minimum check for a meter without readings', async () => {
    mockAccount({ ...ACCOUNT, meters: [{ number: 'Г-1', water_type: 'HOT', verification_date: null, current_period_reading: null, input_locked: false, readings: [] }] })
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue({ status: 'ok' })
    await renderPage()
    fireEvent.change(screen.getByLabelText('Горячая вода, м³'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalled()
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
    fireEvent.change(within(card('Тест 1')).getByLabelText('Горячая вода, м³'), { target: { value: '250,125' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith('000000000001', [{ meterNumber: 'Тест 1', value: '250.125' }])
  })

  it('clears values and errors on reset', async () => {
    await renderPage()
    const hot = within(card('Тест 1')).getByLabelText('Горячая вода, м³')
    fireEvent.change(hot, { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))
    await screen.findByText('Показание не может быть меньше предыдущего')
    fireEvent.click(screen.getByRole('button', { name: 'Сбросить форму' }))
    await waitFor(() => {
      expect(screen.queryByText('Показание не может быть меньше предыдущего')).not.toBeInTheDocument()
    })
    expect(within(card('Тест 1')).getByLabelText('Горячая вода, м³')).toHaveValue('')
  })
})

describe('reading period', () => {
  const submittedMeter = (number: string, type: 'HOT' | 'COLD', value: number, previous: number): AccountMeter => ({
    ...meter(number, type, [value, previous]),
    current_period_reading: {
      value,
      reading_date: '2026-10-18',
      previous: { value: previous, reading_date: '2026-09-25' },
    },
    input_locked: true,
  })

  async function renderAccount(account: Account) {
    mockAccount(account)
    render(<MeterReadingsPage today={new Date(2026, 9, 18)} search="?account=000000000001" />)
    await screen.findByRole('heading', { level: 1 })
  }

  it('shows the readings that are already submitted as a locked field with the earlier previous reading', async () => {
    await renderAccount({
      ...ACCOUNT,
      meters: [submittedMeter('Тест 1', 'HOT', 250.125, 245.3), meter('Тест 2', 'COLD', [512.1, 498.5])],
    })

    const hot = within(card('Тест 1')).getByLabelText('Горячая вода, м³')
    expect(hot).toBeDisabled()
    expect(hot).toHaveValue('250.125')
    expect(within(card('Тест 1')).getByText('Предыдущее показание: 245.300 м³')).toBeInTheDocument()
    expect(within(card('Тест 2')).getByLabelText('Холодная вода, м³')).toBeEnabled()
  })

  it('says that only a part is submitted and keeps the buttons for the rest', async () => {
    await renderAccount({
      ...ACCOUNT,
      meters: [submittedMeter('Тест 1', 'HOT', 250.125, 245.3), meter('Тест 2', 'COLD', [512.1, 498.5])],
    })

    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(screen.getByText('Часть показаний сдана. Передайте остальные до 25 октября')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Отправить показания' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Сбросить форму' })).toBeEnabled()
  })

  it('says that everything is submitted, shows the values and disables the buttons', async () => {
    await renderAccount({
      ...ACCOUNT,
      reading_period: { ...ACCOUNT.reading_period, all_submitted: true },
      meters: [submittedMeter('Тест 1', 'HOT', 250.125, 245.3), submittedMeter('Тест 2', 'COLD', 520, 512.1)],
    })

    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(screen.getByText('Показания сданы, новые можно будет отправить после 25 октября')).toBeInTheDocument()
    expect(within(card('Тест 1')).getByLabelText('Горячая вода, м³')).toHaveValue('250.125')
    expect(within(card('Тест 2')).getByLabelText('Холодная вода, м³')).toHaveValue('520.000')
    expect(screen.getByRole('button', { name: 'Отправить показания' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Сбросить форму' })).toBeDisabled()
  })

  it('opens the form again in a new period', async () => {
    await renderAccount({
      ...ACCOUNT,
      reading_period: { start: '2026-10-26', end: '2026-11-25', next_start: '2026-11-26', all_submitted: false, enforced: true },
      meters: [meter('Тест 1', 'HOT', [250.125, 245.3]), meter('Тест 2', 'COLD', [520, 512.1])],
    })

    expect(screen.getByText('Показания за ноябрь')).toBeInTheDocument()
    expect(screen.getByText('Передайте показания до 25 ноября')).toBeInTheDocument()
    expect(within(card('Тест 1')).getByLabelText('Горячая вода, м³')).toBeEnabled()
    expect(within(card('Тест 1')).getByText('Предыдущее показание: 250.125 м³')).toBeInTheDocument()
  })

  it('does not send a locked meter', async () => {
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue({ status: 'ok' })
    await renderAccount({
      ...ACCOUNT,
      meters: [submittedMeter('Тест 1', 'HOT', 250.125, 245.3), meter('Тест 2', 'COLD', [512.1, 498.5])],
    })

    fireEvent.change(within(card('Тест 2')).getByLabelText('Холодная вода, м³'), { target: { value: '520' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith('000000000001', [{ meterNumber: 'Тест 2', value: '520' }])
  })

  it('shows a note while the lock is switched off for testing', async () => {
    await renderAccount({ ...ACCOUNT, reading_period: { ...ACCOUNT.reading_period, enforced: false } })
    expect(screen.getByText('Тестовый режим: ограничение периода передачи отключено')).toBeInTheDocument()
  })

  it('has no note when the lock is on', async () => {
    await renderAccount(ACCOUNT)
    expect(screen.queryByText(/Тестовый режим/)).not.toBeInTheDocument()
  })

  it('keeps a meter editable when the server does not lock it, even if it has a reading of the period', async () => {
    await renderAccount({
      ...ACCOUNT,
      reading_period: { ...ACCOUNT.reading_period, all_submitted: true, enforced: false },
      meters: [
        { ...submittedMeter('Тест 1', 'HOT', 250.125, 245.3), input_locked: false },
        { ...submittedMeter('Тест 2', 'COLD', 520, 512.1), input_locked: false },
      ],
    })

    expect(within(card('Тест 1')).getByLabelText('Горячая вода, м³')).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Отправить показания' })).toBeInTheDocument()
  })

  it('does not decide anything by the date of the device', async () => {
    mockAccount(ACCOUNT)
    render(<MeterReadingsPage today={new Date(2030, 0, 1)} search="?account=000000000001" />)
    await screen.findByRole('heading', { level: 1 })

    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(within(card('Тест 1')).getByLabelText('Горячая вода, м³')).toBeEnabled()
  })
})

describe('verification date', () => {
  async function renderOn(today: Date, account: Account = ACCOUNT) {
    mockAccount(account)
    render(<MeterReadingsPage today={today} search="?account=000000000001" />)
    await screen.findByRole('heading', { level: 1 })
  }

  it('marks only the meter whose verification period is over', async () => {
    await renderOn(new Date(2026, 9, 18))

    expect(within(card('Тест 1')).queryByText('Просрочено')).not.toBeInTheDocument()
    expect(within(card('Тест 2')).getByText('Просрочено')).toBeInTheDocument()
    expect(screen.getAllByText('Просрочено')).toHaveLength(1)
  })

  it('puts the mark on its own line under the date, which is under the title', async () => {
    await renderOn(new Date(2026, 9, 18))

    const title = screen.getByRole('heading', { name: 'Тест 2' })
    const left = title.closest('.ant-col') as HTMLElement
    const date = within(left).getByText('Дата проверки: 17.10.2026')
    const mark = within(left).getByText('Просрочено')

    expect(title.compareDocumentPosition(date)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(date.compareDocumentPosition(mark)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    // the mark is not inside the date line: it is a block of its own below it
    expect(date.contains(mark)).toBe(false)
    expect(mark.closest('.ant-flex-vertical')).not.toBeNull()
    expect(mark.closest('.ant-flex-vertical')).toBe(date.closest('.ant-flex-vertical'))
  })

  it('does not mark a meter on the last day of its verification period', async () => {
    await renderOn(new Date(2026, 9, 17, 23, 59))

    expect(screen.getByText('Дата проверки: 17.10.2026')).toBeInTheDocument()
    expect(screen.queryByText('Просрочено')).not.toBeInTheDocument()
  })

  it('shows a dash and no mark for a meter without a verification date', async () => {
    await renderOn(new Date(2026, 9, 18), { ...ACCOUNT, meters: [meter('Г-1', 'HOT', [10])] })

    expect(screen.getByText('Дата проверки: —')).toBeInTheDocument()
    expect(screen.queryByText('Просрочено')).not.toBeInTheDocument()
  })

  it('does not stop the user from sending a reading for an expired meter', async () => {
    const submit = vi.spyOn(api, 'submitReadings').mockResolvedValue({ status: 'ok' })
    await renderOn(new Date(2026, 9, 18))

    fireEvent.change(within(card('Тест 2')).getByLabelText('Холодная вода, м³'), { target: { value: '520' } })
    fireEvent.click(screen.getByRole('button', { name: 'Отправить показания' }))

    expect(await screen.findByText('Показания переданы')).toBeInTheDocument()
    expect(submit).toHaveBeenCalledWith('000000000001', [{ meterNumber: 'Тест 2', value: '520' }])
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
    await screen.findByRole('heading', { level: 1 })
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000001')
  })

  it('shows a spinner and no content while loading', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}))
    const { container } = render(<MeterReadingsPage search="?account=000000000001" />)
    expect(container.querySelector('.ant-spin')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('shows a message when the account is not found', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }))
    render(<MeterReadingsPage search="?account=999" />)
    expect(await screen.findByText('Лицевой счёт не найден')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('shows a message on a network error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    render(<MeterReadingsPage search="?account=000000000001" />)
    expect(await screen.findByText('Не удалось загрузить данные')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
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
