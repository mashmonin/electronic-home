import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HealthStatus from './HealthStatus'

afterEach(() => vi.restoreAllMocks())

describe('HealthStatus', () => {
  it('shows success when backend reports UP', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'UP' })),
    )
    render(<HealthStatus />)
    expect(await screen.findByText('Сервис работает')).toBeInTheDocument()
  })

  it('shows an error when backend is unreachable', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network'))
    render(<HealthStatus />)
    expect(await screen.findByText('Нет связи с сервером')).toBeInTheDocument()
  })

  it('shows an error when backend reports DOWN', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'DOWN' }), { status: 503 }),
    )
    render(<HealthStatus />)
    expect(await screen.findByText('Нет связи с сервером')).toBeInTheDocument()
  })
})
