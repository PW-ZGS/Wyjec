import { Link } from 'react-router-dom'
import { api } from '../api'
import { fmtDateTime } from '../components/ui'
import { useLiveData } from '../hooks'

export function EventLogPage() {
  const events = useLiveData(api.events)
  const keys = useLiveData(api.keys)
  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow grey">Audit</div>
          <h1 className="title normal">Alarm event log</h1>
          <p className="subtle">Append-only record of every signed raise / cancel the server has seen — from the console, a phone, or relayed over the mesh.</p>
        </div>
      </div>
      <div className="card">
        <table>
          <thead>
            <tr><th>Received</th><th>Event</th><th>Alarm</th><th>Origin</th><th>First bearer</th><th>Key epoch</th><th>Signature</th><th>Verified</th></tr>
          </thead>
          <tbody>
            {events.data?.map(e => (
              <tr key={e.id}>
                <td className="small">{fmtDateTime(e.receivedAt)}<div className="muted">signed {fmtDateTime(e.createdAt)}</div></td>
                <td><span className={`chip ${e.eventType === 'RAISE' ? 'bad' : 'ok'}`}>{e.eventType}</span></td>
                <td>
                  <strong>{e.name}</strong>{' '}
                  {e.displayNo && <Link to={`/live/${e.alarmInstanceId}`} className="muted">EV#{e.displayNo}</Link>}
                </td>
                <td>{e.originName ?? 'unknown'}<div className="small muted">{e.originPlatform === 'DESKTOP' ? 'console' : 'phone'}</div></td>
                <td><span className="chip">{e.firstBearer ?? '—'}</span></td>
                <td className="mono">#{e.keyEpochId}</td>
                <td className="mono muted" title={e.signatureB64}>{e.signatureB64.slice(0, 18)}…</td>
                <td>{e.verified ? <span className="chip ok">✓ verified</span> : <span className="chip bad">✗ rejected</span>}</td>
              </tr>
            ))}
            {events.data?.length === 0 && <tr><td colSpan={8} className="muted">No alarm events yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ marginTop: 24 }}>
        <h2>Alarm key epochs</h2>
        <p className="subtle" style={{ marginTop: -8 }}>Private keys stay in the HSM and reach only alarm controllers. NEXT is pre-distributed so offline phones survive rotation.</p>
        <table>
          <thead><tr><th>Epoch</th><th>Organization</th><th>Status</th><th>Valid from</th><th>Valid to</th><th>Public key fingerprint</th></tr></thead>
          <tbody>
            {keys.data?.map(k => (
              <tr key={k.id}>
                <td className="mono">#{k.id}</td>
                <td>{k.organizationName}</td>
                <td><span className={`chip ${k.status === 'CURRENT' ? 'ok' : k.status === 'NEXT' ? 'warn' : ''}`}>{k.status}</span></td>
                <td className="small">{fmtDateTime(k.validFrom)}</td>
                <td className="small">{fmtDateTime(k.validTo)}</td>
                <td className="mono">{k.publicKeySha256}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
