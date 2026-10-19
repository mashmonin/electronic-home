import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { type FakeRequests, fakeRequests, message, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

async function open(user = OWNER, server: FakeRequests = fakeRequests({ requests: [request(1, 'О-191026-0001')], messages: { 1: [message(11, 'Привет')] } })) {
  render(<RequestsTab user={user} search="" />)
  await screen.findByText('О-191026-0001')
  fireEvent.click(screen.getByRole('button', { name: 'Новая заявка' }))
  await screen.findByText('Новая заявка', { selector: '.ant-modal-title' })
  return server
}

const text = () => screen.getByLabelText('Сообщение') as HTMLTextAreaElement
const create = () => fireEvent.click(screen.getByRole('button', { name: /Создать/ }))
const fill = (t: string) => fireEvent.change(text(), { target: { value: t } })

describe('the window of a new request', () => {
  it('creates the request, closes, puts it first in the list and opens its chat', async () => {
    const server = await open()
    fill('  Подтекает в ванной  ')

    create()

    // the chat of the new request has a field with the same label, so the window is found by its title
    await waitFor(() => expect(document.querySelector('.ant-modal-title')).toBeNull())
    const [[url, init]] = server.calls('POST', /^\/api\/requests$/)
    expect(url).toBe('/api/requests')
    expect(JSON.parse(String(init?.body))).toEqual({ text: 'Подтекает в ванной' })
    const rows = Array.from(document.querySelectorAll('[data-testid^="request-"]'))
    expect(rows[0].textContent).toMatch(/О-\d{6}-\d{4}/)
    expect(rows[0].textContent).not.toContain('О-191026-0001')
    expect(rows[0]).toHaveAttribute('aria-current', 'true')
    expect(await within(screen.getByTestId('chat-feed')).findByText('Подтекает в ванной')).toBeInTheDocument()
    expect(window.location.search).toMatch(/^\?request=\d+$/)
  })

  it('asks for the text', async () => {
    const server = await open()

    create()
    expect(await screen.findByText('Введите сообщение')).toBeInTheDocument()

    fill('   ')
    create()
    expect(await screen.findByText('Введите сообщение')).toBeInTheDocument()
    expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(0)
  })

  it('refuses a too long text', async () => {
    const server = await open()
    fill('с'.repeat(2001))

    create()

    expect(await screen.findByText('Сообщение длиннее 2000 символов')).toBeInTheDocument()
    expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(0)
  })

  it('has no field for a subject: the number of the request is its name', async () => {
    await open()
    expect(screen.queryByLabelText('Тема')).not.toBeInTheDocument()
    expect(screen.queryByText('Тема')).not.toBeInTheDocument()
    expect(text()).toBeInTheDocument()
  })

  it.each([
    [403, 'Недостаточно прав'],
    [409, 'К вашей учётной записи не привязан лицевой счёт'],
    [400, 'Проверьте сообщение'],
    [500, 'Не удалось создать заявку. Попробуйте позже'],
    [0, 'Не удалось создать заявку. Попробуйте позже'],
  ])('keeps the window open with the data and shows the message for %s', async (status, message) => {
    const server = await open()
    server.fail.create = status
    fill('Текст')

    create()

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(text().value).toBe('Текст')
  })

  it('creates nothing when the owner cancels', async () => {
    const server = await open()
    fill('Текст')

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    await waitFor(() => expect(screen.queryByLabelText('Сообщение')).not.toBeInTheDocument())
    expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(0)
  })

  it('does not ask for the apartment when the owner has one account', async () => {
    await open()
    expect(screen.queryByText('Квартира')).not.toBeInTheDocument()
  })

  it('asks which apartment when the owner has several accounts, and sends the chosen account', async () => {
    const server = await open({ ...OWNER, accounts: ['000000000001', '000000000002'] })
    fill('Текст')

    create()
    await waitFor(() => expect(document.querySelector('.ant-form-item-explain-error')?.textContent).toBe('Выберите квартиру'))
    expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(0)

    fireEvent.mouseDown(document.querySelector('.ant-modal .ant-select-selector') as Element)
    const options = await screen.findAllByText('Лицевой счёт № 000000000002')
    fireEvent.click(options[options.length - 1])
    create()

    await waitFor(() => expect(server.calls('POST', /^\/api\/requests$/)).toHaveLength(1))
    expect(JSON.parse(String(server.calls('POST', /^\/api\/requests$/)[0][1]?.body))).toEqual({
      text: 'Текст',
      account_number: '000000000002',
    })
  })
})
