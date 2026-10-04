import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { RaiseDialog } from '../components/RaiseDialog'
import { DomainChip, domainLogo, fmtDateTime } from '../components/ui'
import { useLiveData } from '../hooks'

export function AlarmsPage() {
  const navigate = useNavigate()
  const definitions = useLiveData(api.definitions)
  const history = useLiveData(api.history)
  const [raiseId, setRaiseId] = useState<string>()

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow grey">Alarms</div>
          <h1 className="title normal">Raise or cancel an alarm</h1>
          <p className="subtle">Each alarm runs its predefined scenario. Only authorized controllers can raise or cancel it.</p>
        </div>
      </div>
      {definitions.error && <div className="error">{definitions.error}</div>}
      <div className="grid cols-3">
        {definitions.data?.map(d => (
          <div key={d.id} className={`card alarm-card ${d.activeInstanceId ? 'active' : ''}`}>
            <div className="top">
              <img src={domainLogo(d.domain)} alt="" />
              <div>
                <div className="name">{d.name}</div>
                <div className="small muted">{d.organizationName}</div>
              </div>
            </div>
            <div className="row"><DomainChip domain={d.domain} /><span className="chip mono">{d.code}</span><span className="chip">v{d.version}</span></div>
            <div className="meta">
              <span><b>{d.groups}</b>task groups</span>
              <span><b>{d.tasks}</b>tasks</span>
              <span><b>{d.people}</b>people</span>
            </div>
            <div className="row">
              {d.activeInstanceId ? (
                <Link className="btn danger" to={`/live/${d.activeInstanceId}`}>● Active — open live view</Link>
              ) : d.canRaise ? (
                <button className="btn danger" onClick={() => setRaiseId(d.id)}>Raise</button>
              ) : (
                <span className="small muted">You are not a controller for this alarm</span>
              )}
              <span className="spacer" />
              <Link className="btn small" to={`/scenarios/${d.id}`}>Scenario</Link>
            </div>
          </div>
        ))}
      </div>

      <div className="card" style={{ marginTop: 28 }}>
        <h2>History</h2>
        <table>
          <thead>
            <tr><th>Event</th><th>Organization</th><th>Raised</th><th>Cancelled</th><th>Delivered</th><th>Tasks</th><th /></tr>
          </thead>
          <tbody>
            {history.data?.map(h => (
              <tr key={h.id}>
                <td><strong>{h.name}</strong> <span className="muted">EV#{h.displayNo}</span></td>
                <td>{h.organizationName}</td>
                <td>{fmtDateTime(h.raisedAt)}<div className="small muted">{h.raisedBy}</div></td>
                <td>{h.cancelledAt ? <>{fmtDateTime(h.cancelledAt)}<div className="small muted">{h.cancelledBy}</div></> : <span className="chip bad">active</span>}</td>
                <td>{h.stats?.received}/{h.stats?.participants}</td>
                <td>{h.stats?.tasksDone}/{h.stats?.tasksTotal}</td>
                <td><Link className="btn small" to={`/live/${h.id}`}>Open</Link></td>
              </tr>
            ))}
            {history.data?.length === 0 && <tr><td colSpan={7} className="muted">No alarms raised yet.</td></tr>}
          </tbody>
        </table>
      </div>

      {raiseId && definitions.data && (
        <RaiseDialog definitions={definitions.data} initialId={raiseId} onClose={() => setRaiseId(undefined)}
          onRaised={id => navigate(`/live/${id}`)} />
      )}
    </>
  )
}
