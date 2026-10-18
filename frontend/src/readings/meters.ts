import type { AccountMeter } from './accountApi'
import type { Meter } from './types'
import { isExpired } from './verification'

// One card per meter, in the order of the API (by number). Whether a meter is locked is decided by the server.
export function toMeters(meters: AccountMeter[], today: Date = new Date()): Meter[] {
  return meters.map((meter) => {
    const locked = meter.input_locked && meter.current_period_reading !== null
    return {
      number: meter.number,
      waterType: meter.water_type,
      previous: locked ? meter.current_period_reading?.previous?.value : meter.readings[0]?.value,
      verificationDate: meter.verification_date ?? undefined,
      expired: isExpired(meter.verification_date, today),
      locked,
      submitted: locked ? meter.current_period_reading?.value : undefined,
    }
  })
}
