import { useEffect, useState } from 'react'
import { api, type AlarmDefinition, type Location } from '../api'
import { MapView } from './MapView'

interface Props {
  definitions: AlarmDefinition[]
  initialId?: string
  onClose: () => void
  onRaised: (instanceId: string) => void
}

/** Pick an alarm, mark the affected area on the map, raise. The server signs as the operator's console. */
export function RaiseDialog({ definitions, initialId, onClose, onRaised }: Props) {
  const raisable = definitions.filter(d => d.canRaise && !d.activeInstanceId)
  const [id, setId] = useState(initialId && raisable.some(d => d.id === initialId) ? initialId : raisable[0]?.id)
  const [destinations, setDestinations] = useState<Location[]>([])
  const [center, setCenter] = useState<{ lat: number; lon: number }>()
  const [radius, setRadius] = useState(900)
  const [withArea, setWithArea] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!id) return
    api.scenario(id).then(s => {
      const locs = s.taskGroups.flatMap(g => (g.location ? [g.location] : []))
      const unique = [...new Map(locs.map(l => [l.id, l])).values()]
      setDestinations(unique)
      if (unique.length) {
        setCenter({
          lat: unique.reduce((a, l) => a + l.lat, 0) / unique.length,
          lon: unique.reduce((a, l) => a + l.lon, 0) / unique.length,
        })
      }
      setRadius(s.definition.domain === 'MED' ? 250 : s.definition.domain === 'MIL' ? 600 : 900)
    })
  }, [id])

  const raise = async () => {
    if (!id) return
    setBusy(true)
    setError(undefined)
    try {
      const detail = await api.raise(id, withArea && center ? { lat: center.lat, lon: center.lon, radiusM: radius } : undefined)
      onRaised(detail.summary.id)
    } catch (e) {
      setError((e as Error).message)
      setBusy(false)
    }
  }

  return (
    <div className="backdrop" onClick={onClose}>
      <div className="dialog" onClick={e => e.stopPropagation()}>
        <div className="eyebrow">Raise alarm</div>
        <h2>Which emergency?</h2>
        <p className="subtle">The alarm is signed with your organization's alarm key and starts the published scenario on every assigned phone.</p>
        {raisable.length === 0 ? (
          <div className="notice">You have no alarms you can raise right now (or they are already active).</div>
        ) : (
          <>
            <label className="field">
              Alarm
              <select value={id} onChange={e => setId(e.target.value)}>
                {raisable.map(d => (
                  <option key={d.id} value={d.id}>{d.name} — {d.organizationName}</option>
                ))}
              </select>
            </label>
            <div className="mini-map">
              <MapView
                area={withArea && center ? { lat: center.lat, lon: center.lon, radiusM: radius } : undefined}
                destinations={destinations}
                fitKey={id + destinations.length}
                onPick={(lat, lon) => { setCenter({ lat, lon }); setWithArea(true) }}
              />
            </div>
            <div className="row wrap">
              <label className="switch">
                <input type="checkbox" checked={withArea} onChange={e => setWithArea(e.target.checked)} />
                Mark affected area
              </label>
              <span className="spacer" />
              <span className="small muted">Click the map to move it · radius {radius} m</span>
              <input type="range" min={100} max={3000} step={50} value={radius} disabled={!withArea}
                onChange={e => setRadius(Number(e.target.value))} style={{ width: 200 }} />
            </div>
          </>
        )}
        {error && <div className="error" style={{ marginTop: 12 }}>{error}</div>}
        <div className="row" style={{ marginTop: 20 }}>
          <span className="spacer" />
          <button className="btn" onClick={onClose}>Back</button>
          <button className="btn danger big" disabled={!id || busy} onClick={raise}>
            {busy ? 'Raising…' : 'Raise alarm now'}
          </button>
        </div>
      </div>
    </div>
  )
}
