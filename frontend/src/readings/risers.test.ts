import { describe, expect, it } from 'vitest'
import type { AccountMeter } from './accountApi'
import { buildRisers } from './risers'

const TODAY = new Date(2026, 9, 18)

function meter(number: string, type: 'HOT' | 'COLD', ...values: number[]): AccountMeter {
  return {
    number,
    water_type: type,
    verification_date: null,
    readings: values.map((value) => ({ value, reading_date: '2026-09-25', previous: null })),
  }
}

describe('buildRisers', () => {
  it('pairs a hot and a cold meter and takes the latest reading as previous', () => {
    expect(buildRisers([meter('Тест 1', 'HOT', 245.3, 230), meter('Тест 2', 'COLD', 512.1)])).toEqual([
      { hot: { number: 'Тест 1', previous: 245.3, verificationDate: undefined, expired: false }, cold: { number: 'Тест 2', previous: 512.1, verificationDate: undefined, expired: false } },
    ])
  })

  it('pairs the N-th hot meter with the N-th cold meter in API order', () => {
    const risers = buildRisers([
      meter('Х-1', 'COLD', 1),
      meter('Г-1', 'HOT', 2),
      meter('Г-2', 'HOT', 3),
      meter('Х-2', 'COLD', 4),
    ])

    expect(risers).toHaveLength(2)
    expect(risers[0]).toEqual({ hot: { number: 'Г-1', previous: 2, verificationDate: undefined, expired: false }, cold: { number: 'Х-1', previous: 1, verificationDate: undefined, expired: false } })
    expect(risers[1]).toEqual({ hot: { number: 'Г-2', previous: 3, verificationDate: undefined, expired: false }, cold: { number: 'Х-2', previous: 4, verificationDate: undefined, expired: false } })
  })

  it('leaves the missing side empty for an unpaired meter', () => {
    expect(buildRisers([meter('Г-1', 'HOT', 5)])).toEqual([{ hot: { number: 'Г-1', previous: 5, verificationDate: undefined, expired: false }, cold: undefined }])
    expect(buildRisers([meter('Х-1', 'COLD', 5)])).toEqual([{ hot: undefined, cold: { number: 'Х-1', previous: 5, verificationDate: undefined, expired: false } }])
  })

  it('returns no risers without meters', () => {
    expect(buildRisers([])).toEqual([])
  })

  it('has no previous value for a meter without readings', () => {
    expect(buildRisers([meter('Г-1', 'HOT')])).toEqual([{ hot: { number: 'Г-1', previous: undefined, verificationDate: undefined, expired: false }, cold: undefined }])
  })

  it('marks a meter as expired when its verification date has passed', () => {
    const risers = buildRisers(
      [{ ...meter('Г-1', 'HOT', 1), verification_date: '2028-01-01' }, { ...meter('Х-1', 'COLD', 2), verification_date: '2026-10-17' }],
      TODAY,
    )

    expect(risers[0].hot).toMatchObject({ verificationDate: '2028-01-01', expired: false })
    expect(risers[0].cold).toMatchObject({ verificationDate: '2026-10-17', expired: true })
  })

  it('does not mark a meter without a verification date or on its last day', () => {
    const risers = buildRisers([meter('Г-1', 'HOT', 1), { ...meter('Х-1', 'COLD', 2), verification_date: '2026-10-18' }], TODAY)

    expect(risers[0].hot).toMatchObject({ verificationDate: undefined, expired: false })
    expect(risers[0].cold).toMatchObject({ verificationDate: '2026-10-18', expired: false })
  })
})
