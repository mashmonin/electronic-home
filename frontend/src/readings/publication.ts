// The server accepts up to `receipts.max-size` (5 MB by default); the form only saves a request that would be refused.
export const MAX_RECEIPT_SIZE = 5 * 1024 * 1024

export interface PublicationInput {
  file: { name: string; type: string; size: number } | null
  capitalRepair: number | null
  utilities: number | null
}

export interface PublicationErrors {
  file?: string
  amounts?: string
}

export const MESSAGES = {
  notPdf: 'Выберите файл в формате PDF',
  tooBig: 'Файл больше 5 МБ',
  oneAmount: 'Укажите обе суммы или оставьте оба поля пустыми',
}

export function validatePublication({ file, capitalRepair, utilities }: PublicationInput): PublicationErrors {
  const errors: PublicationErrors = {}
  if (file) {
    const isPdf = file.name.toLowerCase().endsWith('.pdf') || file.type === 'application/pdf'
    if (!isPdf) errors.file = MESSAGES.notPdf
    else if (file.size > MAX_RECEIPT_SIZE) errors.file = MESSAGES.tooBig
  }
  if ((capitalRepair === null) !== (utilities === null)) errors.amounts = MESSAGES.oneAmount
  return errors
}

export const hasErrors = (errors: PublicationErrors): boolean => Object.keys(errors).length > 0
