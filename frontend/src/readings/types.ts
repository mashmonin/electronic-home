export interface Meter {
  number: string
  /** Value of the latest reading; undefined while the meter has no readings. */
  previous?: number
  /** End of the verification period, `YYYY-MM-DD`; undefined when not known. */
  verificationDate?: string
  /** True when the verification period is over (compared with the system date). */
  expired: boolean
}

/** A card on the screen: the N-th hot water meter paired with the N-th cold water meter. */
export interface Riser {
  hot?: Meter
  cold?: Meter
}

export interface RiserValues {
  hot?: string
  cold?: string
}

/** A value for one meter; `value` is already normalized (dot as the decimal separator). */
export interface ReadingToSend {
  meterNumber: string
  value: string
}

/** A problem the server found in one reading; `meterNumber` is null for problems of the whole request. */
export interface ReadingServerError {
  meterNumber: string | null
  code: string
}

export type SubmitResult =
  | { status: 'ok' }
  | { status: 'invalid'; errors: ReadingServerError[] }
  | { status: 'not-found' }
  | { status: 'conflict' }
  | { status: 'error' }
