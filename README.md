# Siren (pol. Wyjec)

**Siren** is a flexible system for distributing tasks and coordinating people during emergencies, based on predefined scenarios. It is intended for organizations that need to coordinate a response across people, tasks, and locations, including civil administration, medical services, and the military.

When an alarm is raised, it activates an associated scenario. A scenario defines one or more task groups; each group contains tasks and is assigned to a person at a specific location.

## How It Works

1. An authorized person raises an alarm.
2. The alarm activates its associated scenario.
3. Task groups are distributed to the people and locations specified by the scenario.
4. Participants report alarm reception and task progress. When enabled and available, their positions can also be shown on a map.
5. An authorized person can cancel the alarm.

## Concepts

- **Alarm:** An event that starts or cancels an emergency response.
- **Scenario:** A predefined response plan associated with an alarm.
- **Task group:** A set of tasks assigned to a person at a location.
- **Assignment:** The person and location responsible for carrying out a task group.

```mermaid
flowchart TB
    A[Alarm]
    S[Scenario]
    TG[Task Group]
    T[Task]
    P[Person]
    M[Map]

    %% Main hierarchy
    A -->|causes execution of| S
    S -->|defines| TG

    %% Task Group branches
    TG -->|contains| T
    TG -->|is assigned to person at a specific location| P

    %% Monitoring
    P -->|reports position, alarm reception and task progress| M

    %% Alarm control
    P -->|if authorized: raise / cancel| A

    %% Layout constraints
    A ~~~ S
    A ~~~ T
    A ~~~ P
    A ~~~ M
    S ~~~ T
```

Alarms can be raised and cancelled by authorized people.

Scenarios are distributed to devices and stored offline. Alarm delivery is designed not to require the Internet or a centralized server, and can use different communication technologies. Actual delivery still depends on an available communication path; offline devices can use their stored scenarios but cannot exchange updates until a path is available.

During an active alarm, the system can collect participants' alarm reception status, task progress, and, when available, position to provide an overview on a map. Location collection should be limited to what is needed for the response, with access and retention governed by the deploying organization.


## Triple Use

### CIV — Civil Administration

**Air raid alarm**

A school principal is ordered to go to the school and prepare it as a shelter:

- open the building,
- open the shelter,
- display required signs.

### MED — Medical

**Mass-casualty alarm in the Emergency Department**

An emergency response scenario assigns medical staff to specific locations:

- anesthesiologist → Emergency Department,
- surgeon → operating theatre,
- nurses → triage area,
- radiology technician → imaging department.

### MIL — Military

**Mobilization alarm**

A soldier is ordered to:

- go to the equipment store where he collects equipment and takes ammunition
- go to designated bunker where he takes up a guard position

---

# Prototype

A working prototype of the whole system runs on one laptop: database, backend, admin panel, and an Android app for phones on the same Wi‑Fi.

```mermaid
flowchart LR
    subgraph Laptop["Laptop — docker compose"]
        W[web<br/>nginx + admin panel<br/>:8080] -->|/api| B[backend<br/>Kotlin · Spring Boot]
        B --> D[(PostgreSQL + PostGIS)]
    end
    P1[Android phone] -->|HMAC-signed HTTP| W
    P2[Android phone] -->|HMAC-signed HTTP| W
    P1 <-.->|signed alarms over<br/>Wi-Fi broadcast mesh| P2
    O[Operator browser] --> W
```

| Folder | What it is |
|---|---|
| `database/` | Flyway migrations implementing `siren_db_schema.png` (`migrations/`) plus demo data (`demo/`) |
| `backend/` | Kotlin + Spring Boot API: device sync, alarm ingest + verification, status, admin API, live updates (SSE), demo autopilot |
| `admin_panel/` | React + Vite + Leaflet operations console, design from `web-version.png` |
| `mobile/` | Android app (Kotlin, Jetpack Compose): offline scenarios, alarm to-do list, mesh relay, controller raise/cancel |
| `DEMO.md` | Step-by-step script for presenting to customers |

## Quick start

Requirements: Docker with Compose. For the phone app: JDK 21 and the Android SDK (or install the prebuilt APK).

