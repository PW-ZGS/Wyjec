import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect } from 'react'
import { Circle, MapContainer, Marker, TileLayer, Tooltip, useMap, useMapEvents } from 'react-leaflet'
import type { Area, Location, Participant } from '../api'

export const PERSON_SVG =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M5 21v-1.5A5.5 5.5 0 0 1 10.5 14h3a5.5 5.5 0 0 1 5.5 5.5V21"/><path d="M3 21h18"/></svg>'

const KRAKOW: [number, number] = [50.0545, 19.9445]

function personIcon(p: Participant) {
  const first = p.displayName.replace(/^(Dr|Capt\.|Sgt\.|Pvt\.) /, '').split(' ')[0]
  return L.divIcon({
    className: '',
    iconSize: [0, 0],
    html: `<div class="pm ${p.status}"><div class="halo">${PERSON_SVG}</div><div class="label">${p.shortId}-${first}</div></div>`,
  })
}

const destIcon = L.divIcon({ className: '', iconSize: [14, 14], iconAnchor: [7, 7], html: '<div class="dest"></div>' })

function FitTo({ points, fitKey }: { points: [number, number][]; fitKey: string }) {
  const map = useMap()
  useEffect(() => {
    if (points.length === 0) return
    if (points.length === 1) map.setView(points[0], 15)
    else map.fitBounds(L.latLngBounds(points), { padding: [70, 70], maxZoom: 16 })
    // Fit only when the event changes, not on every position update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, map])
  return null
}

function ClickPicker({ onPick }: { onPick: (lat: number, lon: number) => void }) {
  useMapEvents({ click: e => onPick(e.latlng.lat, e.latlng.lng) })
  return null
}

interface Props {
  area?: Area
  participants?: Participant[]
  destinations?: Location[]
  fitKey?: string
  onPick?: (lat: number, lon: number) => void
  onSelect?: (personId: string) => void
  center?: [number, number]
}

/** OpenStreetMap view with the affected area (purple), responders and their destinations. */
export function MapView({ area, participants = [], destinations = [], fitKey = '', onPick, onSelect, center }: Props) {
  const positioned = participants.filter(p => p.position)
  const points: [number, number][] = [
    ...positioned.map(p => [p.position!.lat, p.position!.lon] as [number, number]),
    ...destinations.map(d => [d.lat, d.lon] as [number, number]),
    ...(area ? [[area.lat, area.lon] as [number, number]] : []),
  ]
  return (
    <MapContainer center={center ?? KRAKOW} zoom={14} scrollWheelZoom zoomControl={false}>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      {area && (
        <Circle
          center={[area.lat, area.lon]}
          radius={area.radiusM}
          pathOptions={{ color: '#d946ef', weight: 1, fillColor: '#e879f9', fillOpacity: 0.35 }}
        />
      )}
      {destinations.map(d => (
        <Marker key={d.id} position={[d.lat, d.lon]} icon={destIcon}>
          <Tooltip direction="top" offset={[0, -8]}>{d.name}</Tooltip>
        </Marker>
      ))}
      {positioned.map(p => (
        <Marker
          key={p.personId}
          position={[p.position!.lat, p.position!.lon]}
          icon={personIcon(p)}
          eventHandlers={{ click: () => onSelect?.(p.personId) }}
        />
      ))}
      {fitKey && <FitTo points={points} fitKey={fitKey} />}
      {onPick && <ClickPicker onPick={onPick} />}
    </MapContainer>
  )
}
