import type { ReadingsSubmission, Riser } from './types'

// STUB: the backend has no meters or readings yet. Replace the bodies with REST calls
// and keep the signatures so the components do not change.

const risers: Riser[] = [
  {
    hot: { previous: 245.3 },
    cold: { previous: 512.1 },
  },
  {
    hot: { previous: 133.45 },
    cold: { previous: 289.7 },
  },
]

export async function getRisers(): Promise<Riser[]> {
  return risers
}

export async function submitReadings(submission: ReadingsSubmission): Promise<void> {
  void submission
  await new Promise((resolve) => setTimeout(resolve, 300))
}
