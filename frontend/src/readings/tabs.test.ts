import { afterEach, describe, expect, it } from 'vitest'
import { allowedTab, requestFromSearch, tabFromSearch, writeRequest, writeTab } from './tabs'

afterEach(() => window.history.replaceState(null, '', '/'))

describe('tabFromSearch', () => {
  it('opens the receipts only for tab=receipts', () => {
    expect(tabFromSearch('?tab=receipts')).toBe('receipts')
    expect(tabFromSearch('?account=000000000001&tab=receipts')).toBe('receipts')
  })

  it('opens the requests for tab=requests', () => {
    expect(tabFromSearch('?tab=requests')).toBe('requests')
    expect(tabFromSearch('?tab=requests&request=3')).toBe('requests')
  })

  it('does not take the upload from the address: an administrator has only that tab', () => {
    expect(tabFromSearch('?tab=upload')).toBe('readings')
  })

  it('opens the readings for everything else', () => {
    expect(tabFromSearch('')).toBe('readings')
    expect(tabFromSearch('?account=000000000001')).toBe('readings')
    expect(tabFromSearch('?tab=unknown')).toBe('readings')
    expect(tabFromSearch('?tab=')).toBe('readings')
    expect(tabFromSearch('?tab=RECEIPTS')).toBe('readings')
  })
})

describe('allowedTab', () => {
  it('leaves an administrator the upload and the requests only', () => {
    expect(allowedTab('readings', 'ADMIN')).toBe('upload')
    expect(allowedTab('receipts', 'ADMIN')).toBe('upload')
    expect(allowedTab('upload', 'ADMIN')).toBe('upload')
    expect(allowedTab('requests', 'ADMIN')).toBe('requests')
  })

  it('lets an owner open the requests and a resident not', () => {
    expect(allowedTab('requests', 'OWNER')).toBe('requests')
    expect(allowedTab('requests', 'RESIDENT')).toBe('readings')
  })

  it('lets an owner open the readings and the receipts, but not the upload', () => {
    expect(allowedTab('readings', 'OWNER')).toBe('readings')
    expect(allowedTab('receipts', 'OWNER')).toBe('receipts')
    expect(allowedTab('upload', 'OWNER')).toBe('readings')
  })

  it('lets a resident open only the readings', () => {
    expect(allowedTab('receipts', 'RESIDENT')).toBe('readings')
    expect(allowedTab('upload', 'RESIDENT')).toBe('readings')
    expect(allowedTab('readings', 'RESIDENT')).toBe('readings')
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

  it('writes nothing for the upload tab', () => {
    window.history.replaceState(null, '', '/?account=000000000001')

    writeTab('upload')

    expect(window.location.search).toBe('?account=000000000001')
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

describe('the selected request', () => {
  it('is read from the address when it is a number', () => {
    expect(requestFromSearch('?tab=requests&request=12')).toBe(12)
    expect(requestFromSearch('')).toBeNull()
    expect(requestFromSearch('?request=abc')).toBeNull()
    expect(requestFromSearch('?request=')).toBeNull()
    expect(requestFromSearch('?request=-1')).toBeNull()
    expect(requestFromSearch('?request=1e3')).toBeNull()
  })

  it('is written into the address and removed from it, without history entries', () => {
    window.history.replaceState(null, '', '/?tab=requests')
    const before = window.history.length

    writeRequest(7)
    expect(window.location.search).toBe('?tab=requests&request=7')
    writeRequest(null)
    expect(window.location.search).toBe('?tab=requests')
    expect(window.history.length).toBe(before)
  })

  it('goes away when another tab is written, and stays when the requests are written', () => {
    window.history.replaceState(null, '', '/?account=000000000001&tab=requests&request=7')

    writeTab('requests')
    expect(window.location.search).toBe('?account=000000000001&tab=requests&request=7')
    writeTab('receipts')
    expect(window.location.search).toBe('?account=000000000001&tab=receipts')
  })
})

describe('the tab of the readings of all the apartments', () => {
  it('is taken from tab=all-readings', () => {
    expect(tabFromSearch('?tab=all-readings')).toBe('all-readings')
  })

  it('is open to an administrator only', () => {
    expect(allowedTab('all-readings', 'ADMIN')).toBe('all-readings')
    expect(allowedTab('all-readings', 'OWNER')).toBe('readings')
    expect(allowedTab('all-readings', 'RESIDENT')).toBe('readings')
  })

  it('is written into the address', () => {
    writeTab('all-readings')
    expect(window.location.search).toBe('?tab=all-readings')
    writeTab('readings')
    expect(window.location.search).toBe('')
  })
})
