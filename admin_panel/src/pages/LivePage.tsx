import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, type AlarmDefinition, type InstanceDetail } from '../api'
import { MapView } from '../components/MapView'
import { Avatar, DomainChip, Legend, STATE_LABEL, elapsed, fmtTime, taskProgress } from '../components/ui'
import { useLiveData, useNow } from '../hooks'

/** No active alarm: raise form only. Active alarm: live map, responders, progress. */
export function LivePage() {
  const { instanceId } = useParams()
  const navigate = useNavigate()
  const now = useNow()
  const active = useLiveData(api.active)
  const definitions = useLiveData(api.definitions)
  const selected = active.data?.find(a => a.id === instanceId) ?? active.data?.[0]
  const selectedId = selected?.id
  const detail = useLiveData<InstanceDetail | undefined>(
    () => (selectedId ? api.detail(selectedId) : Promise.resolve(undefined)),
    [selectedId],
  )
  const [open, setOpen] = useState<string>()
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => setOpen(undefined), [selectedId])

  if (!active.data) return null
  if (!selected) return <RaiseForm definitions={definitions.data ?? []} onRaised={id => navigate(`/live/${id}`)} />

  const d = detail.data?.summary.id === selectedId ? detail.data : undefined

  const cancel = async () => {
    if (!confirm(`End ${selected.name}?`)) return
    setCancelling(true)
    try { await api.cancel(selected.id) } catch (e) { alert((e as Error).message) } finally { setCancelling(false) }
  }

  const destinations = d
    ? [...new Map(d.participants.flatMap(p => p.groups.flatMap(g => (g.location ? [g.location] : []))).map(l => [l.id, l])).values()]
    : []
  const participants = [...(d?.participants ?? [])].sort((a, b) => Number(b.blocked) - Number(a.blocked))
  const s = d?.summary.stats
  const canCancel = definitions.data?.find(x => x.id === selected.definitionId)?.canCancel

  return (
    <>
      {active.data.length > 1 && (
        <div className="tabs">
          {active.data.map(a => (
            <button key={a.id} className={`tab ${a.id === selectedId ? 'active' : ''}`} onClick={() => navigate(`/live/${a.id}`)}>
              {a.name} EV#{a.displayNo}
            </button>
          ))}
        </div>
      )}
      <div className="page-head">
        <div>
          <div className="eyebrow">Alarm in progress</div>
          <h1 className="title">{selected.name}</h1>
          {selected.description && <div className="description">{selected.description}</div>}
          <div className="row small muted" style={{ marginTop: 8 }}>
            <DomainChip domain={selected.domain} />
            <span>raised {fmtTime(selected.raisedAt)} by {selected.raisedBy ?? 'unknown'}</span>
          </div>
        </div>
        <div className="head-right">
          <span>Elapsed {elapsed(selected.raisedAt, now)}</span>
          {canCancel && <button className="btn ghost-danger" disabled={cancelling} onClick={cancel}>End alarm</button>}
        </div>
      </div>

      {detail.error && <div className="error">{detail.error}</div>}

      <div className="live">
        <div className="map-frame">
          <MapView participants={d?.participants} destinations={destinations} fitKey={d ? d.summary.id : ''} onSelect={setOpen} />
        </div>
        <div className="card refs">
          <h2>Responders</h2>
          {participants.map(p => {
            const prog = taskProgress(p)
            const isOpen = open === p.personId
            return (
              <div key={p.personId}>
                <button className={`person ${isOpen ? 'open' : ''}`} onClick={() => setOpen(isOpen ? undefined : p.personId)}>
                  <Avatar status={p.status} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="name">{p.displayName}</div>
                    <div className="role">{p.role}</div>
                  </div>
                  {p.blocked && <span className="chip bad">NEED HELP</span>}
                  <span className="small muted">{prog.done}/{prog.total}</span>
                </button>
                {isOpen && (
                  <ul className="tasklist">
                    <li className="muted">
                      {p.reception
                        ? `Received ${fmtTime(p.reception.receivedAt)} via ${p.reception.viaBearer ?? '?'}${p.reception.hopCount ? ` (${p.reception.hopCount} hops)` : ''}` +
                          (p.reception.acknowledgedAt ? ` · acknowledged ${fmtTime(p.reception.acknowledgedAt)}` : ' · not acknowledged') +
                          (p.reception.helpRequestedAt ? ` · help requested ${fmtTime(p.reception.helpRequestedAt)}` : '')
                        : 'Not delivered'}
                    </li>
                    {p.groups.map(g => (
                      <li key={g.id} style={{ flexDirection: 'column', gap: 6 }}>
                        <strong>{g.location?.name ?? g.name}</strong>
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
          <Kpi label="Acknowledged" value={s.acknowledged} total={s.participants} />
          <Kpi label="Tasks done" value={s.tasksDone} total={s.tasksTotal} />
          <Kpi label="People finished" value={s.completed} total={s.participants} />
        </div>
      )}
    </>
  )
}

/** Type + short description + raise. Alarms are system-wide: no area. */
function RaiseForm({ definitions, onRaised }: { definitions: AlarmDefinition[]; onRaised: (instanceId: string) => void }) {
  const raisable = definitions.filter(d => d.canRaise)
  const [picked, setPicked] = useState<string>()
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const id = picked ?? raisable[0]?.id

  const raise = async () => {
    if (!id) return
    setBusy(true)
    setError(undefined)
    try {
      onRaised((await api.raise(id, description)).summary.id)
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow calm">No active alarm</div>
          <h1 className="title">Raise alarm</h1>
        </div>
      </div>
      <div className="card stack" style={{ maxWidth: 560 }}>
        <label className="field">
          Alarm type
          <select value={id} onChange={e => setPicked(e.target.value)}>
            {raisable.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <label className="field">
          Description
          <input type="text" maxLength={200} value={description} onChange={e => setDescription(e.target.value)} />
        </label>
        {error && <div className="error">{error}</div>}
        <button className="btn danger big" disabled={!id || busy} onClick={raise}>Raise alarm</button>
      </div>
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
