import { useCallback, useEffect, useRef, useState } from 'react'
import { loadSession } from './api'

// One shared SSE connection per tab: browsers allow only ~6 HTTP/1.1 connections per host.
const changeListeners = new Set<() => void>()
const connectionListeners = new Set<(connected: boolean) => void>()
let source: EventSource | undefined
let connected = false
let timer: number | undefined

function setConnected(c: boolean) {
  connected = c
  connectionListeners.forEach(f => f(c))
}

function open() {
  const session = loadSession()
  if (source || !session) return
  source = new EventSource(`/api/admin/stream?token=${encodeURIComponent(session.token)}`)
  source.onopen = () => setConnected(true)
  source.onerror = () => setConnected(false)
  source.addEventListener('hello', () => setConnected(true))
  source.addEventListener('change', () => {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => changeListeners.forEach(f => f()), 250)
  })
}

/** Closed when the last subscriber leaves (e.g. logout), so a stale token is never retried. */
function closeIfUnused() {
  if (changeListeners.size > 0) return
  window.clearTimeout(timer)
  source?.close()
  source = undefined
  setConnected(false)
}

/** Subscribes to the backend's change stream; calls `onChange` (debounced) whenever data moved. */
export function useLiveUpdates(onChange: () => void) {
  const handler = useRef(onChange)
  handler.current = onChange
  const [isConnected, setIsConnected] = useState(connected)

  useEffect(() => {
    const fire = () => handler.current()
    changeListeners.add(fire)
    connectionListeners.add(setIsConnected)
    open()
    return () => {
      changeListeners.delete(fire)
      connectionListeners.delete(setIsConnected)
      closeIfUnused()
    }
  }, [])

  return isConnected
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
