import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { clearToken, getToken, setToken } from './auth/session'

afterEach(() => {
  vi.restoreAllMocks()
  clearToken()
  window.history.replaceState(null, '', '/')
})

const ACCOUNT = {
  number: '000000000001',
  address: 'ул. Тестовая, д. Тест, кв. 1',
  premise: { street: 'ул. Тестовая', house: 'Тест', apartment: '1' },
  owners: [{ full_name: 'Тестовый Тест Тестович' }],
  reading_period: { start: '2026-09-26', end: '2026-10-25', next_start: '2026-10-26', all_submitted: false, enforced: true },
  meters: [],
}

type Handler = (url: string, init?: RequestInit) => Response | undefined

// A fake server: the login and /me of the given role, the account, and anything else through `extra`.
function server(role: 'OWNER' | 'RESIDENT' | 'ADMIN' = 'OWNER', extra: Handler = () => undefined) {
  const me = { login: role.toLowerCase(), role, accounts: ['000000000001', '000000000002'] }
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input)
    const custom = extra(url, init)
    if (custom) return custom
    if (url === '/api/auth/login') {
      const body = JSON.parse(String(init?.body))
      return body.password === 'right'
        ? new Response(JSON.stringify({ access_token: 'token-1', token_type: 'Bearer', expires_in: 28800 }))
        : new Response(null, { status: 401 })
    }
    if (url === '/api/auth/me') return new Response(JSON.stringify(me))
    if (url.startsWith('/api/accounts/')) {
      const number = url.split('/')[3]
      return new Response(JSON.stringify({ ...ACCOUNT, number }))
    }
    return new Response(null, { status: 404 })
  })
}

async function signIn(password = 'right') {
  fireEvent.change(await screen.findByLabelText('Логин'), { target: { value: 'owner' } })
  fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Войти' }))
}

describe('login form', () => {
  it('is the first thing an anonymous user sees, and not the account', async () => {
    const fetchMock = server()
    render(<App />)

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(screen.getByLabelText('Логин')).toBeInTheDocument()
    expect(screen.getByLabelText('Пароль')).toHaveAttribute('type', 'password')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows only the name of the association in the header, without navigation', async () => {
    server()
    render(<App />)

    expect(await screen.findByText('ТСЖ «Комфорт»')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Показания' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Профиль' })).not.toBeInTheDocument()
  })

  it('keeps the button disabled until both fields are filled', async () => {
    server()
    render(<App />)
    const button = await screen.findByRole('button', { name: 'Войти' })
    expect(button).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Логин'), { target: { value: 'owner' } })
    expect(button).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: '   ' } })
    expect(button).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: 'right' } })
    await waitFor(() => expect(button).toBeEnabled())
  })

  it('opens the readings of the account of the user after a successful login', async () => {
    server()
    render(<App />)

    await signIn()

    expect(await screen.findByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
    expect(getToken()).toBe('token-1')
    expect(screen.queryByLabelText('Пароль')).not.toBeInTheDocument()
  })

  it('submits with Enter', async () => {
    server()
    render(<App />)
    fireEvent.change(await screen.findByLabelText('Логин'), { target: { value: 'owner' } })
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: 'right' } })

    fireEvent.submit(screen.getByLabelText('Пароль').closest('form') as HTMLFormElement)

    expect(await screen.findByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
  })

  it('keeps the login and clears the password after wrong credentials', async () => {
    server()
    render(<App />)

    await signIn('wrong')

    expect(await screen.findByText('Неверный логин или пароль')).toBeInTheDocument()
    expect(screen.getByLabelText('Логин')).toHaveValue('owner')
    expect(screen.getByLabelText('Пароль')).toHaveValue('')
    expect(getToken()).toBeNull()
  })

  it('says so when the server cannot be reached', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    render(<App />)

    await signIn()

    expect(await screen.findByText('Не удалось выполнить вход. Попробуйте позже')).toBeInTheDocument()
  })
})

