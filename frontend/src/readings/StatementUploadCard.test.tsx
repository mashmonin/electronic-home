import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StatementUploadCard from './StatementUploadCard'
import type { Batch, BatchDetail } from './batchesApi'

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const batch = (overrides: Partial<Batch> = {}): Batch => ({
  id: 1,
  month: '2026-09',
  status: 'done',
  total: 142,
  processed: 142,
  created: 137,
  replaced: 0,
  skipped: 5,
  failed: 0,
  error: null,
  created_at: '2026-10-18T12:00:00Z',
  finished_at: '2026-10-18T12:01:00Z',
  ...overrides,
})

const detail = (overrides: Partial<BatchDetail> = {}): BatchDetail => ({
  ...batch(),
  items: [
    { account_number: '1', status: 'created', reason_code: null, reason: null },
    { account_number: '117', status: 'skipped', reason_code: 'ACCOUNT_NOT_FOUND', reason: 'Лицевой счёт не найден' },
    { account_number: '9', status: 'failed', reason_code: 'MISSING_PART', reason: 'В ведомости нет одной из двух квитанций счёта' },
  ],
  ...overrides,
})

interface Setup {
  batches?: Batch[]
  postStatus?: number
  postBody?: unknown
  detail?: BatchDetail
}

function server({ batches = [], postStatus = 202, postBody = batch({ status: 'queued', processed: 0, created: 0, skipped: 0 }), detail: opened = detail() }: Setup = {}) {
  let list = batches
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST') {
      if (postStatus === 202) list = [postBody as Batch, ...list]
      return new Response(JSON.stringify(postBody), { status: postStatus })
    }
    if (url === '/api/receipt-batches') return new Response(JSON.stringify({ batches: list }))
    return new Response(JSON.stringify(opened))
  })
}

const xls = () => new File(['x'], 'ведомость.xls', { type: 'application/vnd.ms-excel' })
const choose = () => {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  fireEvent.change(input, { target: { files: [xls()] } })
}
// while the file is going antd adds a "loading" icon to the name of the button
const uploadButton = () => screen.getByRole('button', { name: /^(loading )?Загрузить$/ })
const posts = (mock: ReturnType<typeof server>) => mock.mock.calls.filter(([, init]) => init?.method === 'POST')

describe('the card "Загрузить ведомость"', () => {
  it('has the upload button disabled until a file is chosen', async () => {
    server()
    render(<StatementUploadCard />)

    expect(screen.getByText('Загрузить ведомость')).toBeInTheDocument()
    expect(uploadButton()).toBeDisabled()
    choose()
    await waitFor(() => expect(uploadButton()).toBeEnabled())
  })

  it('sends the file as a multipart form and shows the batch that was accepted', async () => {
    const mock = server()
    render(<StatementUploadCard />)
    choose()
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await screen.findByText('В очереди')
    const [url, init] = posts(mock)[0]
    expect(url).toBe('/api/receipt-batches')
    expect((init?.body as FormData).get('file')).toBeInstanceOf(File)
    expect(screen.getByText('Сентябрь 2026')).toBeInTheDocument()
    expect(screen.getByText('Обработано 0 из 142')).toBeInTheDocument()
  })

  it('disables the button while the file is going', async () => {
    let release: (value: Response) => void = () => {}
    let list: Batch[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (init?.method === 'POST') return new Promise<Response>((resolve) => (release = resolve))
      return new Response(JSON.stringify({ batches: list }))
    })
    render(<StatementUploadCard />)
    choose()
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    await waitFor(() => expect(uploadButton()).toBeDisabled())
    list = [batch({ status: 'queued' })]
    release(new Response(JSON.stringify(batch({ status: 'queued' })), { status: 202 }))
    await screen.findByText('В очереди')
  })

  it('shows the message of the server when the file is refused', async () => {
    server({ postStatus: 422, postBody: { message: 'В файле не найдены квитанции ведомости (нет разделов «Раздел 1»)' } })
    render(<StatementUploadCard />)
    choose()
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    expect(await screen.findByText(/нет разделов «Раздел 1»/)).toBeInTheDocument()
    expect(screen.queryByText('В очереди')).not.toBeInTheDocument()
  })

  it('shows a general message when the server fails', async () => {
    server({ postStatus: 500, postBody: {} })
    render(<StatementUploadCard />)
    choose()
    await waitFor(() => expect(uploadButton()).toBeEnabled())

    fireEvent.click(uploadButton())

    expect(await screen.findByText('Не удалось загрузить ведомость. Попробуйте позже')).toBeInTheDocument()
  })

  it('shows the result of a finished batch and the accounts without a receipt on demand', async () => {
    server({ batches: [batch()] })
    render(<StatementUploadCard />)

    const row = await screen.findByTestId('batch-1')
    expect(within(row).getByText('Готов')).toBeInTheDocument()
    expect(within(row).getByText('Создано 137, заменено 0, пропущено 5, ошибок 0')).toBeInTheDocument()

    fireEvent.click(within(row).getByRole('button', { name: 'Подробнее' }))

    const problems = await screen.findByTestId('batch-problems')
    expect(within(problems).getByText('Л/с 117 — пропущена: Лицевой счёт не найден')).toBeInTheDocument()
    expect(within(problems).getByText('Л/с 9 — ошибка: В ведомости нет одной из двух квитанций счёта')).toBeInTheDocument()
    expect(within(problems).queryByText(/Л\/с 1 —/)).not.toBeInTheDocument()
  })

  it('follows the progress of a running batch until it is done', async () => {
    let state: Batch = batch({ status: 'running', processed: 10, created: 10, skipped: 0 })
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === '/api/receipt-batches') return new Response(JSON.stringify({ batches: [state] }))
      return new Response(JSON.stringify({ ...state, items: [] }))
    })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    render(<StatementUploadCard />)

    expect(await screen.findByText('Обработано 10 из 142')).toBeInTheDocument()
    state = batch({ status: 'done' })
    await vi.advanceTimersByTimeAsync(2100)

    expect(await screen.findByText('Создано 137, заменено 0, пропущено 5, ошибок 0')).toBeInTheDocument()
    expect(screen.queryByText(/Обработано/)).not.toBeInTheDocument()
  })

  it('shows why a batch failed', async () => {
    server({ batches: [batch({ status: 'failed', error: 'Формирование прервано перезапуском приложения', created: 0, skipped: 0 })] })
    render(<StatementUploadCard />)

    expect(await screen.findByText('Формирование прервано перезапуском приложения')).toBeInTheDocument()
    expect(screen.getByText('Ошибка')).toBeInTheDocument()
  })
})