```bash
./siren.sh up        # build + start everything (first build takes a few minutes)
```

* Admin panel: <http://localhost:8080>, log in with **admin / siren** (also `hospital` and `army`).
* Phones: `./siren.sh url` prints the LAN address, for example `http://192.168.1.20:8080`.

Other commands: `./siren.sh down`, `./siren.sh reset` (wipes the DB: fresh demo data and keys), `./siren.sh logs`, `./siren.sh smoke`.

## Android app

```bash
./siren.sh apk                       # → dist/siren.apk
adb install -r dist/siren.apk        # or copy the file to the phone and open it
```

On first start, enter the laptop address shown by `./siren.sh url` and tap a demo person. **Tomasz Lewandowski** is the best choice: he receives the drone-strike orders and can also raise or cancel that alarm from the phone. Allow notifications and location.

* Laptop firewall: allow inbound TCP 8080 (for example `sudo ufw allow 8080/tcp`).
* Mesh relay: phones on the same Wi‑Fi pass signed alarms to each other over UDP broadcast port 47474. This works with the server stopped. Guest or corporate networks with client isolation block it; a phone hotspot works.
* Emulator: the server address is `http://10.0.2.2:8080`. Emulators cannot join the mesh.

## Demo data

Kraków city crisis management (CIV), with a drone-strike scenario matching the web design:

| Person | Task group | Phone |
|---|---|---|
| Ania Kowalska | Securing shelter S‑07 | simulated |
| Jacek Zieliński | Securing shelter S‑12 | simulated |
| Maja Wiśniewska | Manage evacuation | simulated |
| Tomasz Lewandowski | Notify authorities · can raise and cancel | **real phone** |

Also included: an air raid (school principal opens the school shelter), a hospital mass-casualty incident (MED), and a mobilization (MIL). The **Simulator** page plays every responder without a real phone (the autopilot), so a full response can be shown on one laptop. As soon as a real phone enrolls as a person, the autopilot stops playing that person.

## How the security model is implemented

| SECURITY.md | Prototype |
|---|---|
| Server–App channel, individual symmetric credentials | Every device request carries `HMAC-SHA256(psk, METHOD\nPATH?QUERY\nTIMESTAMP\nsha256(body))`; ±5 min clock skew |
| Alarm cryptography, asymmetric | ECDSA P‑256 per organization and key epoch. Every receiving device gets the public key; only alarm controllers get the private key |
| Authenticity independent of server and medium | Phones verify every message themselves, whether it came from the server poll or the mesh. The server verifies it again, together with the controller's rights |
| Replay protection | Message id unique; CANCEL is terminal; events past `key_epoch.valid_to + 24 h` or in the future are rejected; forged copies are never stored |
| Key updates | CURRENT plus a pre-distributed NEXT epoch, then GRACE and RETIRED, rotated hourly by the backend |
| Offline operation | Scenario bundles (own task groups only, SHA‑256 checked) and keys are stored in on-device SQLite; status waits in an outbox until a path to the server exists |

### Prototype shortcuts (do not deploy as is)

* The HSM is a DB table (`prototype_hsm_secret`), and the object store is `prototype_object_store`.
* Plain HTTP on the LAN. The protocol is authenticated, but production adds TLS.
* Phone storage is not encrypted, and keys live in app storage instead of the TEE keystore.
* Operator sessions are kept in memory and passwords are bcrypt hashes in the DB. There is no SSO.
* The mesh uses Wi‑Fi broadcast; it stands in for BLE, Wi‑Fi Direct, LoRa and pagers.
* Map tiles come from openstreetmap.org, so the laptop needs Internet for the map background.

## Development

```bash
docker compose up -d db                                         # Postgres on localhost:5433
cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 DB_PORT=5433 ./gradlew bootRun   # API on :8081
cd admin_panel && npm install && npm run dev                    # UI on :5173, proxies /api to :8081
```

Device API (`/api/device/**`, HMAC): `GET sync`, `GET alarms?since=`, `POST alarms` (signed raise/cancel or a relayed copy), `POST status`.
Admin API (`/api/admin/**`, bearer token): alarm definitions, scenarios, raise/cancel, live detail, event log, people, keys, simulator, `stream` (SSE).
