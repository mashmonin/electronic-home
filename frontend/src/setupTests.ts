import '@testing-library/jest-dom/vitest'
import { configure } from '@testing-library/react'

// antd forms render slowly in jsdom; with the whole suite running in parallel the default 1 s is too tight.
configure({ asyncUtilTimeout: 5000 })

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
