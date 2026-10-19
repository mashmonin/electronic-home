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
