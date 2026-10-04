import { useState } from 'react'
import { api, type InstanceDetail, type Participant, type SimulatedDevice, type TaskState } from '../api'
import { DomainChip, STATE_LABEL, ago, firstName } from '../components/ui'
import { useLiveData } from '../hooks'

/**
 * Stand-in phones for a one-laptop demo. Autopilot plays every "simulated" responder; any phone can
 * also be driven by hand. Untick a person to leave them to a real Android device.
 */
export function SimulatorPage() {
  const sim = useLiveData(api.simulator)
  const active = useLiveData(api.active)
  const [chosen, setChosen] = useState<string>()
  const instanceId = chosen ?? active.data?.[0]?.id
  const detail = useLiveData<InstanceDetail | undefined>(
    () => (instanceId ? api.detail(instanceId) : Promise.resolve(undefined)),
    [instanceId],
  )
  const [error, setError] = useState<string>()

  const isIn = (personId: string) => !!detail.data?.participants.some(p => p.personId === personId)
  const run = (p: Promise<unknown>) => p.then(() => { sim.reload(); detail.reload() }).catch(e => setError(e.message))

  if (sim.data && !sim.data.demo) return <div className="notice">The simulator is available only with the demo profile.</div>

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow grey">Demo</div>
          <h1 className="title normal">Phone simulator</h1>
          <p className="subtle">Responders' phones for a one-laptop demo. Real Android phones work side by side — untick "autopilot" for them.</p>
        </div>
        <div className="head-right">
          <label className="switch">
            <input type="checkbox" checked={sim.data?.autopilot ?? false} onChange={e => run(api.setAutopilot(e.target.checked))} />
            Autopilot
          </label>
        </div>
      </div>
      {error && <div className="error" style={{ marginBottom: 16 }}>{error}</div>}
      {(active.data?.length ?? 0) > 1 && (
        <div className="tabs">
          {active.data!.map(a => (
            <button key={a.id} className={`tab ${a.id === instanceId ? 'active' : ''}`} onClick={() => setChosen(a.id)}>
              {a.name} EV#{a.displayNo}
            </button>
          ))}
        </div>
      )}
      {!instanceId && <div className="notice" style={{ marginBottom: 16 }}>No active alarm. Raise one on the Live or Alarms page — phones assigned in its scenario light up here.</div>}
      <div className="phones">
        {[...(sim.data?.devices ?? [])]
          .sort((a, b) => Number(isIn(b.personId)) - Number(isIn(a.personId)))
          .map(d => (
          <Phone key={d.deviceId} device={d} detail={detail.data}
            participant={detail.data?.participants.find(p => p.personId === d.personId)} run={run} />
        ))}
      </div>
    </>
  )
}

function Phone({ device, participant, detail, run }: {
  device: SimulatedDevice
  participant?: Participant
  detail?: InstanceDetail
  run: (p: Promise<unknown>) => void
}) {
  const now = () => new Date().toISOString()
  const live = participant && detail && !detail.summary.cancelledAt
  const instanceId = detail?.summary.id ?? ''

  const ack = () => run(api.report(device.deviceId, {
    receptions: [{ alarmInstanceId: instanceId, receivedAt: participant?.reception?.receivedAt ?? now(), viaBearer: 'INTERNET', hopCount: 0, acknowledgedAt: now() }],
  }))
  const setTask = (taskId: string, state: TaskState) => run(api.report(device.deviceId, {
    tasks: [{ alarmInstanceId: instanceId, taskId, state, reportedAt: now() }],
  }))
  const goTo = (lat: number, lon: number) => run(api.report(device.deviceId, {
    positions: [{ alarmInstanceId: instanceId, lat, lon, accuracyM: 6, reportedAt: now() }],
  }))

  return (
    <div className="phone">
      <div className="ph-head">
        <div className="row">
          <strong>{firstName(device.displayName)}</strong>
          <span className="spacer" />
          <DomainChip domain={device.domain} />
        </div>
        <div className="small muted">{device.role} · seen {ago(device.lastSeenAt)}</div>
        <label className="switch small" style={{ marginTop: 8 }}>
          <input type="checkbox" checked={device.simulated} onChange={e => run(api.setSimulated(device.deviceId, e.target.checked))} />
          Autopilot drives this phone
        </label>
      </div>
      {live ? (
        <>
          <div className="ph-alarm">
            ALARM · {detail.summary.name}
            <small>EV#{detail.summary.displayNo} — {participant.role}</small>
          </div>
          <div className="ph-body">
            {!participant.reception?.acknowledgedAt && <button className="btn danger" onClick={ack}>I've read it — acknowledge</button>}
            {participant.groups.map(g => (
              <div key={g.id} className="stack" style={{ gap: 6 }}>
                <div className="row small">
                  <strong>📍 {g.location?.name}</strong>
                  <span className="spacer" />
                  {g.location && <button className="btn small" onClick={() => goTo(g.location!.lat, g.location!.lon)}>Arrive</button>}
                </div>
                {g.tasks.map(t => (
                  <div key={t.taskId} className="ph-task">
                    <div className="row" style={{ alignItems: 'flex-start' }}>
                      <span style={{ flex: 1 }}>{t.description}</span>
                      <span className={`state ${t.state}`}>{STATE_LABEL[t.state]}</span>
                    </div>
                    <div className="acts">
                      {(['EN_ROUTE', 'IN_PROGRESS', 'DONE', 'BLOCKED'] as TaskState[]).filter(s => s !== t.state).map(s => (
                        <button key={s} className="btn small" onClick={() => setTask(t.taskId, s)}>{STATE_LABEL[s]}</button>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="ph-idle">
          <div style={{ fontSize: 28 }}>🛡</div>
          Standing by<br />scenarios stored offline
        </div>
      )}
    </div>
  )
}
