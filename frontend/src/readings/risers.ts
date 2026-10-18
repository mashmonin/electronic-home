import type { AccountMeter } from './accountApi'
import type { Meter, Riser } from './types'
import { isExpired } from './verification'

function toMeter(meter: AccountMeter | undefined, today: Date): Meter | undefined {
  if (!meter) return undefined
  return {
    number: meter.number,
    previous: meter.readings[0]?.value,
    verificationDate: meter.verification_date ?? undefined,
    expired: isExpired(meter.verification_date, today),
  }
}

// The database has no risers: the N-th hot water meter and the N-th cold water meter form "Стояк N".
export function buildRisers(meters: AccountMeter[], today: Date = new Date()): Riser[] {
  const hot = meters.filter((meter) => meter.water_type === 'HOT')
  const cold = meters.filter((meter) => meter.water_type === 'COLD')
  return Array.from({ length: Math.max(hot.length, cold.length) }, (_, index) => ({
    hot: toMeter(hot[index], today),
    cold: toMeter(cold[index], today),
  }))
}
