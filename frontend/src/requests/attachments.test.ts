import { describe, expect, it } from 'vitest'
import { ACCEPT, MAX_FILE_BYTES, MAX_FILES, MAX_TOTAL_BYTES, addFiles, formatSize } from './attachments'

const MB = 1024 * 1024
const file = (name: string, size = 100) => {
  const result = new File(['x'], name)
  Object.defineProperty(result, 'size', { value: size })
  return result
}

describe('addFiles', () => {
  it('accepts the allowed types, in any case', () => {
    const names = ['a.jpg', 'b.JPEG', 'c.png', 'd.gif', 'e.webp', 'f.pdf', 'g.doc', 'h.docx', 'i.xls', 'j.xlsx', 'k.txt']
    const { files, problems } = addFiles([], names.slice(0, 5).map((name) => file(name)))
    expect(files).toHaveLength(5)
    expect(problems).toEqual([])
    expect(addFiles([], [file('h.DOCX'), file('K.TXT')]).files).toHaveLength(2)
    expect(names).toHaveLength(11)
  })

  it('leaves out a type that is not allowed and says which file it is', () => {
    const { files, problems } = addFiles([], [file('good.pdf'), file('скрипт.exe'), file('page.html'), file('noextension')])
    expect(files.map((f) => f.name)).toEqual(['good.pdf'])
    expect(problems).toEqual(['Тип файла «скрипт.exe» не поддерживается', 'Тип файла «page.html» не поддерживается', 'Тип файла «noextension» не поддерживается'])
  })

  it('does not take svg, zip or a file whose name ends with a dot', () => {
    expect(addFiles([], [file('a.svg'), file('b.zip'), file('c.')]).files).toEqual([])
  })

  it('leaves out an empty file', () => {
    const { files, problems } = addFiles([], [file('empty.pdf', 0)])
    expect(files).toEqual([])
    expect(problems).toEqual(['Файл «empty.pdf» пуст'])
  })

  it('leaves out a file over 10 MB but takes one of exactly 10 MB', () => {
    expect(addFiles([], [file('big.pdf', MAX_FILE_BYTES + 1)]).problems).toEqual(['Файл «big.pdf» больше 10 МБ'])
    expect(addFiles([], [file('edge.pdf', MAX_FILE_BYTES)]).files).toHaveLength(1)
  })

  it('takes at most five files and says it once', () => {
    const chosen = addFiles([], ['a', 'b', 'c', 'd', 'e'].map((n) => file(`${n}.pdf`))).files
    const { files, problems } = addFiles(chosen, [file('f.pdf'), file('g.pdf')])
    expect(MAX_FILES).toBe(5)
    expect(files).toHaveLength(5)
    expect(problems).toEqual(['Не более 5 файлов'])
  })

  it('keeps the chosen files when the new ones do not pass', () => {
    const first = addFiles([], [file('a.pdf')]).files
    const { files } = addFiles(first, [file('bad.exe')])
    expect(files.map((f) => f.name)).toEqual(['a.pdf'])
  })

  it('stops at 25 MB together', () => {
    expect(MAX_TOTAL_BYTES).toBe(25 * MB)
    const chosen = addFiles([], [file('a.pdf', 9 * MB), file('b.pdf', 9 * MB)]).files
    const { files, problems } = addFiles(chosen, [file('c.pdf', 8 * MB)])
    expect(files).toHaveLength(2)
    expect(problems).toEqual(['Общий размер файлов больше 25 МБ'])
    expect(addFiles(chosen, [file('c.pdf', 7 * MB)]).files).toHaveLength(3)
  })

  it('takes the files that fit in the order they came', () => {
    const { files, problems } = addFiles([], [file('a.pdf', 9 * MB), file('big.pdf', 12 * MB), file('b.pdf', 9 * MB), file('c.pdf', 9 * MB)])
    expect(files.map((f) => f.name)).toEqual(['a.pdf', 'b.pdf'])
    expect(problems).toEqual(['Файл «big.pdf» больше 10 МБ', 'Общий размер файлов больше 25 МБ'])
  })

  it('offers the allowed extensions to the file dialog', () => {
    expect(ACCEPT).toBe('.jpg,.jpeg,.png,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.txt')
  })
})

describe('formatSize', () => {
  it('writes bytes, kilobytes and megabytes the way people read them', () => {
    expect(formatSize(0)).toBe('0 Б')
    expect(formatSize(512)).toBe('512 Б')
    expect(formatSize(340 * 1024)).toBe('340 КБ')
    expect(formatSize(1.2 * MB)).toBe('1,2 МБ')
    expect(formatSize(10 * MB)).toBe('10 МБ')
    expect(formatSize(25 * MB)).toBe('25 МБ')
  })
})
