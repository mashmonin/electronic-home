export type Tab = 'readings' | 'receipts'

// The tab is kept in the address as ?tab=receipts (readings have no parameter); anything else means readings.
export function tabFromSearch(search: string): Tab {
  return new URLSearchParams(search).get('tab') === 'receipts' ? 'receipts' : 'readings'
}

// Writes the tab into the address of the page without reloading it and without touching the other parameters.
export function writeTab(tab: Tab): void {
  const url = new URL(window.location.href)
  if (tab === 'receipts') url.searchParams.set('tab', 'receipts')
  else url.searchParams.delete('tab')
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}
