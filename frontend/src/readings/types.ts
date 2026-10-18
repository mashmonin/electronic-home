export interface Meter {
  previous: number
}

export interface Riser {
  meterNumber?: string
  hot: Meter
  cold: Meter
}

export interface RiserValues {
  hot?: string
  cold?: string
}

export interface ReadingsSubmission {
  accountNumber: string
  risers: RiserValues[]
}
