import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ADMIN, OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { type FakeRequests, fakeRequests, message, reply, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

const MB = 1024 * 1024
const file = (name: string, size = 100, type = 'application/octet-stream') => {
  const result = new File(['x'], name, { type })
  Object.defineProperty(result, 'size', { value: size })
  return result
}

async function openChat(user = OWNER, server: FakeRequests = fakeRequests({
  requests: [request(1, 'О-191026-0001')],
  messages: { 1: [message(11, 'Подтекает кран')] },
  viewer: { role: user.role, login: user.login },
})) {
  render(<RequestsTab user={user} search="?request=1" />)
  await screen.findByText('Подтекает кран')
  return server
}

const input = () => screen.getByTestId('attachment-input') as HTMLInputElement
const choose = (...files: File[]) => fireEvent.change(input(), { target: { files } })
const chosen = () => within(screen.getByTestId('chosen-files'))
const field = () => screen.getByLabelText('Сообщение') as HTMLTextAreaElement
const sendButton = () => screen.getByRole('button', { name: /Отправить/ })

describe('choosing files in the chat', () => {
  it('offers a button and a file dialog for the allowed types, several files at once', async () => {
    await openChat()

    expect(screen.getByRole('button', { name: /Прикрепить файл/ })).toBeInTheDocument()
    expect(input().multiple).toBe(true)
    expect(input().accept).toBe('.jpg,.jpeg,.png,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.txt')
  })

  it('shows the chosen files with their sizes, and removes one on demand', async () => {
    await openChat()

    choose(file('схема.pdf', 340 * 1024), file('фото.jpg', 1.2 * MB))

    expect(chosen().getByText('схема.pdf')).toBeInTheDocument()
    expect(chosen().getByText('340 КБ')).toBeInTheDocument()
    expect(chosen().getByText('1,2 МБ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Удалить файл «схема.pdf»' }))
    expect(screen.queryByText('схема.pdf')).not.toBeInTheDocument()
    expect(chosen().getByText('фото.jpg')).toBeInTheDocument()
  })

  it('refuses a sixth file and keeps the five', async () => {
    await openChat()
    choose(file('a.pdf'), file('b.pdf'), file('c.pdf'), file('d.pdf'), file('e.pdf'))

    choose(file('f.pdf'))

    expect(await screen.findByText('Не более 5 файлов')).toBeInTheDocument()
    expect(screen.queryByText('f.pdf')).not.toBeInTheDocument()
    expect(chosen().getByText('a.pdf')).toBeInTheDocument()
  })

  it('refuses a file over 10 MB, a type that is not allowed and an empty file, each with its reason', async () => {
    await openChat()

    choose(file('big.pdf', 10 * MB + 1), file('скрипт.exe'), file('empty.pdf', 0), file('good.pdf'))

    expect(await screen.findByText('Файл «big.pdf» больше 10 МБ')).toBeInTheDocument()
    expect(screen.getByText('Тип файла «скрипт.exe» не поддерживается')).toBeInTheDocument()
    expect(screen.getByText('Файл «empty.pdf» пуст')).toBeInTheDocument()
    expect(chosen().getByText('good.pdf')).toBeInTheDocument()
    expect(screen.queryByText('big.pdf', { selector: '.ant-typography' })).not.toBeInTheDocument()
  })

  it('refuses files that are over 25 MB together', async () => {
    await openChat()
    choose(file('a.pdf', 9 * MB), file('b.pdf', 9 * MB))

    choose(file('c.pdf', 8 * MB))

    expect(await screen.findByText('Общий размер файлов больше 25 МБ')).toBeInTheDocument()
    expect(screen.queryByText('c.pdf')).not.toBeInTheDocument()
  })

  it('lets the same file be chosen again after it was removed', async () => {
    await openChat()
    choose(file('a.pdf'))
    fireEvent.click(screen.getByRole('button', { name: 'Удалить файл «a.pdf»' }))

    choose(file('a.pdf'))

    expect(chosen().getByText('a.pdf')).toBeInTheDocument()
    expect(input().value).toBe('')
  })
})

