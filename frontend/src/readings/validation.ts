const READING_PATTERN = /^\d+(\.\d{1,3})?$/

export function normalizeReading(value: string): string {
  return value.trim().replace(',', '.')
}

export function isFilled(value: string | undefined): value is string {
  return value !== undefined && value.trim() !== ''
}

// Returns an error message, or undefined when the value is empty or valid.
// `previous` is undefined for meters that have no previous reading yet.
export function validateReading(value: string | undefined, previous?: number): string | undefined {
  if (!isFilled(value)) return undefined
  const normalized = normalizeReading(value)
  if (!READING_PATTERN.test(normalized)) {
    return 'Введите число, не более трёх знаков после запятой'
  }
  if (previous !== undefined && Number(normalized) < previous) {
    return 'Показание не может быть меньше предыдущего'
  }
  return undefined
}

export function formatReading(value: number): string {
  return value.toFixed(3)
}
