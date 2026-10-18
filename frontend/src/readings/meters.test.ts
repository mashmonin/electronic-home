import { describe, expect, it } from 'vitest'
import type { AccountMeter } from './accountApi'
import { toMeters } from './meters'

const TODAY = new Date(2026, 9, 18)

function meter(number: string, type: 'HOT' | 'COLD', verificationDate: string | null = null, ...values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: verificationDate,
    current_period_reading: null,
    input_locked: false,
    readings: values.map((value) => ({ value, reading_date: '2026-09-25', previous: null })),
  }
}

describe('toMeters', () => {
  it('makes one entry per meter in the order of the API', () => {
    const result = toMeters([meter('Б', 'COLD'), meter('А', 'HOT'), meter('В', 'HOT')], TODAY)

    expect(result.map((m) => m.number)).toEqual(['Б', 'А', 'В'])
    expect(result.map((m) => m.waterType)).toEqual(['COLD', 'HOT', 'HOT'])
  })

  it('takes the latest reading as the previous value', () => {
    expect(toMeters([meter('Тест 1', 'HOT', null, 245.3, 230)], TODAY)[0].previous).toBe(245.3)
  })

  it('has no previous value for a meter without readings', () => {
    expect(toMeters([meter('Тест 1', 'HOT')], TODAY)[0].previous).toBeUndefined()
  })

  it('marks a meter as expired when its verification date has passed', () => {
    const result = toMeters([meter('Г-1', 'HOT', '2028-01-01'), meter('Х-1', 'COLD', '2026-10-17')], TODAY)

    expect(result[0]).toMatchObject({ verificationDate: '2028-01-01', expired: false })
    expect(result[1]).toMatchObject({ verificationDate: '2026-10-17', expired: true })
  })

  it('does not mark a meter without a date or on the last day of its period', () => {
    const result = toMeters([meter('Г-1', 'HOT'), meter('Х-1', 'COLD', '2026-10-18')], TODAY)

    expect(result[0]).toMatchObject({ verificationDate: undefined, expired: false })
    expect(result[1]).toMatchObject({ verificationDate: '2026-10-18', expired: false })
  })

  it('returns nothing without meters', () => {
    expect(toMeters([], TODAY)).toEqual([])
  })

  it('takes the state of the lock from the server and shows the submitted reading', () => {
    const submitted = {
      ...meter('Тест 1', 'HOT', null, 250.125, 245.3),
      current_period_reading: { value: 250.125, reading_date: '2026-10-18', previous: { value: 245.3, reading_date: '2026-09-25' } },
      input_locked: true,
    }

    const [result] = toMeters([submitted], TODAY)

    expect(result).toMatchObject({ locked: true, submitted: 250.125, previous: 245.3 })
  })

  it('does not lock a meter that has a reading of the period while the server says it is open', () => {
    const testing = {
      ...meter('Тест 1', 'HOT', null, 250.125, 245.3),
      current_period_reading: { value: 250.125, reading_date: '2026-10-18', previous: null },
      input_locked: false,
    }

    const [result] = toMeters([testing], TODAY)

    expect(result).toMatchObject({ locked: false, submitted: undefined, previous: 250.125 })
  })

  it('has no previous value for a locked first reading', () => {
    const first = {
      ...meter('Тест 1', 'HOT', null, 5),
      current_period_reading: { value: 5, reading_date: '2026-10-18', previous: null },
      input_locked: true,
    }

    expect(toMeters([first], TODAY)[0]).toMatchObject({ locked: true, submitted: 5, previous: undefined })
  })
})