describe('sending files', () => {
  it('sends a multipart form with the text and the files, shows the message with the files and clears the field', async () => {
    const server = await openChat()
    fireEvent.change(field(), { target: { value: 'Вот фото' } })
    choose(file('фото.jpg', 1.2 * MB, 'image/jpeg'))

    fireEvent.click(sendButton())

    expect(await within(screen.getByTestId('chat-feed')).findByText('Вот фото')).toBeInTheDocument()
    const [[url, init]] = server.calls('POST', /\/messages$/)
    expect(url).toBe('/api/requests/1/messages')
    const form = init?.body as FormData
    expect(form.get('text')).toBe('Вот фото')
    expect((form.getAll('files') as File[]).map((f) => f.name)).toEqual(['фото.jpg'])
    expect(screen.getByRole('button', { name: /Скачать файл «фото.jpg»/ })).toBeInTheDocument()
    expect(field().value).toBe('')
    expect(screen.queryByTestId('chosen-files')).not.toBeInTheDocument()
  })

  it('lets a message be only files: the button is enabled and the text goes empty', async () => {
    const server = await openChat()
    expect(sendButton()).toBeDisabled()

    choose(file('схема.pdf', 340 * 1024, 'application/pdf'))
    expect(sendButton()).toBeEnabled()
    fireEvent.click(sendButton())

    await screen.findByRole('button', { name: /Скачать файл «схема.pdf»/ })
    const form = server.calls('POST', /\/messages$/)[0][1]?.body as FormData
    expect(form.get('text')).toBe('')
  })

  it('still sends JSON when there are no files', async () => {
    const server = await openChat()
    fireEvent.change(field(), { target: { value: 'Только текст' } })

    fireEvent.click(sendButton())

    await within(screen.getByTestId('chat-feed')).findByText('Только текст')
    const init = server.calls('POST', /\/messages$/)[0][1]
    expect(typeof init?.body).toBe('string')
    expect(JSON.parse(String(init?.body))).toEqual({ text: 'Только текст' })
  })

  it('does not enable the button for an empty text and no files, and again after the files are removed', async () => {
    await openChat()
    choose(file('a.pdf'))
    expect(sendButton()).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Удалить файл «a.pdf»' }))

    expect(sendButton()).toBeDisabled()
  })

  it.each([
    [413, 'Файл слишком большой или вложения превышают допустимый размер'],
    [415, 'Тип файла не поддерживается'],
    [500, 'Не удалось отправить сообщение. Попробуйте позже'],
    [0, 'Не удалось отправить сообщение. Попробуйте позже'],
  ])('shows the message for %s and keeps the text and the files', async (status, text) => {
    const server = await openChat()
    server.fail.send = status
    fireEvent.change(field(), { target: { value: 'Мой текст' } })
    choose(file('a.pdf'))

    fireEvent.click(sendButton())

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(field().value).toBe('Мой текст')
    expect(chosen().getByText('a.pdf')).toBeInTheDocument()
  })

  it('does not take new files while a message is being sent', async () => {
    let finish: (response: Response) => void = () => {}
    const server = await openChat()
    server.fetchMock.mockImplementation((url, init) => {
      if (init?.method === 'POST') {
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      }
      return Promise.resolve(new Response(JSON.stringify({ requests: server.requests, messages: server.messages[1] })))
    })
    choose(file('a.pdf'))

    fireEvent.click(sendButton())

    await waitFor(() => expect(screen.getByRole('button', { name: /Прикрепить файл/ })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Удалить файл «a.pdf»' })).toBeDisabled()
    finish(new Response(JSON.stringify(message(99, '', { attachments: [{ id: 1, name: 'a.pdf', size: 100, content_type: 'application/pdf' }] })), { status: 201 }))
    await screen.findByRole('button', { name: /Скачать файл «a.pdf»/ })
  })
})

describe('the files in the feed', () => {
  const withFiles = () => fakeRequests({
    requests: [request(1, 'О-191026-0001')],
    messages: {
      1: [
        message(11, 'Подтекает кран', { attachments: [
          { id: 21, name: 'схема.pdf', size: 340 * 1024, content_type: 'application/pdf' },
          { id: 22, name: 'фото.jpg', size: 1.2 * MB, content_type: 'image/jpeg' },
        ] }),
        reply(12, ''),
      ],
    },
  })

  it('shows the name and the size of every file under the message', async () => {
    await openChat(OWNER, withFiles())

    const first = screen.getByTestId('attachment-21')
    expect(first.textContent).toContain('схема.pdf')
    expect(first.textContent).toContain('340 КБ')
    expect(screen.getByTestId('attachment-22').textContent).toContain('1,2 МБ')
  })

  it('shows a message that is only files without an empty bubble of text', async () => {
    const server = withFiles()
    server.messages[1][1] = reply(12, '')
    server.messages[1][1].attachments = [{ id: 23, name: 'образец.docx', size: 20 * 1024, content_type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }]
    await openChat(OWNER, server)

    expect(screen.getByTestId('attachment-23').textContent).toContain('образец.docx')
  })

  it('downloads a file with the token and hands it to the browser under its own name', async () => {
    const server = await openChat(OWNER, withFiles())
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:file'), revokeObjectURL: vi.fn() })
    const saved: { download: string; href: string }[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push({ download: this.download, href: this.href })
    })

    fireEvent.click(screen.getByTestId('attachment-21'))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toEqual({ download: 'схема.pdf', href: 'blob:file' })
    expect(server.calls('GET', /\/attachments\/21$/)[0][0]).toBe('/api/requests/1/attachments/21')
  })

  it('downloads with the keyboard too', async () => {
    const server = await openChat(OWNER, withFiles())
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:file'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    fireEvent.keyDown(screen.getByTestId('attachment-22'), { key: 'Enter' })

    await waitFor(() => expect(server.calls('GET', /\/attachments\/22$/)).toHaveLength(1))
  })

  it('says when a file cannot be downloaded', async () => {
    const server = await openChat(OWNER, withFiles())
    server.fail.download = 404

    fireEvent.click(screen.getByTestId('attachment-21'))

    expect(await screen.findByText('Не удалось скачать файл')).toBeInTheDocument()
  })

  it('shows an administrator the files of the owner and lets him answer with a file', async () => {
    const server = withFiles()
    // the administrator writes only into a request that is in work
    server.requests.forEach((item) => (item.status = 'IN_PROGRESS'))
    await openChat(ADMIN, server)
    expect(screen.getByTestId('attachment-21')).toBeInTheDocument()

    choose(file('ответ.pdf'))
    fireEvent.click(sendButton())

    await screen.findByRole('button', { name: /Скачать файл «ответ.pdf»/ })
  })

  it('has no list of files under a message without files', async () => {
    await openChat()
    expect(screen.queryByTestId(/^attachment-\d+$/)).not.toBeInTheDocument()
  })

  it('brings the files of a new message with the polling', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = await openChat()
    server.messages[1].push(reply(12, 'Образец во вложении'))
    server.messages[1][1].attachments = [{ id: 31, name: 'образец.docx', size: 2048, content_type: 'application/msword' }]

    await act(async () => {
      vi.advanceTimersByTime(5000)
    })

    expect(await screen.findByTestId('attachment-31')).toBeInTheDocument()
  })
})

