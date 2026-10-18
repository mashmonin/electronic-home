import { describe, expect, it } from 'vitest'
import { formatVerificationDate, isExpired } from './verification'

const date = (y: number, m: number, d: number, h = 12, min = 0) => new Date(y, m - 1, d, h, min)

describe('isExpired', () => {
  it('is true when the verification date is before today', () => {
    expect(isExpired('2026-10-17', date(2026, 10, 18))).toBe(true)
  })

  it('is false on the verification date itself', () => {
    expect(isExpired('2026-10-18', date(2026, 10, 18))).toBe(false)
  })

  it('is false when the verification date is after today', () => {
    expect(isExpired('2028-01-01', date(2026, 10, 18))).toBe(false)
    expect(isExpired('2026-10-19', date(2026, 10, 18))).toBe(false)
  })

  it('compares calendar dates across month and year boundaries', () => {
    expect(isExpired('2026-09-30', date(2026, 10, 1))).toBe(true)
    expect(isExpired('2025-12-31', date(2026, 1, 1))).toBe(true)
    expect(isExpired('2026-01-01', date(2025, 12, 31))).toBe(false)
  })

  it('compares dates only, whatever the time of day', () => {
    expect(isExpired('2026-10-18', date(2026, 10, 18, 0, 0))).toBe(false)
    expect(isExpired('2026-10-18', date(2026, 10, 18, 23, 59))).toBe(false)
    expect(isExpired('2026-10-17', date(2026, 10, 18, 0, 0))).toBe(true)
    expect(isExpired('2026-10-17', date(2026, 10, 17, 23, 59))).toBe(false)
  })

  it('is false when there is no verification date', () => {
    expect(isExpired(null, date(2026, 10, 18))).toBe(false)
    expect(isExpired(undefined, date(2026, 10, 18))).toBe(false)
    expect(isExpired('', date(2026, 10, 18))).toBe(false)
  })
})

describe('formatVerificationDate', () => {
  it('formats a date as DD.MM.YYYY', () => {
    expect(formatVerificationDate('2028-01-01')).toBe('01.01.2028')
    expect(formatVerificationDate('2026-10-17')).toBe('17.10.2026')
  })

  it('shows a dash without a date', () => {
    expect(formatVerificationDate(undefined)).toBe('—')
    expect(formatVerificationDate(null)).toBe('—')
  })
})
