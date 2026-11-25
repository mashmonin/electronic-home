import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AccountListItem } from './accountApi'
import ReceiptsTab from './ReceiptsTab'
import UploadReceiptTab from './UploadReceiptTab'
import type { Receipt } from './receiptsApi'

afterEach(() => vi.restoreAllMocks())

const ACCOUNTS: AccountListItem[] = [
  { number: '000000000001', address: 'ул. Тестовая, д. Тест, кв. 1', apartment: '1' },
  { number: '000000000002', address: 'ул. Тестовая, д. Тест, кв. 2', apartment: '2' },
]
const LABEL_1 = 'кв. 1 · ул. Тестовая, д. Тест (счёт 000000000001)'
const LABEL_2 = 'кв. 2 · ул. Тестовая, д. Тест (счёт 000000000002)'

const receipt = (month: string): Receipt => ({
  month,
  size: 10,
  capital_repair_amount: null,
  utilities_amount: null,
  published_at: '2026-10-18T12:00:00Z',
})

interface Setup {
  /** Receipts already published, by account number. */
  listed?: Record<string, Receipt[]>
  putStatus?: number
  accountsStatus?: number
  receiptsStatus?: number
}

// A fake server: the list of apartments, the receipts of an apartment and the answer to a publication.
function server({ listed = {}, putStatus = 201, accountsStatus = 200, receiptsStatus = 200 }: Setup = {}) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (init?.method === 'PUT') return new Response(null, { status: putStatus })
    if (url === '/api/accounts') {
      return accountsStatus === 200
        ? new Response(JSON.stringify({ accounts: ACCOUNTS }))
        : new Response(null, { status: accountsStatus })
    }
    if (receiptsStatus !== 200) return new Response(null, { status: receiptsStatus })
    const number = url.split('/')[3]
    return new Response(JSON.stringify({ receipts: listed[number] ?? [] }))
  })
}

const puts = (fetchMock: ReturnType<typeof server>) => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')
const listCalls = (fetchMock: ReturnType<typeof server>, number: string) =>
  fetchMock.mock.calls.filter(([url, init]) => init?.method !== 'PUT' && String(url) === `/api/accounts/${number}/receipts`)

