import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MeterReadingsPage from './MeterReadingsPage'
import type { Account, AccountMeter } from './accountApi'
import { OWNER, RESIDENT } from '../testUsers'

function meter(number: string, verificationDate: string | null): AccountMeter {
  return { number, water_type: 'HOT', verification_date: verificationDate, current_period_reading: null, input_locked: false, readings: [] }
}

const ACCOUNT: Account = {
  number: '000000000001',
  address: 'ул. Тестовая, д. Тест, кв. 1',
  premise: { street: 'ул. Тестовая', house: 'Тест', apartment: '1' },
  owners: [],
  reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true },
  meters: [meter('Тест 1', '2028-01-01'), meter('Тест 2', null)],
}

interface Fake {
  account: Account
  uploads: { url: string; file: File }[]
  puts: { url: string; body: { verification_date: string; act_file_key: string } }[]
  accountGets: number
  uploadStatus: number
  putStatus: number
  putAnswer: unknown
}

// Answers the account, the upload of an act and the change of the date like the server would.
function fakeServer(): Fake {
  const fake: Fake = {
    account: ACCOUNT,
    uploads: [],
    puts: [],
    accountGets: 0,
    uploadStatus: 201,
    putStatus: 201,
    putAnswer: { applied: false, verification_date: '2028-01-01', request: { id: 7, number: 'П-201026-0003' } },
  }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/accounts/000000000001') {
      fake.accountGets += 1
      return new Response(JSON.stringify(fake.account))
    }
    if (method === 'POST' && url.endsWith('/verification-act-uploads')) {
      fake.uploads.push({ url, file: (init?.body as FormData).get('file') as File })
      return fake.uploadStatus === 201
        ? new Response(JSON.stringify({ file_key: `key-${fake.uploads.length}`, file_name: 'act.pdf', size_bytes: 3 }), { status: 201 })
        : new Response(null, { status: fake.uploadStatus })
    }
    if (method === 'PUT' && url.endsWith('/verification-date')) {
      fake.puts.push({ url, body: JSON.parse(String(init?.body)) })
      return new Response(fake.putStatus < 300 ? JSON.stringify(fake.putAnswer) : null, { status: fake.putStatus })
    }
    return new Response(null, { status: 404 })
  })
  return fake
}

afterEach(() => vi.restoreAllMocks())

async function renderPage(user = OWNER) {
  render(<MeterReadingsPage user={user} today={new Date(2026, 9, 18)} search="?account=000000000001" />)
  await screen.findByRole('heading', { level: 1 })
}

const pdf = (name = 'act.pdf') => new File(['%PDF-1'], name, { type: 'application/pdf' })
const edit = (meterName: string) => fireEvent.click(screen.getByRole('button', { name: `Изменить срок проверки счётчика ${meterName}` }))
const dialog = () => screen.getByRole('dialog')
const save = () => within(dialog()).getByRole('button', { name: 'Сохранить' })
const attach = (file: File) => fireEvent.change(screen.getByTestId('act-input'), { target: { files: [file] } })

