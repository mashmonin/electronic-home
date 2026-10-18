export interface Meter {
  previous: number
}

export interface Riser {
  meterNumber?: string
  hot: Meter
  cold: Meter
}

export interface Apartment {
  id: string
  address: string
  account: string
  risers: Riser[]
}

export interface RiserValues {
  hot?: string
  cold?: string
}

export interface ReadingsSubmission {
  apartmentId: string
  risers: RiserValues[]
}
