import { useEffect, useRef } from 'react'

// Calls `callback` every `intervalMs` while `enabled` and while the page of the browser is visible. Coming back to a
// visible page triggers one call at once. The interval stops when `enabled` turns false and when the component leaves.
export function usePolling(callback: () => void, intervalMs: number, enabled = true): void {
  const latest = useRef(callback)
  latest.current = callback

  useEffect(() => {
    if (!enabled) return
    const tick = () => {
      if (!document.hidden) latest.current()
    }
    const timer = setInterval(tick, intervalMs)
    const onVisible = () => {
      if (!document.hidden) latest.current()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [intervalMs, enabled])
}
