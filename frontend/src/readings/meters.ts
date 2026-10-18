import type { AccountMeter } from './accountApi'
import type { Meter } from './types'
import { isExpired } from './verification'

// One card per meter, in the order of the API (by number).
export function toMeters(meters: AccountMeter[], today: Date = new Date()): Meter[] {
  return meters.map((meter) => ({
    number: meter.number,
    waterType: meter.water_type,
    previous: meter.readings[0]?.value,
    verificationDate: meter.verification_date ?? undefined,
    expired: isExpired(meter.verification_date, today),
  }))
}
