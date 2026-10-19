// What may be attached to a message; the server checks the same limits and the content of the files again.
export const MAX_FILES = 5
export const MAX_FILE_BYTES = 10 * 1024 * 1024
export const MAX_TOTAL_BYTES = 25 * 1024 * 1024
export const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'txt']
// for the file dialog of the browser
export const ACCEPT = ALLOWED_EXTENSIONS.map((extension) => `.${extension}`).join(',')

export interface FilesCheck {
  /** The chosen files that fit: the old ones and the new ones that passed. */
  files: File[]
  /** What was wrong with the files that did not pass, each text once. */
  problems: string[]
}

const extensionOf = (name: string): string => {
  const dot = name.lastIndexOf('.')
  return dot < 0 || dot === name.length - 1 ? '' : name.slice(dot + 1).toLowerCase()
}

// Adds the new files to the chosen ones, one by one, in the order they came; a file that does not pass is left out with
// the reason, the others stay.
export function addFiles(chosen: File[], added: File[]): FilesCheck {
  const files = [...chosen]
  const problems: string[] = []
  const note = (text: string) => {
    if (!problems.includes(text)) problems.push(text)
  }
  let total = files.reduce((sum, file) => sum + file.size, 0)
  for (const file of added) {
    if (files.length >= MAX_FILES) {
      note(`Не более ${MAX_FILES} файлов`)
    } else if (!ALLOWED_EXTENSIONS.includes(extensionOf(file.name))) {
      note(`Тип файла «${file.name}» не поддерживается`)
    } else if (file.size === 0) {
      note(`Файл «${file.name}» пуст`)
    } else if (file.size > MAX_FILE_BYTES) {
      note(`Файл «${file.name}» больше 10 МБ`)
    } else if (total + file.size > MAX_TOTAL_BYTES) {
      note('Общий размер файлов больше 25 МБ')
    } else {
      files.push(file)
      total += file.size
    }
  }
  return { files, problems }
}

// 340 КБ, 1,2 МБ, 10 МБ: the size of a file as people read it.
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} КБ`
  const megabytes = (bytes / (1024 * 1024)).toFixed(1).replace('.', ',').replace(/,0$/, '')
  return `${megabytes} МБ`
}
