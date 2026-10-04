import { useCallback, useEffect, useRef, useState } from 'react'
import { loadSession } from './api'

/** Subscribes to the backend's change stream; calls `onChange` (debounced) whenever data moved. */
export function useLiveUpdates(onChange: () => void) {
  const handler = useRef(onChange)
  handler.current = onChange
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    const session = loadSession()
    if (!session) return
    let timer: number | undefined
    const source = new EventSource(`/api/admin/stream?token=${encodeURIComponent(session.token)}`)
    source.addEventListener('hello', () => setConnected(true))
    source.addEventListener('change', () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => handler.current(), 250)
    })
    source.onerror = () => setConnected(false)
    source.onopen = () => setConnected(true)
    return () => {
      window.clearTimeout(timer)
      source.close()
    }
  }, [])

  return connected
}

/** Loads data once, then reloads on every live change. */
export function useLiveData<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | undefined>()
  const [error, setError] = useState<string | undefined>()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const reload = useCallback(() => {
    load().then(d => { setData(d); setError(undefined) }).catch(e => setError(String(e.message ?? e)))
  }, deps)
  useEffect(reload, [reload])
  useLiveUpdates(reload)
  return { data, error, reload }
}

export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), intervalMs)
    return () => window.clearInterval(id)
  }, [intervalMs])
  return now
}
