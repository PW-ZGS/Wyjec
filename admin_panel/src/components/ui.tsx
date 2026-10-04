import type { Domain, Participant, ParticipantStatus, TaskState } from '../api'
import { PERSON_SVG } from './MapView'

export const STATUS_LABEL: Record<ParticipantStatus, string> = {
  COMPLETED: 'Tasks completed',
  ACKNOWLEDGED: 'Message received',
  UNREAD: 'Message unread',
}

export const STATE_LABEL: Record<TaskState, string> = {
  PENDING: 'Pending',
  EN_ROUTE: 'En route',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  BLOCKED: 'Blocked',
}

export function Avatar({ status }: { status: ParticipantStatus }) {
  return <span className={`avatar s-${status}`} dangerouslySetInnerHTML={{ __html: PERSON_SVG }} />
}

export function Legend() {
  return (
    <div className="legend">
      <h4>Legend</h4>
      <div><span className="sw" style={{ background: '#34c768' }} />green — tasks completed</div>
      <div><span className="sw" style={{ background: '#f0962a' }} />yellow — message received</div>
      <div><span className="sw" style={{ background: '#ef4444' }} />red — message unread</div>
      <div><span className="sw" style={{ background: '#e879f9' }} />purple — affected area</div>
      <div><span className="sw" style={{ background: '#1e2b27', borderRadius: 3 }} />square — assigned location</div>
    </div>
  )
}

export function DomainChip({ domain }: { domain: Domain }) {
  const label = { CIV: 'Civil', MED: 'Medical', MIL: 'Military' }[domain]
  return <span className={`chip ${domain}`}>{domain} · {label}</span>
}

export function domainLogo(domain: Domain) {
  return { CIV: '/logo.png', MED: '/logo-med.png', MIL: '/logo-mil.png' }[domain]
}

export function firstName(name: string) {
  return name.replace(/^(Dr|Capt\.|Sgt\.|Pvt\.) /, '').split(' ')[0]
}

export function taskProgress(p: Participant) {
  const tasks = p.groups.flatMap(g => g.tasks)
  return { done: tasks.filter(t => t.state === 'DONE').length, total: tasks.length }
}

export function fmtTime(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString([], { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export function ago(iso?: string) {
  if (!iso) return 'never'
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (s < 60) return `${Math.max(s, 0)}s ago`
  if (s < 3600) return `${Math.round(s / 60)} min ago`
  if (s < 86400) return `${Math.round(s / 3600)} h ago`
  return `${Math.round(s / 86400)} d ago`
}

export function elapsed(fromIso: string, now: Date) {
  const s = Math.max(0, Math.round((now.getTime() - new Date(fromIso).getTime()) / 1000))
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}
