import { api } from '../api'
import { DomainChip, ago } from '../components/ui'
import { useLiveData } from '../hooks'

export function PeoplePage() {
  const people = useLiveData(api.people)
  return (
    <>
      <div className="page-head">
        <div>
          <div className="eyebrow grey">People & devices</div>
          <h1 className="title normal">Who receives alarms</h1>
          <p className="subtle">Each device has its own credential, a device class (security profile) and the bearers it can be reached on.</p>
        </div>
      </div>
      {people.error && <div className="error">{people.error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr><th>Person</th><th>Organization</th><th>Device</th><th>Class · relay</th><th>Bearers</th><th>Last seen</th><th>Last sync</th></tr>
          </thead>
          <tbody>
            {people.data?.flatMap(p =>
              (p.devices.length ? p.devices : [undefined]).map((d, i) => (
                <tr key={p.id + (d?.id ?? '')}>
                  {i === 0 && (
                    <>
                      <td rowSpan={Math.max(p.devices.length, 1)}><strong>{p.displayName}</strong><div className="small muted">{p.role}</div><div className="small muted">{p.phone}</div></td>
                      <td rowSpan={Math.max(p.devices.length, 1)}><DomainChip domain={p.domain} /><div className="small muted">{p.organizationName}</div></td>
                    </>
                  )}
                  {d ? (
                    <>
                      <td>
                        {d.platform === 'DESKTOP' ? '🖥 Console' : '📱 Phone'}
                        {d.simulated && <span className="chip warn" style={{ marginLeft: 6 }}>simulated</span>}
                        <div className="mono muted">{d.id.slice(-12)}</div>
                      </td>
                      <td><span className="chip">{d.deviceClass}</span><span className={`chip ${d.criticality === 'CRITICAL' ? 'bad' : ''}`}>{d.criticality.toLowerCase().replace('_', '-')}</span><div className="small muted">{d.relayMode.toLowerCase().replace('_', ' ')}</div></td>
                      <td>{d.bearers.map(b => <span key={b.bearer} className="chip">{b.bearer}</span>)}</td>
                      <td className="small">{ago(d.lastSeenAt)}</td>
                      <td className="small">{ago(d.lastSyncAt)}</td>
                    </>
                  ) : (
                    <td colSpan={5} className="muted">No device</td>
                  )}
                </tr>
              )),
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}
