import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../auth/authApi'
import { ADMIN, OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { type FakeRequests, fakeRequests, message, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

async function open(user: Me, overrides: Parameters<typeof request>[2] = {}, number = 'О-191026-0001'): Promise<FakeRequests> {
  const server = fakeRequests({
    requests: [request(1, number, overrides)],
    messages: { 1: [message(11, 'Подтекает кран')] },
    viewer: { role: user.role, login: user.login },
  })
  render(<RequestsTab user={user} search="?request=1" />)
  await screen.findByText('Подтекает кран')
  return server
}

const stepButton = (name: string) => screen.getByRole('button', { name })
const field = () => screen.getByLabelText('Сообщение') as HTMLTextAreaElement
const type = (text: string) => fireEvent.change(field(), { target: { value: text } })

describe('the status of a request', () => {
  it('is shown as a label in the list and in the chat, the type of the request has no label', async () => {
    await open(OWNER, { status: 'IN_PROGRESS' })

    expect(screen.getAllByText('В работе')).toHaveLength(2)
    expect(screen.queryByText('Обращение')).not.toBeInTheDocument()
    expect(screen.queryByText('Проверка счетчика')).not.toBeInTheDocument()
  })

  it('shows no type label for a request about a verification either', async () => {
    await open(OWNER, { kind: 'VERIFICATION' }, 'П-201026-0002')

    expect(screen.queryByText('Проверка счетчика')).not.toBeInTheDocument()
    expect(screen.getAllByText('П-201026-0002').length).toBeGreaterThan(0)
  })

  it('give an owner no buttons of the administrator', async () => {
    await open(OWNER)

    for (const name of ['Взять в работу', 'Подтвердить', 'Выполнено', 'Закрыть']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })
})

describe('the steps of the administrator', () => {
  it('go to "Выполнена" and stop there, with no "Закрыть"', async () => {
    const server = await open(ADMIN)

    fireEvent.click(stepButton('Взять в работу'))
    await waitFor(() => expect(stepButton('Выполнено')).toBeInTheDocument())
    fireEvent.click(stepButton('Выполнено'))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Выполнено' })).not.toBeInTheDocument())
    expect(screen.getAllByText('Выполнена').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Закрыть' })).not.toBeInTheDocument()
    expect(screen.queryByText('Закрыта')).not.toBeInTheDocument()
    expect(server.calls('PUT', /\/api\/requests\/1\/status/).map(([, init]) => JSON.parse(String(init?.body)))).toEqual([
      { status: 'IN_PROGRESS' },
      { status: 'DONE' },
    ])
  })

  it('put the thanks into the chat when the work is done', async () => {
    await open(ADMIN, { status: 'IN_PROGRESS' })

    fireEvent.click(stepButton('Выполнено'))

    expect((await screen.findAllByText('Благодарим за обращение, заявка выполнена.')).length).toBeGreaterThan(0)
    expect(screen.getByTestId('chat-feed')).toHaveTextContent('Благодарим за обращение, заявка выполнена.')
  })

  it('name the first step of a request about a verification "Подтвердить"', async () => {
    await open(ADMIN, { kind: 'VERIFICATION' }, 'П-201026-0002')

    expect(stepButton('Подтвердить')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Взять в работу' })).not.toBeInTheDocument()
    fireEvent.click(stepButton('Подтвердить'))
    await waitFor(() => expect(stepButton('Выполнено')).toBeInTheDocument())
  })

  it('show the failure and keep the status when the server refuses the step', async () => {
    const server = await open(ADMIN)
    server.fail.status = 409

    fireEvent.click(stepButton('Взять в работу'))

    expect(await screen.findByText('Статус заявки уже изменён или шаг невозможен')).toBeInTheDocument()
    expect(stepButton('Взять в работу')).toBeInTheDocument()
  })
})

describe('the field of the administrator', () => {
  it('is shut until the request is taken into work', async () => {
    await open(ADMIN)

    expect(field()).toBeDisabled()
    expect(screen.getByRole('button', { name: /Отправить/ })).toBeDisabled()
    expect(screen.getByText('Чтобы писать в чат, возьмите заявку в работу')).toBeInTheDocument()

    fireEvent.click(stepButton('Взять в работу'))

    await waitFor(() => expect(field()).toBeEnabled())
    expect(screen.queryByText('Чтобы писать в чат, возьмите заявку в работу')).not.toBeInTheDocument()
  })

  it('asks to confirm a request about a verification', async () => {
    await open(ADMIN, { kind: 'VERIFICATION' }, 'П-201026-0002')

    expect(screen.getByText('Чтобы писать в чат, подтвердите заявку')).toBeInTheDocument()
    expect(field()).toBeDisabled()
  })

  it('is open while the request is in work', async () => {
    await open(ADMIN, { status: 'IN_PROGRESS' })

    expect(field()).toBeEnabled()
  })
})

describe('a request that is done', () => {
  it('shuts the field for the administrator and shows the panel "Заявка выполнена"', async () => {
    await open(ADMIN, { status: 'DONE' })

    expect(screen.getByText('Заявка выполнена')).toBeInTheDocument()
    expect(screen.queryByText('Заявка закрыта')).not.toBeInTheDocument()
    expect(field()).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Вернуть в работу' })).not.toBeInTheDocument()
  })

  it('shuts the field for the owner and offers "Вернуть в работу"', async () => {
    await open(OWNER, { status: 'DONE' })

    expect(screen.getByText('Заявка выполнена')).toBeInTheDocument()
    expect(field()).toBeDisabled()
    expect(stepButton('Вернуть в работу')).toBeInTheDocument()
  })

  it('opens the field after "Вернуть в работу" but keeps the status until the message is sent', async () => {
    const server = await open(OWNER, { status: 'DONE' })

    fireEvent.click(stepButton('Вернуть в работу'))

    expect(field()).toBeEnabled()
    expect(screen.getByText('Опишите, почему заявку нужно вернуть в работу')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Вернуть в работу' })).toBeDisabled()
    expect(screen.getAllByText('Выполнена').length).toBeGreaterThan(0)
    expect(server.calls('POST', /reopen/)).toHaveLength(0)
  })

  it('does not return without a message', async () => {
    await open(OWNER, { status: 'DONE' })
    fireEvent.click(stepButton('Вернуть в работу'))

    type('   ')

    expect(screen.getByRole('button', { name: 'Вернуть в работу' })).toBeDisabled()
  })

  it('returns to the work with the message', async () => {
    const server = await open(OWNER, { status: 'DONE' })
    fireEvent.click(stepButton('Вернуть в работу'))

    type('Не помогло')
    fireEvent.click(screen.getByRole('button', { name: 'Вернуть в работу' }))

    expect(await screen.findByText('Не помогло')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Заявка выполнена')).not.toBeInTheDocument())
    expect(screen.getAllByText('В работе').length).toBeGreaterThan(0)
    expect(field()).toBeEnabled()
    expect(server.calls('POST', /\/api\/requests\/1\/reopen/)).toHaveLength(1)
  })

  it('goes back to the shut field on "Отмена"', async () => {
    const server = await open(OWNER, { status: 'DONE' })
    fireEvent.click(stepButton('Вернуть в работу'))

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(field()).toBeDisabled()
    expect(screen.getByText('Заявка выполнена')).toBeInTheDocument()
    expect(server.calls('POST', /reopen/)).toHaveLength(0)
  })

  it('keeps the text and the status when the server refuses the return', async () => {
    const server = await open(OWNER, { status: 'DONE' })
    server.fail.reopen = 500
    fireEvent.click(stepButton('Вернуть в работу'))
    type('Не помогло')

    fireEvent.click(screen.getByRole('button', { name: 'Вернуть в работу' }))

    expect(await screen.findByText('Не удалось вернуть заявку в работу. Попробуйте позже')).toBeInTheDocument()
    expect(field().value).toBe('Не помогло')
    expect(screen.getAllByText('Выполнена').length).toBeGreaterThan(0)
  })
})
