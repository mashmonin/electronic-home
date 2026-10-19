/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Two ways to run the dev server:
//  - in a container behind nginx (https://localhost): BEHIND_NGINX=true, HMR goes through nginx's TLS port;
//  - directly from a terminal (http://localhost:5173): /api is proxied to the backend started from the IDE.
const behindNginx = process.env.BEHIND_NGINX === 'true'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    ...(behindNginx && { hmr: { protocol: 'wss', clientPort: 443 } }),
    proxy: {
      '/api': process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // the screen tests type into antd forms and take several seconds on a busy machine
    testTimeout: 20000,
    setupFiles: './src/setupTests.ts',
  },
})