describe('the list of the requests', () => {
  it('shows what the server gives for a message that is only files', async () => {
    fakeRequests({
      requests: [request(1, 'О-191026-0001', { last_message: { text: 'Вложение: схема.pdf', author_role: 'OWNER' }, awaiting_reply: true })],
      messages: { 1: [message(11, '', { attachments: [{ id: 21, name: 'схема.pdf', size: 100, content_type: 'application/pdf' }] })] },
    })
    render(<RequestsTab user={OWNER} search="" />)

    expect(await screen.findByText('Вложение: схема.pdf')).toBeInTheDocument()
  })
})

describe('the window of a new request', () => {
  async function openWindow() {
    const server = fakeRequests({ requests: [request(1, 'О-191026-0001')], messages: { 1: [message(11, 'Привет')] } })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0001')
    fireEvent.click(screen.getByRole('button', { name: 'Новая заявка' }))
    await screen.findByText('Новая заявка', { selector: '.ant-modal-title' })
    return server
  }

  const modalInput = () => document.querySelector('.ant-modal [data-testid="attachment-input"]') as HTMLInputElement
  const chooseInModal = (...files: File[]) => fireEvent.change(modalInput(), { target: { files } })
  const create = () => fireEvent.click(screen.getByRole('button', { name: /Создать/ }))

  it('has the button to attach files', async () => {
    await openWindow()
    expect(within(document.querySelector('.ant-modal') as HTMLElement).getByRole('button', { name: /Прикрепить файл/ })).toBeInTheDocument()
  })

  it('creates a request with a text and a file as a multipart form', async () => {
    const server = await openWindow()
    fireEvent.change(screen.getByLabelText('Сообщение', { selector: 'textarea.ant-input' }), { target: { value: 'Течёт труба' } })
    chooseInModal(file('труба.jpg', 500 * 1024, 'image/jpeg'))

    create()

    await waitFor(() => expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(1))
    const form = server.calls('POST', /^\/api\/requests$/)[0][1]?.body as FormData
    expect(form.get('text')).toBe('Течёт труба')
    expect((form.getAll('files') as File[]).map((f) => f.name)).toEqual(['труба.jpg'])
    await waitFor(() => expect(document.querySelector('.ant-modal-title')).toBeNull())
  })

  it('creates a request of only a file', async () => {
    const server = await openWindow()
    chooseInModal(file('акт.pdf', 100, 'application/pdf'))

    create()

    await waitFor(() => expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(1))
    expect(screen.queryByText('Введите сообщение')).not.toBeInTheDocument()
  })

  it('still asks for a message when there are neither a text nor a file', async () => {
    const server = await openWindow()

    create()

    expect(await screen.findByText('Введите сообщение')).toBeInTheDocument()
    expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(0)
  })

  it.each([
    [413, 'Файл слишком большой или вложения превышают допустимый размер'],
    [415, 'Тип файла не поддерживается'],
  ])('keeps the window, the text and the files for %s', async (status, text) => {
    const server = await openWindow()
    server.fail.create = status
    fireEvent.change(screen.getByLabelText('Сообщение', { selector: 'textarea.ant-input' }), { target: { value: 'Текст' } })
    chooseInModal(file('a.pdf'))

    create()

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByText('a.pdf')).toBeInTheDocument()
    expect((screen.getByLabelText('Сообщение', { selector: 'textarea.ant-input' }) as HTMLTextAreaElement).value).toBe('Текст')
  })

  it('applies the same rules to the files as the chat', async () => {
    await openWindow()

    chooseInModal(file('скрипт.exe'), file('big.pdf', 11 * MB))

    expect(await screen.findByText('Тип файла «скрипт.exe» не поддерживается')).toBeInTheDocument()
    expect(screen.getByText('Файл «big.pdf» больше 10 МБ')).toBeInTheDocument()
  })
})
