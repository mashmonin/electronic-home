import type { ChatMessage, RequestItem } from './requestsApi'

// "19.10, 14:05" in the time zone of the browser; an unreadable value gives an empty string.
export function formatTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// Who wrote the message from the point of view of the reader: "Вы", the administration, or the login of the owner.
export function authorLabel(message: ChatMessage): string {
  if (message.mine) return 'Вы'
  return message.author.role === 'ADMIN' ? 'Администрация' : message.author.login
}

// "кв. 1 · owner": the line an administrator reads in the list and in the header of a chat.
export function ownerLabel(request: RequestItem): string {
  return `кв. ${request.account.apartment} · ${request.author.login}`
}

// "14:05" in the time zone of the browser; an unreadable value gives an empty string.
export function formatClock(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

// The calendar day of a message in the time zone of the browser, as a key to see where the day changes.
export function dayKey(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

// "19 октября", with the year when it is not the current one; the divider between the days of a dialog.
export function formatDay(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const sameYear = date.getFullYear() === new Date().getFullYear()
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', ...(sameYear ? {} : { year: 'numeric' }) })
}
