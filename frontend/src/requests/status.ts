import type { RequestItem, RequestStatus } from './requestsApi'

export const STATUS_LABELS: Record<RequestStatus, string> = {
  ACCEPTED: 'Принята',
  IN_PROGRESS: 'В работе',
  DONE: 'Выполнена',
  CLOSED: 'Закрыта',
}

export const STATUS_COLORS: Record<RequestStatus, string> = {
  ACCEPTED: 'blue',
  IN_PROGRESS: 'gold',
  DONE: 'green',
  CLOSED: 'default',
}

// The one step an administrator can make: its status and the caption of the button; none for a closed request.
// The step of a request about a verification from "accepted" is the confirmation of the new date.
export function nextStep(request: RequestItem): { status: RequestStatus; label: string } | null {
  switch (request.status) {
    case 'ACCEPTED':
      return { status: 'IN_PROGRESS', label: request.kind === 'VERIFICATION' ? 'Подтвердить' : 'Взять в работу' }
    case 'IN_PROGRESS':
      return { status: 'DONE', label: 'Выполнено' }
    case 'DONE':
      return { status: 'CLOSED', label: 'Закрыть' }
    default:
      return null
  }
}
