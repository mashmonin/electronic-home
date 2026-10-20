import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OWNER } from '../testUsers'
import { restoreScreen, setWideScreen } from '../testViewport'
import RequestsTab from './RequestsTab'
import { fakeRequests, request } from './fakeRequestsServer'

beforeEach(() => setWideScreen(true))
afterEach(() => {
  vi.restoreAllMocks()
  restoreScreen()
  window.history.replaceState(null, '', '/')
})

// n requests; the fake server cuts the pages of five like the real one and keeps the order it is given
const many = (n: number) => Array.from({ length: n }, (_, index) => request(index + 1, `О-191026-${String(index + 1).padStart(4, '0')}`))
const numbersOnScreen = () => Array.from(document.querySelectorAll('[data-testid^="request-"]')).map((row) => row.querySelector('strong')?.textContent)

describe('the pages of the list', () => {
  it('show five requests and the pager when there are more', async () => {
    fakeRequests({ requests: many(7) })
    render(<RequestsTab user={OWNER} search="" />)

    await screen.findByText('О-191026-0001')

    expect(numbersOnScreen()).toEqual(['О-191026-0001', 'О-191026-0002', 'О-191026-0003', 'О-191026-0004', 'О-191026-0005'])
    expect(document.querySelector('.ant-pagination')).not.toBeNull()
  })

  it('have no pager when everything fits on a page', async () => {
    fakeRequests({ requests: many(5) })
    render(<RequestsTab user={OWNER} search="" />)

    await screen.findByText('О-191026-0001')

    expect(document.querySelector('.ant-pagination')).toBeNull()
  })

  it('ask the server for the next page and show it', async () => {
    const server = fakeRequests({ requests: many(7) })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0001')

    fireEvent.click(document.querySelector('.ant-pagination-next') as HTMLElement)

    await screen.findByText('О-191026-0006')
    expect(numbersOnScreen()).toEqual(['О-191026-0006', 'О-191026-0007'])
    expect(server.calls('GET', /page=2/)).toHaveLength(1)
  })

  it('go back to the first page when the search changes', async () => {
    const server = fakeRequests({ requests: many(12) })
    render(<RequestsTab user={OWNER} search="" />)
    await screen.findByText('О-191026-0001')
    fireEvent.click(document.querySelector('.ant-pagination-next') as HTMLElement)
    await screen.findByText('О-191026-0006')

    fireEvent.change(screen.getByLabelText('Поиск по номеру'), { target: { value: '19' } })

    await waitFor(() => expect(server.calls('GET', /q=19/).length).toBeGreaterThan(0))
    await screen.findByText('О-191026-0001')
    expect(server.calls('GET', /q=19&page=2|page=2&q=19/)).toHaveLength(0)
  })
})
