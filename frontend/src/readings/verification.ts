function pad(value: number): string {
  return String(value).padStart(2, '0')
}

// The local calendar date of `today` as `YYYY-MM-DD` (not toISOString: that would shift the date by the UTC offset).
function localIsoDate(today: Date): string {
  return `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
}

// The verification period is over only after its last day: on `verificationDate` itself the meter is still valid.
// Both dates are compared as `YYYY-MM-DD` strings, whose order matches the calendar order.
export function isExpired(verificationDate: string | null | undefined, today: Date): boolean {
  if (!verificationDate) return false
  return verificationDate < localIsoDate(today)
}

// `2028-01-01` -> `01.01.2028`, or a dash when there is no date.
export function formatVerificationDate(verificationDate: string | null | undefined): string {
  if (!verificationDate) return '—'
  const [year, month, day] = verificationDate.split('-')
  return `${day}.${month}.${year}`
}
