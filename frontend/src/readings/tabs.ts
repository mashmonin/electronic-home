import type { Role } from '../auth/authApi'

export type Tab = 'readings' | 'receipts' | 'upload' | 'requests' | 'all-readings'

// The tab is kept in the address as ?tab=receipts, ?tab=requests or ?tab=all-readings (readings have no parameter); anything else means
// readings. The upload tab of an administrator is the one without a parameter.
export function tabFromSearch(search: string): Tab {
  const tab = new URLSearchParams(search).get('tab')
  return tab === 'receipts' || tab === 'requests' || tab === 'all-readings' ? tab : 'readings'
}

// What the role may open: an administrator the upload, the readings of all the apartments and the requests, an owner the readings, the receipts and the
// requests, a resident only the readings; the server refuses the rest too.
export function allowedTab(tab: Tab, role: Role): Tab {
  if (role === 'ADMIN') return tab === 'requests' || tab === 'all-readings' ? tab : 'upload'
  if (role === 'RESIDENT') return 'readings'
  return tab === 'upload' || tab === 'all-readings' ? 'readings' : tab
}

// Writes the tab into the address of the page without reloading it and without touching the other parameters.
// The selected request belongs to the requests tab, so it is dropped when another tab is written.
export function writeTab(tab: Tab): void {
  const url = new URL(window.location.href)
  if (tab === 'receipts' || tab === 'requests' || tab === 'all-readings') url.searchParams.set('tab', tab)
  else url.searchParams.delete('tab')
  if (tab !== 'requests') url.searchParams.delete('request')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

// The number of the selected request from ?request=, or null.
export function requestFromSearch(search: string): number | null {
  const value = new URLSearchParams(search).get('request')
  return value !== null && /^\d{1,15}$/.test(value) ? Number(value) : null
}

// Writes the selected request into the address (null removes it) without adding a history entry.
export function writeRequest(id: number | null): void {
  const url = new URL(window.location.href)
  if (id === null) url.searchParams.delete('request')
  else url.searchParams.set('request', String(id))
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}
