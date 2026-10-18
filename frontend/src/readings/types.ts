export interface Meter {
  number: string
  /** Value of the latest reading; undefined while the meter has no readings. */
  previous?: number
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

export interface ReadingsSubmission {
  accountNumber: string
  risers: RiserValues[]
}
