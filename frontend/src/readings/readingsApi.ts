import type { ReadingsSubmission } from './types'

// STUB: the backend cannot save readings yet. Replace the body with a REST call
// and keep the signature so the components do not change.
export async function submitReadings(submission: ReadingsSubmission): Promise<void> {
  void submission
  await new Promise((resolve) => setTimeout(resolve, 300))
}
