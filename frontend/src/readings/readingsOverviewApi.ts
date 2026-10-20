import { apiFetch } from '../auth/apiFetch'

export interface OverviewPeriod {
  /** `YYYY-MM`: the period of the readings, named after the month it ends in. */
  month: string
  start: string
  end: string
}

export interface OverviewReading {
  value: number
  reading_date: string
  previous_value: number | null
  /** The value minus the previous one; null for the first reading of a meter. */
  consumption: number | null
}

export interface OverviewMeter {
  number: string
  water_type: 'HOT' | 'COLD'
  /** Null while the meter has no reading in the month. */
  reading: OverviewReading | null
}

export interface OverviewApartment {
  /** Null while no personal account is made for the apartment. */
  account_number: string | null
  apartment: string
  address: string
  submitted: number
  total: number
  meters: OverviewMeter[]
}

export interface OverviewMonth {
  month: string
  start: string
  end: string
  apartments: OverviewApartment[]
}

export type PeriodsResult = { status: 'ok'; periods: OverviewPeriod[] } | { status: 'error' }
export type MonthResult = { status: 'ok'; month: OverviewMonth } | { status: 'error' }

// The months that have readings (and the current one), newest first; never throws.
export async function fetchPeriods(): Promise<PeriodsResult> {
  try {
    const response = await apiFetch('/api/readings/periods')
    if (!response.ok) return { status: 'error' }
    const body = (await response.json()) as { periods?: OverviewPeriod[] }
    return { status: 'ok', periods: body.periods ?? [] }
  } catch {
    return { status: 'error' }
  }
}

// Every apartment that has meters with the reading of each meter in the month; never throws.
export async function fetchOverviewMonth(month: string): Promise<MonthResult> {
  try {
    const response = await apiFetch(`/api/readings?month=${encodeURIComponent(month)}`)
    if (!response.ok) return { status: 'error' }
    return { status: 'ok', month: (await response.json()) as OverviewMonth }
  } catch {
    return { status: 'error' }
  }
}
