import { describe, expect, it } from 'vitest'
import { formatMoney, formatReceiptMonth } from './receiptFormat'

const NBSP = ' '

describe('formatMoney', () => {
  it('writes roubles with a decimal comma, two digits after it and the ruble sign', () => {
    expect(formatMoney(1842.5)).toBe(`1${NBSP}842,50${NBSP}₽`)
    expect(formatMoney(6318.27)).toBe(`6${NBSP}318,27${NBSP}₽`)
    expect(formatMoney(5)).toBe(`5,00${NBSP}₽`)
  })

  it('groups thousands with a non-breaking space', () => {
    expect(formatMoney(999)).toBe(`999,00${NBSP}₽`)
    expect(formatMoney(1000)).toBe(`1${NBSP}000,00${NBSP}₽`)
    expect(formatMoney(1234567.8)).toBe(`1${NBSP}234${NBSP}567,80${NBSP}₽`)
    expect(formatMoney(9999999999.99)).toBe(`9${NBSP}999${NBSP}999${NBSP}999,99${NBSP}₽`)
  })

  it('shows zero as an amount', () => {
    expect(formatMoney(0)).toBe(`0,00${NBSP}₽`)
  })

  it('rounds to kopecks', () => {
    expect(formatMoney(0.005)).toBe(`0,01${NBSP}₽`)
    expect(formatMoney(2.499)).toBe(`2,50${NBSP}₽`)
  })

  it('shows a dash when the amount is not known', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined)).toBe('—')
  })
})

describe('formatReceiptMonth', () => {
  it('writes the month with a capital letter and the year', () => {
    expect(formatReceiptMonth('2026-10')).toBe('Октябрь 2026')
    expect(formatReceiptMonth('2026-01')).toBe('Январь 2026')
    expect(formatReceiptMonth('2027-12')).toBe('Декабрь 2027')
  })

  it('keeps what it cannot read', () => {
    expect(formatReceiptMonth('2026-13')).toBe('2026-13 2026')
  })
})