const pdf = (name = 'receipt.pdf', size = 100) => {
  const file = new File(['%PDF-1.4'], name, { type: name.endsWith('.pdf') ? 'application/pdf' : 'application/msword' })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

async function open() {
  const view = render(<UploadReceiptTab />)
  await waitFor(() => expect(screen.getByRole('combobox')).toBeEnabled())
  return view
}

async function pickApartment(label: string) {
  fireEvent.mouseDown(screen.getByRole('combobox'))
  const options = await screen.findAllByText(label)
  fireEvent.click(options[options.length - 1])
}

// The apartment shown in the closed select (the option in the dropdown has the same title).
const selectedApartment = () => document.querySelector('.ant-select-selection-item')?.getAttribute('title') ?? null

function pickMonth(text: string) {
  const input = screen.getByPlaceholderText('Выберите месяц')
  fireEvent.mouseDown(input)
  fireEvent.change(input, { target: { value: text } })
  fireEvent.keyDown(input, { key: 'Enter' })
}

function pickFile(file: File) {
  // the card of the statement has a file input too; the one of the form of an apartment is the last
  const input = Array.from(document.querySelectorAll('input[type="file"]')).at(-1) as HTMLInputElement
  fireEvent.change(input, { target: { files: [file] } })
}

// while a request runs antd adds a "loading" icon to the name of the button
const publishButton = () => screen.getByRole('button', { name: /^(loading )?Опубликовать$/ })

// Fills everything the form needs and waits until the receipts of the apartment are known.
async function fillIn(label = LABEL_1, month = '09.2026') {
  await pickApartment(label)
  pickMonth(month)
  pickFile(pdf())
  await waitFor(() => expect(publishButton()).toBeEnabled())
}

describe('the tab', () => {
  it('has the title and an empty form: no apartment is chosen for the administrator', async () => {
    server()
    await open()

    expect(screen.getByRole('heading', { level: 1, name: 'Выставить квитанции' })).toBeInTheDocument()
    expect(screen.getByText('Опубликовать квитанцию')).toBeInTheDocument()
    expect(screen.getByText('Выберите квартиру')).toBeInTheDocument()
    expect(selectedApartment()).toBeNull()
    expect(publishButton()).toBeDisabled()
  })

  it('is not part of the receipts tab, even for an administrator', async () => {
    server()
    const { container } = render(<ReceiptsTab accountNumber="000000000001" />)
    await waitFor(() => expect(document.querySelector('.ant-spin')).not.toBeInTheDocument())

    expect(screen.queryByText('Опубликовать квитанцию')).not.toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).not.toBeInTheDocument()
    for (const name of ['Опубликовать', 'Заменить', 'Удалить', 'Загрузить']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })
})

describe('the apartment', () => {
  it('is listed as "кв. N · адрес (счёт …)" and can be searched', async () => {
    server()
    await open()

    fireEvent.mouseDown(screen.getByRole('combobox'))
    expect((await screen.findAllByText(LABEL_1)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(LABEL_2).length).toBeGreaterThan(0)

    fireEvent.change(screen.getByRole('combobox'), { target: { value: '000000000002' } })
    await waitFor(() => expect(screen.queryAllByText(LABEL_1)).toHaveLength(0))
    expect(screen.getAllByText(LABEL_2).length).toBeGreaterThan(0)
  })

  it('is required: with a month and a file but without an apartment the button stays disabled', async () => {
    server()
    await open()
    pickMonth('09.2026')
    pickFile(pdf())

    await waitFor(() => expect(screen.getByText('receipt.pdf')).toBeInTheDocument())
    expect(publishButton()).toBeDisabled()
  })

  it('enables the button when the apartment, the month and the file are chosen', async () => {
    server()
    await open()

    await fillIn()

    expect(publishButton()).toBeEnabled()
  })

  it('asks for the receipts of the chosen apartment and keeps the button disabled until they are known', async () => {
    let answer: (response: Response) => void = () => {}
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) =>
      String(input) === '/api/accounts'
        ? Promise.resolve(new Response(JSON.stringify({ accounts: ACCOUNTS })))
        : new Promise<Response>((resolve) => {
            answer = resolve
          }),
    )
    await open()
    await pickApartment(LABEL_1)
    pickMonth('09.2026')
    pickFile(pdf())

    await waitFor(() => expect(screen.getByText('receipt.pdf')).toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/000000000001/receipts', expect.anything())
    expect(publishButton()).toBeDisabled()

    answer(new Response(JSON.stringify({ receipts: [] })))
    await waitFor(() => expect(publishButton()).toBeEnabled())
  })

  it('shows a message with a retry when the list of apartments does not load', async () => {
    const fetchMock = server({ accountsStatus: 500 })
    render(<UploadReceiptTab />)

    expect(await screen.findByText('Не удалось загрузить список квартир')).toBeInTheDocument()
    expect(publishButton()).toBeDisabled()

    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ accounts: ACCOUNTS })))
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    await waitFor(() => expect(screen.getByRole('combobox')).toBeEnabled())
    expect(screen.queryByText('Не удалось загрузить список квартир')).not.toBeInTheDocument()
  })

  it('shows a message with a retry when the receipts of the apartment do not load', async () => {
    server({ receiptsStatus: 500 })
    await open()
    await pickApartment(LABEL_1)
    pickMonth('09.2026')
    pickFile(pdf())

    expect(await screen.findByText('Не удалось проверить опубликованные квитанции')).toBeInTheDocument()
    expect(publishButton()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeInTheDocument()
  })
})

