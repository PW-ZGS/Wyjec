import { useEffect, useState, type FormEvent } from 'react'
import { api, type PublicInfo, type Session } from '../api'

export function LoginPage({ onLogin }: { onLogin: (s: Session) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [info, setInfo] = useState<PublicInfo>()

  useEffect(() => { api.info().then(setInfo).catch(() => setInfo(undefined)) }, [])

  const login = async (u: string, p: string) => {
    setBusy(true)
    setError(undefined)
    try {
      onLogin(await api.login(u, p))
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    login(username, password)
  }

  return (
    <div className="login">
      <div className="hero">
        <img src="/logo-light.png" alt="Siren" />
        <h1>Task dispatching system during emergencies</h1>
        <div className="where">WHERE TO GO. <span>WHAT TO DO.</span></div>
        <p style={{ opacity: 0.75, maxWidth: 460, lineHeight: 1.6 }}>
          Predefined scenarios turn an alarm into coordinated action — for civil administration, medical services and the military.
        </p>
      </div>
      <form className="form" onSubmit={submit}>
        <div>
          <div className="eyebrow grey">Operations console</div>
          <h1 className="title normal">Sign in</h1>
        </div>
        <label className="field">Username<input type="text" value={username} onChange={e => setUsername(e.target.value)} autoFocus /></label>
        <label className="field">Password<input type="password" value={password} onChange={e => setPassword(e.target.value)} /></label>
        {error && <div className="error">{error}</div>}
        <button className="btn primary big" disabled={busy || !username}>{busy ? 'Signing in…' : 'Sign in'}</button>
        {info?.demo && (
          <div className="demo-accounts" style={{ marginTop: 16 }}>
            <div className="small muted" style={{ marginBottom: 8 }}>Demo accounts (password <span className="mono">siren</span>):</div>
            {info.demoAccounts.map(a => (
              <button type="button" key={a.username} className="btn" onClick={() => login(a.username, a.password)}>
                <strong>{a.username}</strong><span className="muted small">{a.who}</span>
              </button>
            ))}
          </div>
        )}
      </form>
    </div>
  )
}