describe('the change of the verification date', () => {
  it('has a button in the card of an owner and none for a resident', async () => {
    fakeServer()
    await renderPage()
    expect(screen.getAllByRole('button', { name: /^Изменить срок проверки счётчика/ })).toHaveLength(2)
    vi.restoreAllMocks()
    fakeServer()
    document.body.innerHTML = ''
    await renderPage(RESIDENT)
    expect(screen.queryByRole('button', { name: /^Изменить срок проверки счётчика/ })).not.toBeInTheDocument()
  })

  it('opens a window with the meter, the date and a disabled save button', async () => {
    fakeServer()
    await renderPage()

    edit('Тест 1')

    expect(within(dialog()).getByText('Изменение срока проверки')).toBeInTheDocument()
    expect(within(dialog()).getByText('Тест 1')).toBeInTheDocument()
    expect(within(dialog()).getByDisplayValue('01.01.2028')).toBeInTheDocument()
    expect(save()).toBeDisabled()
  })

  it('uploads the act when it is attached and then allows the save', async () => {
    const server = fakeServer()
    await renderPage()
    edit('Тест 1')

    attach(pdf())

    await waitFor(() => expect(save()).toBeEnabled())
    expect(server.uploads).toHaveLength(1)
    expect(server.uploads[0].url).toBe('/api/accounts/000000000001/meters/%D0%A2%D0%B5%D1%81%D1%82%201/verification-act-uploads')
    expect(server.uploads[0].file.name).toBe('act.pdf')
    expect(within(dialog()).getByText('Загружен')).toBeInTheDocument()
  })

  it('refuses a file of a wrong type or size without a request', async () => {
    const server = fakeServer()
    await renderPage()
    edit('Тест 1')

    attach(pdf('act.exe'))
    expect(await within(dialog()).findByText('Допустимы файлы PDF, JPG и PNG')).toBeInTheDocument()
    attach(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.pdf'))
    expect(await within(dialog()).findByText('Файл больше 10 МБ')).toBeInTheDocument()

    expect(server.uploads).toHaveLength(0)
    expect(save()).toBeDisabled()
  })

  it('says so when the server refuses the file', async () => {
    const server = fakeServer()
    server.uploadStatus = 415
    await renderPage()
    edit('Тест 1')

    attach(pdf())

    expect(await within(dialog()).findByText(/не поддерживается/)).toBeInTheDocument()
    expect(save()).toBeDisabled()
  })

  it('blocks the save again when the file is removed', async () => {
    fakeServer()
    await renderPage()
    edit('Тест 1')
    attach(pdf())
    await waitFor(() => expect(save()).toBeEnabled())

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Удалить файл «act.pdf»' }))

    expect(save()).toBeDisabled()
    expect(screen.queryByTestId('chosen-act')).not.toBeInTheDocument()
  })

  it('needs a date for a meter that has none', async () => {
    const server = fakeServer()
    await renderPage()
    edit('Тест 2')
    attach(pdf())
    await waitFor(() => expect(within(dialog()).getByText('Загружен')).toBeInTheDocument())
    expect(save()).toBeDisabled()

    const input = within(dialog()).getByPlaceholderText('Выберите дату')
    fireEvent.change(input, { target: { value: '20.05.2030' } })
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 })

    await waitFor(() => expect(save()).toBeEnabled())
    fireEvent.click(save())
    await waitFor(() => expect(server.puts).toHaveLength(1))
    expect(server.puts[0].body).toEqual({ verification_date: '2030-05-20', act_file_key: 'key-1' })
  })

  it('sends the date with the key of the act and tells an owner that the request waits', async () => {
    const server = fakeServer()
    await renderPage()
    edit('Тест 1')
    attach(pdf())
    await waitFor(() => expect(save()).toBeEnabled())

    fireEvent.click(save())

    await waitFor(() => expect(server.puts).toHaveLength(1))
    expect(server.puts[0].url).toBe('/api/accounts/000000000001/meters/%D0%A2%D0%B5%D1%81%D1%82%201/verification-date')
    expect(server.puts[0].body).toEqual({ verification_date: '2028-01-01', act_file_key: 'key-1' })
    expect(await screen.findByText(/Заявка П-201026-0003 создана/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // the date waits for the administrator: the card shows the old one and the account was not asked again
    expect(screen.getByText('Дата проверки: 01.01.2028')).toBeInTheDocument()
    expect(server.accountGets).toBe(1)
  })

  it('reloads the account when the date is in force at once', async () => {
    const server = fakeServer()
    server.putStatus = 200
    server.putAnswer = { applied: true, verification_date: '2028-01-01', request: null }
    await renderPage()
    edit('Тест 1')
    attach(pdf())
    await waitFor(() => expect(save()).toBeEnabled())

    fireEvent.click(save())

    expect(await screen.findByText('Срок проверки изменён')).toBeInTheDocument()
    await waitFor(() => expect(server.accountGets).toBe(2))
  })

  it('keeps the window and the choices when the server fails', async () => {
    const server = fakeServer()
    server.putStatus = 500
    await renderPage()
    edit('Тест 1')
    attach(pdf())
    await waitFor(() => expect(save()).toBeEnabled())

    fireEvent.click(save())

    expect(await within(dialog()).findByText('Не удалось сохранить. Попробуйте позже')).toBeInTheDocument()
    expect(within(dialog()).getByText('act.pdf')).toBeInTheDocument()
    expect(save()).toBeEnabled()
  })

  it('changes nothing when the window is cancelled', async () => {
    const server = fakeServer()
    await renderPage()
    edit('Тест 1')
    attach(pdf())
    await waitFor(() => expect(save()).toBeEnabled())

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Отмена' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(server.puts).toHaveLength(0)
    expect(screen.getByText('Дата проверки: 01.01.2028')).toBeInTheDocument()
  })
})
