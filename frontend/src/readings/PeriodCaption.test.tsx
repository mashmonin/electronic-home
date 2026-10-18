import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PeriodCaption from './PeriodCaption'
import type { ReadingPeriod } from './accountApi'
import type { Meter } from './types'

const meter = (locked: boolean): Meter => ({ number: 'М', waterType: 'HOT', expired: false, locked })

function period(end: string, allSubmitted = false): ReadingPeriod {
  return { start: '2026-01-01', end, next_start: '2026-01-01', all_submitted: allSubmitted, enforced: true }
}

describe('PeriodCaption', () => {
  it('asks for the readings while nothing is submitted', () => {
    render(<PeriodCaption period={period('2026-10-25')} meters={[meter(false), meter(false)]} />)

    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(screen.getByText('Передайте показания до 25 октября')).toBeInTheDocument()
  })

  it('tells when the readings are submitted and when new ones can be sent', () => {
    render(<PeriodCaption period={period('2026-10-25', true)} meters={[meter(true), meter(true)]} />)

    expect(screen.getByText('Показания за октябрь')).toBeInTheDocument()
    expect(screen.getByText('Показания сданы, новые можно будет отправить после 25 октября')).toBeInTheDocument()
  })

  it('tells when only a part of the readings is submitted', () => {
    render(<PeriodCaption period={period('2026-10-25')} meters={[meter(true), meter(false)]} />)

    expect(screen.getByText('Часть показаний сдана. Передайте остальные до 25 октября')).toBeInTheDocument()
  })

  it('names the month in which the period ends, also across the edge of a year', () => {
    const { rerender } = render(<PeriodCaption period={period('2027-01-25')} meters={[]} />)
    expect(screen.getByText('Показания за январь')).toBeInTheDocument()
    expect(screen.getByText('Передайте показания до 25 января')).toBeInTheDocument()

    rerender(<PeriodCaption period={period('2026-12-25', true)} meters={[]} />)
    expect(screen.getByText('Показания за декабрь')).toBeInTheDocument()
    expect(screen.getByText('Показания сданы, новые можно будет отправить после 25 декабря')).toBeInTheDocument()
  })

  it('is the title of the page: a large bold heading with the status under it, both aligned left', () => {
    const { container } = render(<PeriodCaption period={period('2026-10-25', true)} meters={[meter(true)]} />)

    const title = screen.getByRole('heading', { level: 1, name: 'Показания за октябрь' })
    expect(title).toHaveStyle({ fontSize: '26px' })
    const status = screen.getByText('Показания сданы, новые можно будет отправить после 25 октября')
    expect(status.tagName).toBe('STRONG')
    expect(status.parentElement).toHaveStyle({ fontSize: '15px' })
    expect(title.compareDocumentPosition(status)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(container.firstChild).toHaveStyle({ textAlign: 'left' })
    expect(container.firstChild).not.toHaveStyle({ textAlign: 'center' })
  })

  it('uses the closing day of the period it is given', () => {
    render(<PeriodCaption period={period('2026-03-05')} meters={[]} />)

    expect(screen.getByText('Показания за март')).toBeInTheDocument()
    expect(screen.getByText('Передайте показания до 5 марта')).toBeInTheDocument()
  })
})