describe('session', () => {
  it('stays signed in after a reload of the tab', async () => {
    setToken('token-1')
    server()
    render(<App />)

    expect(await screen.findByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
  })

  it('shows the login form with a notice when the stored token is refused', async () => {
    setToken('old')
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }))
    render(<App />)

    expect(await screen.findByText('Сессия истекла. Войдите снова')).toBeInTheDocument()
    expect(getToken()).toBeNull()
  })

  it('ends when a request fails with 401 while working', async () => {
    server('OWNER', (url) => (url.startsWith('/api/accounts/') ? new Response(null, { status: 401 }) : undefined))
    render(<App />)

    await signIn()

    expect(await screen.findByText('Сессия истекла. Войдите снова')).toBeInTheDocument()
    expect(getToken()).toBeNull()
    expect(screen.getByLabelText('Логин')).toBeInTheDocument()
  })

  it('keeps the session on 403 and says there are no rights', async () => {
    server('OWNER', (url) => (url.startsWith('/api/accounts/') ? new Response(null, { status: 403 }) : undefined))
    render(<App />)

    await signIn()

    expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()
    expect(getToken()).toBe('token-1')
  })

  it('signs out from the profile menu', async () => {
    server()
    render(<App />)
    await signIn()
    await screen.findByText('Лицевой счёт № 000000000001')

    fireEvent.click(screen.getByRole('button', { name: 'Профиль' }))
    fireEvent.click(await screen.findByRole('button', { name: /Выйти/ }))

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(getToken()).toBeNull()
    expect(screen.queryByText('Лицевой счёт № 000000000001')).not.toBeInTheDocument()
  })

  it('shows the full name of the owner instead of the login and the role in the profile menu', async () => {
    server('OWNER')
    render(<App />)
    await signIn()
    await screen.findByText('Лицевой счёт № 000000000001')

    fireEvent.click(screen.getByRole('button', { name: 'Профиль' }))

    expect(await screen.findByText('Собственник')).toBeInTheDocument()
    expect(screen.getByText('Тестовый Тест Тестович')).toBeInTheDocument()
    expect(screen.queryByText('owner')).not.toBeInTheDocument()
  })
})

describe('roles', () => {
  it('hides the receipts from a resident, even when the address asks for them', async () => {
    window.history.replaceState(null, '', '/?tab=receipts')
    const fetchMock = server('RESIDENT')
    render(<App />)

    await signIn()

    await screen.findByText('Лицевой счёт № 000000000001')
    expect(screen.queryByRole('button', { name: 'Квитанции' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Заявки' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Показания' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/receipts'))).toBe(false)
  })

  it('shows the readings and the receipts to an owner', async () => {
    server('OWNER')
    render(<App />)

    await signIn()

    await screen.findByText('Лицевой счёт № 000000000001')
    expect(screen.getByRole('button', { name: 'Показания' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Квитанции' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Заявки' })).toBeInTheDocument()
  })

  const emptyReceipts: Handler = (url) => (url.endsWith('/receipts') ? new Response(JSON.stringify({ receipts: [] })) : undefined)
  const apartments: Handler = (url) =>
    url === '/api/accounts'
      ? new Response(JSON.stringify({ accounts: [{ number: '000000000001', address: 'ул. Тестовая, д. Тест, кв. 1', apartment: '1' }] }))
      : undefined
  const handlers: Handler = (url, init) => emptyReceipts(url, init) ?? apartments(url, init)

  it('opens the upload tab for an administrator at once, with no readings, no receipts and no account', async () => {
    window.history.replaceState(null, '', '/?tab=receipts&account=000000000001')
    const fetchMock = server('ADMIN', handlers)
    render(<App />)

    await signIn()

    expect(await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })).toBeInTheDocument()
    expect(screen.getByText('Опубликовать квитанцию')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Показания' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Квитанции' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Лицевой счёт №/)).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => /^\/api\/accounts\/\d/.test(String(url)))).toBe(false)
  })

  it('opens the requests of an administrator by the address, and the upload tab otherwise', async () => {
    window.history.replaceState(null, '', '/?tab=requests')
    server('ADMIN', (url) => (url === '/api/requests' ? new Response(JSON.stringify({ requests: [] })) : undefined))
    render(<App />)

    await signIn()

    expect(await screen.findByText('Заявок пока нет')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Заявки' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows the upload tab again after a reload of the tab', async () => {
    setToken('token-1')
    server('ADMIN', handlers)
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Выставить квитанции' })).toBeInTheDocument()
  })

  it('does not give the upload tab to an owner or a resident, even by the address', async () => {
    for (const role of ['OWNER', 'RESIDENT'] as const) {
      window.history.replaceState(null, '', '/?tab=upload')
      const fetchMock = server(role, handlers)
      const { unmount } = render(<App />)
      await signIn()

      await screen.findByText('Лицевой счёт № 000000000001')
      expect(screen.queryByRole('button', { name: 'Выставить квитанции' })).not.toBeInTheDocument()
      expect(screen.queryByText('Опубликовать квитанцию')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Показания' })).toBeInTheDocument()
      expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/accounts')).toBe(false)
      unmount()
      clearToken()
    }
  })

  it('ignores ?account= for an owner', async () => {
    window.history.replaceState(null, '', '/?account=000000000002')
    server('OWNER')
    render(<App />)

    await signIn()

    expect(await screen.findByText('Лицевой счёт № 000000000001')).toBeInTheDocument()
  })
})
