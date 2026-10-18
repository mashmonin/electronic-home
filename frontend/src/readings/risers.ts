import type { AccountMeter } from './accountApi'
import type { Meter, Riser } from './types'

function toMeter(meter: AccountMeter | undefined): Meter | undefined {
  if (!meter) return undefined
  return { number: meter.number, previous: meter.readings[0]?.value }
}

// The database has no risers: the N-th hot water meter and the N-th cold water meter form "Стояк N".
export function buildRisers(meters: AccountMeter[]): Riser[] {
  const hot = meters.filter((meter) => meter.water_type === 'HOT')
  const cold = meters.filter((meter) => meter.water_type === 'COLD')
  return Array.from({ length: Math.max(hot.length, cold.length) }, (_, index) => ({
    hot: toMeter(hot[index]),
    cold: toMeter(cold[index]),
  }))
}
