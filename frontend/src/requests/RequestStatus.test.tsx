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

describe('the status of a request', () => {
  it('is shown as a label in the list and in the chat', async () => {
    await open(OWNER, { status: 'IN_PROGRESS' })

    expect(screen.getAllByText('В работе')).toHaveLength(2)
  })

  it('has no buttons for an owner', async () => {
    await open(OWNER)

    for (const name of ['Взять в работу', 'Подтвердить', 'Выполнено', 'Закрыть']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
  })

  it('goes one step at a time for an administrator, up to the closed request', async () => {
    const server = await open(ADMIN)

    fireEvent.click(stepButton('Взять в работу'))
    await waitFor(() => expect(stepButton('Выполнено')).toBeInTheDocument())
    fireEvent.click(stepButton('Выполнено'))
    await waitFor(() => expect(stepButton('Закрыть')).toBeInTheDocument())
    fireEvent.click(stepButton('Закрыть'))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Закрыть' })).not.toBeInTheDocument())
    expect(screen.getAllByText('Закрыта').length).toBeGreaterThan(0)
    expect(server.calls('PUT', /\/api\/requests\/1\/status/).map(([, init]) => JSON.parse(String(init?.body)))).toEqual([
      { status: 'IN_PROGRESS' },
      { status: 'DONE' },
      { status: 'CLOSED' },
    ])
  })

  it('names the first step of a request about a verification "Подтвердить"', async () => {
    await open(ADMIN, { kind: 'VERIFICATION' }, 'П-201026-0002')

    expect(stepButton('Подтвердить')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Взять в работу' })).not.toBeInTheDocument()
    fireEvent.click(stepButton('Подтвердить'))
    await waitFor(() => expect(stepButton('Выполнено')).toBeInTheDocument())
  })

  it('shows the failure and keeps the status when the server refuses the step', async () => {
    const server = await open(ADMIN)
    server.fail.status = 409

    fireEvent.click(stepButton('Взять в работу'))

    expect(await screen.findByText('Статус заявки уже изменён или шаг невозможен')).toBeInTheDocument()
    expect(stepButton('Взять в работу')).toBeInTheDocument()
  })

  it('shuts the field of a closed request', async () => {
    await open(OWNER, { status: 'CLOSED' })

    expect(screen.getByText('Заявка закрыта')).toBeInTheDocument()
    expect(screen.getByLabelText('Сообщение')).toBeDisabled()
    expect(screen.getByRole('button', { name: /Отправить/ })).toBeDisabled()
  })
})
