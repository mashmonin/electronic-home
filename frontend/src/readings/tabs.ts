import type { Role } from '../auth/authApi'

export type Tab = 'readings' | 'receipts' | 'upload'

// The tab is kept in the address as ?tab=receipts (readings have no parameter); anything else means readings.
// The tab of an administrator never shows in the address: he has only one.
export function tabFromSearch(search: string): Tab {
  return new URLSearchParams(search).get('tab') === 'receipts' ? 'receipts' : 'readings'
}

// What the role may open: an administrator only uploads, a resident has no receipts; the server refuses the rest too.
export function allowedTab(tab: Tab, role: Role): Tab {
  if (role === 'ADMIN') return 'upload'
  if (role === 'RESIDENT') return 'readings'
  return tab === 'upload' ? 'readings' : tab
}

// Writes the tab into the address of the page without reloading it and without touching the other parameters.
export function writeTab(tab: Tab): void {
  const url = new URL(window.location.href)
  if (tab === 'receipts') url.searchParams.set('tab', 'receipts')
  else url.searchParams.delete('tab')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}
