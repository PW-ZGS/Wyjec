import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { api, loadSession, saveSession, setUnauthorizedHandler, type Session } from './api'
import { useLiveData, useLiveUpdates } from './hooks'
import { LivePage } from './pages/LivePage'
import { LoginPage } from './pages/LoginPage'
import { ScenariosPage } from './pages/ScenariosPage'

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  useEffect(() => {
    setUnauthorizedHandler(() => { saveSession(null); setSession(null) })
  }, [])

  if (!session) {
    return <LoginPage onLogin={s => { saveSession(s); setSession(s) }} />
  }

  const logout = () => {
    api.logout().catch(() => {})
    saveSession(null)
    setSession(null)
  }

  return (
    <BrowserRouter>
      <Shell session={session} onLogout={logout}>
        <Routes>
          <Route path="/" element={<Navigate to="/live" replace />} />
          <Route path="/live" element={<LivePage />} />
          <Route path="/live/:instanceId" element={<LivePage />} />
          <Route path="/scenarios" element={<ScenariosPage />} />
          <Route path="/scenarios/:definitionId" element={<ScenariosPage />} />
          <Route path="*" element={<Navigate to="/live" replace />} />
        </Routes>
      </Shell>
    </BrowserRouter>
  )
}

function Shell({ session, onLogout, children }: { session: Session; onLogout: () => void; children: React.ReactNode }) {
  const active = useLiveData(api.active)
  const connected = useLiveUpdates(() => {})
  const count = active.data?.length ?? 0
  return (
    <div className="shell">
      <header className="topbar">
        <NavLink to="/live" className="brand"><img src="/logo.png" alt="" />SIREN</NavLink>
        <nav className="nav">
          <NavLink to="/live">Live{count > 0 && <span className="badge">{count}</span>}</NavLink>
          <NavLink to="/scenarios">Scenarios</NavLink>
        </nav>
        <div className="who">
          <div style={{ textAlign: 'right' }}>
            <strong>{session.displayName}</strong>
            {session.organizationName}
          </div>
          <button className="btn small" onClick={onLogout}>Log out</button>
        </div>
      </header>
      <main className="content">{children}</main>
      <footer className="footer">
        <span><span className={`dot ${connected ? 'on' : ''}`} /> &nbsp;{connected ? 'Connected' : 'Disconnected'}</span>
        <span className="secure">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
          Secure connection
        </span>
      </footer>
    </div>
  )
}
