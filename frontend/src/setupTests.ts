import '@testing-library/jest-dom/vitest'

// The first getComputedStyle of an element makes jsdom match it against every rule of the antd styles (thousands of
// them), which cost ~2 s in every test that used a ByRole query or toBeVisible. The tests only need inline styles
// (display, visibility) and the `hidden` attribute, so the styles of the stylesheets are left out. The consequence: an
// element hidden only by a class (a closed antd dropdown) counts as visible, so check such things by the DOM instead.
window.getComputedStyle = (element: Element) => (element as HTMLElement).style

// antd uses matchMedia, which jsdom does not provide.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})
