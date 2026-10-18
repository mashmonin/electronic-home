import { NOMINATIVE } from './months'

// "2026-10" -> "Октябрь 2026", without Date, so no time zone can shift the month.
export function formatReceiptMonth(month: string): string {
  const [year, number] = month.split('-')
  const name = NOMINATIVE[Number(number) - 1] ?? month
  return `${name.charAt(0).toUpperCase()}${name.slice(1)} ${year}`
}

// 1842.5 -> "1 842,50 ₽" (a non-breaking space between thousands, a decimal comma); an unknown amount is a dash.
export function formatMoney(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—'
  const [whole, cents] = amount.toFixed(2).split('.')
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ' ')},${cents} ₽`
}
