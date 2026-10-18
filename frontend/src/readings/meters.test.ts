import { describe, expect, it } from 'vitest'
import type { AccountMeter } from './accountApi'
import { toMeters } from './meters'

const TODAY = new Date(2026, 9, 18)

function meter(number: string, type: 'HOT' | 'COLD', verificationDate: string | null = null, ...values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: verificationDate,
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
})
