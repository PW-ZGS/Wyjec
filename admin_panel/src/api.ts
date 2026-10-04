// Typed client for the Siren admin API (/api/admin/**).

export type Domain = 'CIV' | 'MED' | 'MIL'
export type ParticipantStatus = 'COMPLETED' | 'ACKNOWLEDGED' | 'UNREAD'
export type TaskState = 'PENDING' | 'EN_ROUTE' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export interface Session { token: string; username: string; displayName: string; organizationName: string }
export interface Area { lat: number; lon: number; radiusM: number }
export interface Location { id: string; name: string; address?: string; lat: number; lon: number }

export interface AlarmDefinition {
  id: string; code: string; name: string; organizationName: string; domain: Domain
  version?: number; scenarioVersionId?: string; groups: number; tasks: number; people: number
  canRaise: boolean; canCancel: boolean; activeInstanceId?: string
}

export interface InstanceStats { participants: number; received: number; acknowledged: number; completed: number; tasksDone: number; tasksTotal: number }
export interface InstanceSummary {
  id: string; displayNo: number; definitionId: string; code: string; name: string; organizationName: string; domain: Domain
  scenarioVersionId: string; version: number; raisedAt: string; raisedBy?: string; cancelledAt?: string; cancelledBy?: string
  area?: Area; stats?: InstanceStats
}
export interface TaskStatus { taskId: string; orderNo: number; description: string; requiresConfirmation: boolean; state: TaskState; reportedAt?: string; note?: string }
export interface ParticipantGroup { id: string; name: string; rank: number; location?: Location; tasks: TaskStatus[] }
export interface Participant {
  personId: string; displayName: string; personRole?: string; shortId: string; role: string; deviceId?: string
  status: ParticipantStatus; blocked: boolean
  reception?: { receivedAt: string; acknowledgedAt?: string; viaBearer?: string; hopCount?: number }
  position?: { lat: number; lon: number; accuracyM?: number; reportedAt: string }
  groups: ParticipantGroup[]
}
export interface EventRow {
  id: string; alarmInstanceId: string; displayNo?: number; code: string; name: string; eventType: 'RAISE' | 'CANCEL'
  originDeviceId: string; originName?: string; originPlatform: string; keyEpochId: number
  createdAt: string; receivedAt: string; firstBearer?: string; verified: boolean; signatureB64: string
}
export interface InstanceDetail { summary: InstanceSummary; participants: Participant[]; events: EventRow[] }

export interface TaskGroup {
  id: string; name: string; orderNo: number; location?: Location
  assignees: { personId: string; displayName: string; role?: string; rank: number }[]
  tasks: { id: string; orderNo: number; description: string; requiresConfirmation: boolean }[]
}
export interface ScenarioView {
  definition: AlarmDefinition
  versions: { id: string; version: number; status: string; bundleObjectKey?: string; bundleSha256?: string; publishedAt?: string }[]
  taskGroups: TaskGroup[]
  controllers: { personId: string; displayName: string; canRaise: boolean; canCancel: boolean }[]
}
export interface DeviceView {
  id: string; platform: string; deviceClass: string; criticality: string; relayMode: string; status: string
  lastSeenAt?: string; lastSyncAt?: string; bearers: { bearer: string; priority: number; address?: string }[]; simulated: boolean
}
export interface PersonView { id: string; displayName: string; role?: string; phone?: string; organizationName: string; domain: Domain; devices: DeviceView[] }
export interface KeyEpochView { id: number; organizationName: string; status: string; validFrom: string; validTo: string; publicKeySha256: string }
export interface SimulatedDevice { deviceId: string; personId: string; displayName: string; role?: string; organizationName: string; domain: Domain; simulated: boolean; lastSeenAt?: string }
export interface SimulatorView { demo: boolean; autopilot: boolean; devices: SimulatedDevice[] }
export interface PublicInfo { demo: boolean; demoAccounts: { username: string; password: string; who: string }[] }

export interface StatusBatch {
  receptions?: { alarmInstanceId: string; receivedAt: string; viaBearer?: string; hopCount?: number; acknowledgedAt?: string }[]
  tasks?: { alarmInstanceId: string; taskId: string; state: TaskState; reportedAt: string; note?: string }[]
  positions?: { alarmInstanceId: string; lat: number; lon: number; accuracyM?: number; reportedAt: string }[]
}

const TOKEN_KEY = 'siren.session'

export function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export function saveSession(s: Session | null) {
  try {
    if (s) localStorage.setItem(TOKEN_KEY, JSON.stringify(s))
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* storage unavailable: session lasts for this tab only */
  }
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

export class ApiError extends Error {}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const session = loadSession()
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(session ? { Authorization: `Bearer ${session.token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 401 && path !== '/api/admin/login') {
    onUnauthorized()
    throw new ApiError('Session expired — please log in again')
  }
  const text = await res.text()
  const data = text ? JSON.parse(text) : undefined
  if (!res.ok) throw new ApiError(data?.error ?? `Request failed (${res.status})`)
  return data as T
}

export const api = {
  info: () => request<PublicInfo>('GET', '/api/public/info'),
  login: (username: string, password: string) => request<Session>('POST', '/api/admin/login', { username, password }),
  logout: () => request<void>('POST', '/api/admin/logout'),
  definitions: () => request<AlarmDefinition[]>('GET', '/api/admin/alarm-definitions'),
  scenario: (definitionId: string) => request<ScenarioView>('GET', `/api/admin/scenarios/${definitionId}`),
  raise: (definitionId: string, area?: Area) => request<InstanceDetail>('POST', '/api/admin/alarms', { definitionId, area }),
  cancel: (instanceId: string) => request<InstanceDetail>('POST', `/api/admin/alarms/${instanceId}/cancel`),
  active: () => request<InstanceSummary[]>('GET', '/api/admin/alarms/active'),
  history: () => request<InstanceSummary[]>('GET', '/api/admin/alarms/history'),
  detail: (instanceId: string) => request<InstanceDetail>('GET', `/api/admin/alarms/${instanceId}`),
  events: () => request<EventRow[]>('GET', '/api/admin/events'),
  people: () => request<PersonView[]>('GET', '/api/admin/people'),
  keys: () => request<KeyEpochView[]>('GET', '/api/admin/keys'),
  simulator: () => request<SimulatorView>('GET', '/api/admin/simulator'),
  setAutopilot: (enabled: boolean) => request<SimulatorView>('POST', '/api/admin/simulator/autopilot', { enabled }),
  setSimulated: (deviceId: string, enabled: boolean) => request<SimulatorView>('POST', `/api/admin/simulator/devices/${deviceId}/simulated`, { enabled }),
  report: (deviceId: string, batch: StatusBatch) => request<void>('POST', `/api/admin/simulator/devices/${deviceId}/report`, batch),
}
