// jsdom has no layout, so antd takes the width of the screen from matchMedia. These make it wide or narrow.
const original = window.matchMedia

export function setWideScreen(wide: boolean): void {
  window.matchMedia = ((query: string) => ({
    matches: wide,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia
}

export function restoreScreen(): void {
  window.matchMedia = original
}
