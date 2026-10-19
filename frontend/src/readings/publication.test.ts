import { describe, expect, it } from 'vitest'
import { MAX_RECEIPT_SIZE, MESSAGES, hasErrors, validatePublication } from './publication'

const pdf = { name: 'receipt.pdf', type: 'application/pdf', size: 1000 }

describe('validatePublication', () => {
  it('accepts a PDF without amounts and with both amounts', () => {
    expect(validatePublication({ file: pdf, capitalRepair: null, utilities: null })).toEqual({})
    expect(validatePublication({ file: pdf, capitalRepair: 0, utilities: 12.5 })).toEqual({})
  })

  it('accepts a PDF by the extension or by the type alone', () => {
    expect(validatePublication({ file: { name: 'A.PDF', type: '', size: 1 }, capitalRepair: null, utilities: null })).toEqual({})
    expect(validatePublication({ file: { name: 'scan', type: 'application/pdf', size: 1 }, capitalRepair: null, utilities: null })).toEqual({})
  })

  it('refuses other files', () => {
    const errors = validatePublication({ file: { name: 'receipt.docx', type: 'application/msword', size: 1 }, capitalRepair: null, utilities: null })
    expect(errors.file).toBe(MESSAGES.notPdf)
  })

  it('refuses a file over 5 MB but accepts exactly 5 MB', () => {
    expect(validatePublication({ file: { ...pdf, size: MAX_RECEIPT_SIZE + 1 }, capitalRepair: null, utilities: null }).file).toBe(MESSAGES.tooBig)
    expect(validatePublication({ file: { ...pdf, size: MAX_RECEIPT_SIZE }, capitalRepair: null, utilities: null })).toEqual({})
  })

  it('refuses one amount without the other', () => {
    expect(validatePublication({ file: pdf, capitalRepair: 1, utilities: null }).amounts).toBe(MESSAGES.oneAmount)
    expect(validatePublication({ file: pdf, capitalRepair: null, utilities: 0 }).amounts).toBe(MESSAGES.oneAmount)
  })

  it('reports whether there are errors', () => {
    expect(hasErrors({})).toBe(false)
    expect(hasErrors({ file: 'x' })).toBe(true)
  })
})
