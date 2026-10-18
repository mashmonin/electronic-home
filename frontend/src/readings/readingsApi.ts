import type { Apartment, ReadingsSubmission } from './types'

// STUB: the backend has no readings API yet. Replace the bodies with REST calls
// and keep the signatures so the components do not change.

const apartments: Apartment[] = [
  {
    id: 'sadovaya-12-47',
    address: 'ул. Садовая, д. 12, кв. 47',
    account: '0004521873',
    risers: [
      {
        hot: { previous: 245.3 },
        cold: { previous: 512.1 },
      },
      {
        hot: { previous: 133.45 },
        cold: { previous: 289.7 },
      },
    ],
  },
  {
    id: 'sadovaya-12-48',
    address: 'ул. Садовая, д. 12, кв. 48',
    account: '0004521874',
    risers: [
      {
        hot: { previous: 98.2 },
        cold: { previous: 187.65 },
      },
    ],
  },
]

export async function getApartments(): Promise<Apartment[]> {
  return apartments
}

export async function submitReadings(submission: ReadingsSubmission): Promise<void> {
  void submission
  await new Promise((resolve) => setTimeout(resolve, 300))
}
