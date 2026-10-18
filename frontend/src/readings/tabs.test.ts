import { afterEach, describe, expect, it } from 'vitest'
import { tabFromSearch, writeTab } from './tabs'

afterEach(() => window.history.replaceState(null, '', '/'))

describe('tabFromSearch', () => {
  it('opens the receipts only for tab=receipts', () => {
    expect(tabFromSearch('?tab=receipts')).toBe('receipts')
    expect(tabFromSearch('?account=000000000001&tab=receipts')).toBe('receipts')
  })

  it('opens the readings for everything else', () => {
    expect(tabFromSearch('')).toBe('readings')
    expect(tabFromSearch('?account=000000000001')).toBe('readings')
    expect(tabFromSearch('?tab=unknown')).toBe('readings')
    expect(tabFromSearch('?tab=')).toBe('readings')
    expect(tabFromSearch('?tab=RECEIPTS')).toBe('readings')
  })
})

describe('writeTab', () => {
  it('puts the tab into the address and keeps the other parameters and the path', () => {
    window.history.replaceState(null, '', '/app?account=000000000001#top')

    writeTab('receipts')

    expect(window.location.pathname).toBe('/app')
    expect(window.location.search).toBe('?account=000000000001&tab=receipts')
    expect(window.location.hash).toBe('#top')
  })

  it('removes the tab for the readings and keeps the account', () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=receipts')

    writeTab('readings')

    expect(window.location.search).toBe('?account=000000000001')
  })

  it('leaves an address without parameters clean', () => {
    window.history.replaceState(null, '', '/?tab=receipts')

    writeTab('readings')

    expect(window.location.search).toBe('')
  })

  it('does not add history entries', () => {
    const before = window.history.length

    writeTab('receipts')
    writeTab('readings')

    expect(window.history.length).toBe(before)
  })
})