describe('filling the form', () => {
  it('does not offer a month later than the current one', async () => {
    server()
    await open()
    await pickApartment(LABEL_1)
    pickMonth('01.2999')
    pickFile(pdf())

    await waitFor(() => expect(screen.getByText('receipt.pdf')).toBeInTheDocument())
    expect(publishButton()).toBeDisabled()
  })

  it('refuses a file that is not a PDF without sending anything', async () => {
    const fetchMock = server()
    await open()
    await pickApartment(LABEL_1)
    pickMonth('09.2026')
    pickFile(pdf('receipt.docx'))
    await waitFor(() => expect(publishButton()).toBeEnabled())

    fireEvent.click(publishButton())

    expect(await screen.findByText('Выберите файл в формате PDF')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('refuses a file over 5 MB', async () => {
    const fetchMock = server()
    await open()
    await pickApartment(LABEL_1)
    pickMonth('09.2026')
    pickFile(pdf('big.pdf', 6 * 1024 * 1024))
    await waitFor(() => expect(publishButton()).toBeEnabled())

    fireEvent.click(publishButton())

    expect(await screen.findByText('Файл больше 5 МБ')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('refuses one amount without the other', async () => {
    const fetchMock = server()
    await open()
    fireEvent.change(screen.getByLabelText('Капитальный ремонт, ₽'), { target: { value: '100' } })
    await fillIn()

    fireEvent.click(publishButton())

    expect(await screen.findByText('Укажите обе суммы или оставьте оба поля пустыми')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })
})

describe('publishing', () => {
  it('sends the file to the chosen apartment, keeps the apartment and clears the rest', async () => {
    const fetchMock = server()
    await open()
    fireEvent.change(screen.getByLabelText('Капитальный ремонт, ₽'), { target: { value: '1842,5' } })
    fireEvent.change(screen.getByLabelText('Коммунальные услуги, ₽'), { target: { value: '6318,27' } })
    await fillIn(LABEL_1, '10.2026')

    fireEvent.click(publishButton())

    expect(await screen.findByText('Квитанция за Октябрь 2026 (кв. 1) опубликована')).toBeInTheDocument()
    const [[url]] = puts(fetchMock)
    expect(url).toBe('/api/accounts/000000000001/receipts/2026-10?capital_repair=1842.50&utilities=6318.27')
    expect(selectedApartment()).toBe(LABEL_1)
    expect(screen.getByPlaceholderText('Выберите месяц')).toHaveValue('')
    expect(screen.queryByText('receipt.pdf')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Капитальный ремонт, ₽')).toHaveValue('')
    await waitFor(() => expect(listCalls(fetchMock, '000000000001').length).toBeGreaterThanOrEqual(2))
  })

  it('sends no amount parameters when both are empty', async () => {
    const fetchMock = server()
    await open()
    await fillIn()

    fireEvent.click(publishButton())

    await screen.findByText('Квитанция за Сентябрь 2026 (кв. 1) опубликована')
    expect(puts(fetchMock)[0][0]).toBe('/api/accounts/000000000001/receipts/2026-09')
  })

  it('publishes to another apartment when another one is chosen', async () => {
    const fetchMock = server()
    await open()
    await fillIn(LABEL_2)

    fireEvent.click(publishButton())

    await screen.findByText('Квитанция за Сентябрь 2026 (кв. 2) опубликована')
    expect(puts(fetchMock)[0][0]).toBe('/api/accounts/000000000002/receipts/2026-09')
  })

  it('says that a receipt was replaced', async () => {
    server({ putStatus: 200 })
    await open()
    await fillIn()

    fireEvent.click(publishButton())

    expect(await screen.findByText('Квитанция за Сентябрь 2026 (кв. 1) заменена')).toBeInTheDocument()
  })

  it.each([
    [400, 'Квитанция не принята. Проверьте, что файл является PDF, а суммы указаны верно'],
    [413, 'Файл слишком большой. Максимум 5 МБ'],
    [404, 'Лицевой счёт не найден'],
    [403, 'Недостаточно прав'],
    [500, 'Не удалось опубликовать квитанцию. Попробуйте позже'],
  ])('shows the message for %s and keeps the form filled', async (status, text) => {
    server({ putStatus: status })
    await open()
    await fillIn()

    fireEvent.click(publishButton())

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(selectedApartment()).toBe(LABEL_1)
    expect(screen.getByText('receipt.pdf')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Выберите месяц')).toHaveValue('09.2026')
    expect(publishButton()).toBeEnabled()
  })

  it('shows a message when the network fails and keeps the form filled', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (init?.method === 'PUT') throw new Error('network')
      if (String(input) === '/api/accounts') return new Response(JSON.stringify({ accounts: ACCOUNTS }))
      return new Response(JSON.stringify({ receipts: [] }))
    })
    await open()
    await fillIn()

    fireEvent.click(publishButton())

    expect(await screen.findByText('Не удалось опубликовать квитанцию. Попробуйте позже')).toBeInTheDocument()
    expect(screen.getByText('receipt.pdf')).toBeInTheDocument()
  })

  it('does not send a second request while the first one is running', async () => {
    let finish: (response: Response) => void = () => {}
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      if (init?.method === 'PUT') {
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      }
      if (String(input) === '/api/accounts') return Promise.resolve(new Response(JSON.stringify({ accounts: ACCOUNTS })))
      return Promise.resolve(new Response(JSON.stringify({ receipts: [] })))
    })
    await open()
    await fillIn()

    fireEvent.click(publishButton())
    await waitFor(() => expect(publishButton()).toBeDisabled())
    fireEvent.click(publishButton())

    expect(puts(fetchMock as unknown as ReturnType<typeof server>)).toHaveLength(1)
    finish(new Response(null, { status: 201 }))
    expect(await screen.findByText('Квитанция за Сентябрь 2026 (кв. 1) опубликована')).toBeInTheDocument()
  })
})

describe('replacing a receipt', () => {
  const LISTED = { '000000000001': [receipt('2026-10')] }

  async function fillForExistingMonth(label = LABEL_1) {
    const fetchMock = server({ listed: LISTED, putStatus: 200 })
    await open()
    await fillIn(label, '10.2026')
    fireEvent.click(publishButton())
    return fetchMock
  }

  it('asks for a confirmation with the apartment and sends nothing before it', async () => {
    const fetchMock = await fillForExistingMonth()

    expect(await screen.findByText('Квитанция за Октябрь 2026 (кв. 1) уже опубликована. Заменить?')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(0)
  })

  it('sends the receipt after the confirmation', async () => {
    const fetchMock = await fillForExistingMonth()

    fireEvent.click(await screen.findByRole('button', { name: 'Заменить' }))

    expect(await screen.findByText('Квитанция за Октябрь 2026 (кв. 1) заменена')).toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(1)
  })

  it('keeps the form and sends nothing after a refusal', async () => {
    const fetchMock = await fillForExistingMonth()

    fireEvent.click(await screen.findByRole('button', { name: 'Отмена' }))

    // antd keeps a closed popover in the DOM and hides it with a class
    await waitFor(() => expect(document.querySelector('.ant-popover:not(.ant-popover-hidden)')).toBeNull())
    expect(puts(fetchMock)).toHaveLength(0)
    expect(screen.getByText('receipt.pdf')).toBeInTheDocument()
  })

  it('does not ask when the same month is published only for another apartment', async () => {
    const fetchMock = server({ listed: LISTED })
    await open()
    await fillIn(LABEL_2, '10.2026')

    fireEvent.click(publishButton())

    await screen.findByText('Квитанция за Октябрь 2026 (кв. 2) опубликована')
    expect(screen.queryByText(/уже опубликована/)).not.toBeInTheDocument()
    expect(puts(fetchMock)).toHaveLength(1)
  })

  it('does not ask for a month that has no receipt', async () => {
    const fetchMock = server({ listed: LISTED })
    await open()
    await fillIn(LABEL_1, '09.2026')

    fireEvent.click(publishButton())

    await screen.findByText('Квитанция за Сентябрь 2026 (кв. 1) опубликована')
    expect(puts(fetchMock)).toHaveLength(1)
  })
})
