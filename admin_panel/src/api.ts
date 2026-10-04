// Typed client for the Siren admin API (/api/admin/**).

export type Domain = 'CIV' | 'MED' | 'MIL'
export type ParticipantStatus = 'COMPLETED' | 'ACKNOWLEDGED' | 'UNREAD'
export type TaskState = 'PENDING' | 'EN_ROUTE' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export interface Session {
    token: string;
    username: string;
    displayName: string;
    organizationName: string
}

export interface Location {
    id: string;
    name: string;
    address?: string;
    lat: number;
    lon: number
}

export interface AlarmDefinition {
    id: string;
    code: string;
    name: string;
    organizationName: string;
    domain: Domain
    version?: number;
    scenarioVersionId?: string;
    groups: number;
    tasks: number;
    people: number
    canRaise: boolean;
    canCancel: boolean;
    activeInstanceId?: string
}

export interface InstanceStats {
    participants: number;
    received: number;
    acknowledged: number;
    completed: number;
    tasksDone: number;
    tasksTotal: number
}

export interface InstanceSummary {
    id: string;
    displayNo: number;
    definitionId: string;
    code: string;
    name: string;
    organizationName: string;
    domain: Domain
    scenarioVersionId: string;
    version: number;
    raisedAt: string;
    raisedBy?: string;
    cancelledAt?: string;
    cancelledBy?: string
    description?: string;
    stats?: InstanceStats
}

export interface TaskStatus {
    taskId: string;
    orderNo: number;
    description: string;
    requiresConfirmation: boolean;
    state: TaskState;
    reportedAt?: string;
    note?: string
}

export interface ParticipantGroup {
    id: string;
    name: string;
    rank: number;
    location?: Location;
    tasks: TaskStatus[]
}

export interface Participant {
    personId: string;
    displayName: string;
    personRole?: string;
    shortId: string;
    role: string;
    deviceId?: string
    status: ParticipantStatus;
    blocked: boolean
    reception?: {
        receivedAt: string;
        acknowledgedAt?: string;
        viaBearer?: string;
        hopCount?: number;
        helpRequestedAt?: string
    }
    position?: { lat: number; lon: number; accuracyM?: number; reportedAt: string }
    groups: ParticipantGroup[]
}

export interface InstanceDetail {
    summary: InstanceSummary;
    participants: Participant[]
}

export interface TaskGroup {
    id: string;
    name: string;
    orderNo: number;
    location?: Location
    assignees: { personId: string; displayName: string; role?: string; rank: number }[]
    tasks: { id: string; orderNo: number; description: string; requiresConfirmation: boolean }[]
}

export interface ScenarioView {
    definition: AlarmDefinition
    versions: {
        id: string;
        version: number;
        status: string;
        bundleObjectKey?: string;
        bundleSha256?: string;
        publishedAt?: string
    }[]
    taskGroups: TaskGroup[]
    controllers: { personId: string; displayName: string; canRaise: boolean; canCancel: boolean }[]
}

export interface PublicInfo {
    demo: boolean;
    demoAccounts: { username: string; password: string; who: string }[]
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

let onUnauthorized: () => void = () => {
}

export function setUnauthorizedHandler(fn: () => void) {
    onUnauthorized = fn
}

export class ApiError extends Error {
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const session = loadSession()
    const res = await fetch(path, {
        method,
        headers: {
            ...(body !== undefined ? {'Content-Type': 'application/json'} : {}),
            ...(session ? {Authorization: `Bearer ${session.token}`} : {}),
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
    login: (username: string, password: string) => request<Session>('POST', '/api/admin/login', {username, password}),
    logout: () => request<void>('POST', '/api/admin/logout'),
    definitions: () => request<AlarmDefinition[]>('GET', '/api/admin/alarm-definitions'),
    scenario: (definitionId: string) => request<ScenarioView>('GET', `/api/admin/scenarios/${definitionId}`),
    raise: (definitionId: string, description: string) => request<InstanceDetail>('POST', '/api/admin/alarms', {
        definitionId,
        description
    }),
    cancel: (instanceId: string) => request<InstanceDetail>('POST', `/api/admin/alarms/${instanceId}/cancel`),
    active: () => request<InstanceSummary[]>('GET', '/api/admin/alarms/active'),
    detail: (instanceId: string) => request<InstanceDetail>('GET', `/api/admin/alarms/${instanceId}`),
}
