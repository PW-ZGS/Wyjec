import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, type InstanceDetail } from '../api'
import { MapView } from '../components/MapView'
import { RaiseDialog } from '../components/RaiseDialog'
import { Avatar, DomainChip, Legend, STATE_LABEL, domainLogo, elapsed, fmtTime, taskProgress } from '../components/ui'
import { useLiveData, useNow } from '../hooks'

/** Operations view from web-version.png: event header, live map, map references, legend. */
export function LivePage() {
  const { instanceId } = useParams()
  const navigate = useNavigate()
  const now = useNow()
  const active = useLiveData(api.active)
  const definitions = useLiveData(api.definitions)
  const selectedId = instanceId ?? active.data?.[0]?.id
  const detail = useLiveData<InstanceDetail | undefined>(
    () => (selectedId ? api.detail(selectedId) : Promise.resolve(undefined)),
    [selectedId],
  )
  const [open, setOpen] = useState<string>()
  const [raising, setRaising] = useState<string | boolean>(false)
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => setOpen(undefined), [selectedId])

  const d = detail.data
  const ended = !!d?.summary.cancelledAt
  const localTime = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

  if (!selectedId) {
    return (
      <>
        <div className="page-head">
          <div>
            <div className="eyebrow calm">All clear</div>
            <h1 className="title">No event in progress</h1>
          </div>
          <div className="head-right">Local time {localTime}</div>
        </div>
        <div className="live">
          <div className="map-frame"><MapView /></div>
          <div className="card refs">
            <h2>Ready to respond</h2>
            <p className="subtle">Scenarios are distributed to responders' phones and stored offline. Raising an alarm starts them instantly.</p>
            <div className="stack" style={{ marginTop: 8 }}>
              {(definitions.data ?? []).filter(x => x.canRaise).map(x => (
                <button key={x.id} className="person" onClick={() => setRaising(x.id)}>
                  <img src={domainLogo(x.domain)} alt="" style={{ height: 34 }} />
                  <div>
                    <div className="name">{x.name}</div>
                    <div className="role">{x.people} people · {x.tasks} tasks · v{x.version}</div>
                  </div>
                </button>
              ))}
            </div>
            <span className="spacer" />
            <button className="btn danger big" style={{ marginTop: 16 }} onClick={() => setRaising(true)}>Raise alarm</button>
          </div>
        </div>
        {raising && definitions.data && (
          <RaiseDialog definitions={definitions.data} initialId={typeof raising === 'string' ? raising : undefined} onClose={() => setRaising(false)}
            onRaised={id => { setRaising(false); navigate(`/live/${id}`) }} />
        )}
      </>
    )
  }

  const cancel = async () => {
    if (!d || !confirm(`Cancel ${d.summary.name} EV#${d.summary.displayNo}? All phones will receive a signed cancel.`)) return
    setCancelling(true)
    try { await api.cancel(d.summary.id) } catch (e) { alert((e as Error).message) } finally { setCancelling(false) }
  }

  const destinations = d
    ? [...new Map(d.participants.flatMap(p => p.groups.flatMap(g => (g.location ? [g.location] : []))).map(l => [l.id, l])).values()]
    : []
  const s = d?.summary.stats
  const canCancel = definitions.data?.find(x => x.id === d?.summary.definitionId)?.canCancel

  return (
    <>
      {(active.data?.length ?? 0) > 1 && (
        <div className="tabs">
          {active.data!.map(a => (
            <button key={a.id} className={`tab ${a.id === selectedId ? 'active' : ''}`} onClick={() => navigate(`/live/${a.id}`)}>
              {a.name} EV#{a.displayNo}
            </button>
          ))}
        </div>
      )}
      <div className="page-head">
        <div>
          <div className={`eyebrow ${ended ? 'grey' : ''}`}>{ended ? 'Event ended' : 'Event in progress'}</div>
          <h1 className="title">{d ? `${d.summary.name} EV#${d.summary.displayNo}` : '…'}</h1>
          {d && (
            <div className="row small muted" style={{ marginTop: 8 }}>
              <DomainChip domain={d.summary.domain} />
              <span>{d.summary.organizationName}</span>
              <span>· raised {fmtTime(d.summary.raisedAt)} by {d.summary.raisedBy ?? 'unknown'}</span>
              {ended && <span>· cancelled {fmtTime(d.summary.cancelledAt)} by {d.summary.cancelledBy}</span>}
            </div>
          )}
        </div>
        <div className="head-right">
          {d && !ended && <span>Elapsed {elapsed(d.summary.raisedAt, now)}</span>}
          <span>Local time {localTime}</span>
          {d && !ended && canCancel && (
            <button className="btn ghost-danger" disabled={cancelling} onClick={cancel}>Cancel alarm</button>
          )}
        </div>
      </div>

      {detail.error && <div className="error">{detail.error}</div>}

      <div className="live">
        <div className="map-frame">
          <MapView area={d?.summary.area} participants={d?.participants} destinations={destinations}
            fitKey={d ? d.summary.id : ''} onSelect={setOpen} />
        </div>
        <div className="card refs">
          <h2>Map references</h2>
          {d?.participants.map(p => {
            const prog = taskProgress(p)
            const isOpen = open === p.personId
            return (
              <div key={p.personId}>
                <button className={`person ${isOpen ? 'open' : ''}`} onClick={() => setOpen(isOpen ? undefined : p.personId)}>
                  <Avatar status={p.status} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="name">{p.displayName}</div>
                    <div className="role">Role: {p.role}</div>
                  </div>
                  <span className="small muted">{prog.done}/{prog.total}</span>
                </button>
                {isOpen && (
                  <ul className="tasklist">
                    <li className="muted">
                      {p.reception
                        ? `Received ${fmtTime(p.reception.receivedAt)} via ${p.reception.viaBearer ?? '?'}${p.reception.hopCount ? ` (${p.reception.hopCount} hops)` : ''}` +
                          (p.reception.acknowledgedAt ? ` · read ${fmtTime(p.reception.acknowledgedAt)}` : ' · not read yet')
                        : 'Not delivered yet'}
                    </li>
                    {p.groups.map(g => (
                      <li key={g.id} style={{ flexDirection: 'column', gap: 6 }}>
                        <strong>{g.name} — {g.location?.name}</strong>
                        {g.tasks.map(t => (
                          <span key={t.taskId} className="row" style={{ gap: 8 }}>
                            <span className={`state ${t.state}`}>{STATE_LABEL[t.state]}</span>
                            <span>{t.description}</span>
                          </span>
                        ))}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )
          })}
          <Legend />
        </div>
      </div>

      {s && (
        <div className="kpis">
          <Kpi label="Delivered" value={s.received} total={s.participants} />
          <Kpi label="Read" value={s.acknowledged} total={s.participants} />
          <Kpi label="Tasks done" value={s.tasksDone} total={s.tasksTotal} />
          <Kpi label="People finished" value={s.completed} total={s.participants} />
        </div>
      )}
    </>
  )
}

function Kpi({ label, value, total }: { label: string; value: number; total: number }) {
  return (
    <div className="kpi">
      <div className="l">{label}</div>
      <div className="v">{value} <small>/ {total}</small></div>
      <div className="bar"><span style={{ width: `${total ? (100 * value) / total : 0}%` }} /></div>
    </div>
  )
}
