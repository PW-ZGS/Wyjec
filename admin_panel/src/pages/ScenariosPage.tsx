import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { MapView } from '../components/MapView'
import { DomainChip, domainLogo, fmtDateTime } from '../components/ui'
import { useLiveData } from '../hooks'

export function ScenariosPage() {
  const { definitionId } = useParams()
  const definitions = useLiveData(api.definitions)
  const selected = definitionId ?? definitions.data?.[0]?.id
  const scenario = useLiveData(() => (selected ? api.scenario(selected) : Promise.resolve(undefined)), [selected])
  const s = scenario.data
  const locations = s ? [...new Map(s.taskGroups.flatMap(g => (g.location ? [g.location] : [])).map(l => [l.id, l])).values()] : []
  const current = s?.versions.find(v => v.id === s.definition.scenarioVersionId)

  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow grey">Scenarios</div>
          <h1 className="title normal">Predefined response plans</h1>
          <p className="subtle">Published versions are immutable bundles. Each phone stores only its own task groups, offline.</p>
        </div>
      </div>
      <div className="tabs">
        {definitions.data?.map(d => (
          <Link key={d.id} to={`/scenarios/${d.id}`} className={`tab ${d.id === selected ? 'active' : ''}`} style={{ textDecoration: 'none' }}>
            {d.name}
          </Link>
        ))}
      </div>
      {s && (
        <>
          <div className="grid cols-2" style={{ marginBottom: 20 }}>
            <div className="card">
              <div className="row" style={{ marginBottom: 14 }}>
                <img src={domainLogo(s.definition.domain)} alt="" style={{ height: 48 }} />
                <div>
                  <h2 style={{ margin: 0 }}>{s.definition.name}</h2>
                  <div className="small muted">{s.definition.organizationName}</div>
                </div>
              </div>
              <div className="row wrap" style={{ marginBottom: 12 }}>
                <DomainChip domain={s.definition.domain} />
                <span className="chip mono">{s.definition.code}</span>
                {current && <span className="chip ok">v{current.version} {current.status.toLowerCase()}</span>}
              </div>
              {current && (
                <div className="small muted stack" style={{ gap: 4 }}>
                  <span>Published {fmtDateTime(current.publishedAt)}</span>
                  <span>Bundle <span className="mono">{current.bundleObjectKey}</span></span>
                  <span>SHA-256 <span className="mono">{current.bundleSha256?.slice(0, 32)}…</span></span>
                </div>
              )}
              <h3 style={{ marginTop: 18, marginBottom: 8 }}>Alarm controllers</h3>
              {s.controllers.map(c => (
                <div key={c.personId} className="row small" style={{ marginBottom: 4 }}>
                  <strong>{c.displayName}</strong>
                  {c.canRaise && <span className="chip">raise</span>}
                  {c.canCancel && <span className="chip">cancel</span>}
                </div>
              ))}
            </div>
            <div className="map-frame" style={{ minHeight: 320 }}>
              <MapView destinations={locations} fitKey={s.definition.id + locations.length} />
            </div>
          </div>
          <div className="groups">
            {s.taskGroups.map(g => (
              <div key={g.id} className="group">
                <div className="small muted">Group {g.orderNo}</div>
                <h3>{g.name}</h3>
                <div className="small" style={{ marginTop: 4 }}>📍 {g.location?.name}<div className="muted">{g.location?.address}</div></div>
                <div className="row wrap" style={{ marginTop: 10 }}>
                  {g.assignees.map(a => (
                    <span key={a.personId} className="chip">{a.displayName}{a.rank > 1 ? ' (deputy)' : ''}</span>
                  ))}
                </div>
                <ol>
                  {g.tasks.map(t => (
                    <li key={t.id}>{t.description}{t.requiresConfirmation && <span className="confirm">CONFIRM</span>}</li>
                  ))}
                </ol>
              </div>
            ))}
          </div>
        </>
      )}
    </>
  )
}
