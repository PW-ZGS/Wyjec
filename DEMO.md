# Demo script (≈10 minutes)

**Before the meeting**
1. `./siren.sh reset` gives a clean database and fresh keys (required after this update: new migration and seed).
2. Three phones on the same Wi‑Fi as the laptop. Open Siren and enroll:
   - phone 1: **Ewa Dąbrowska** (School Director)
   - phone 2: **Andrzej Malinowski** (City Mayor)
   - phone 3: **Dr Robert Krawczyk** (Emergency Department Manager)

   Turn the volume up. Allow location.
3. Browser full-screen on <http://localhost:8080>, logged in as **admin / siren** (Crisis Management Operator).

---

### 1. Normal mode (1 min)
*Phones.* Only own procedures, stored offline. No map, no location. The Mayor's phone has **RAISE ALARM** at the bottom.
*Browser, LIVE.* No map: alarm type, description, raise.

### 2. Raise AIR RAID (1 min)
*LIVE → Air raid → description (e.g. "Possible aerial threat over northern district.") → Raise alarm.*
All three phones ring and show **AIR RAID** with the same description.

### 3. One alarm, three responsibilities (3 min)
Put the phones side by side after **ACKNOWLEDGE**:
- School Director → school protected area, pupils and staff.
- City Mayor → Municipal Crisis Management Centre, municipal coordination.
- ED Manager → triage area, mass-casualty readiness.

Tick tasks on the phones. **GO** opens navigation.
*Browser.* The map appears only now: three phones, their destinations, task progress. Tap **NEED HELP** on one phone: it shows as NEED HELP on LIVE.

### 4. Triple use (1 min)
*Scenarios page.* Click through **Air raid**, **Mass-casualty incident** and **Mobilization**. Drone strike and the other alarm types stay selectable.

### 5. Works without the server (2 min)
1. `docker compose stop backend` takes the server down.
2. On the Mayor's phone: **END ALARM**. The phone signs the cancel itself; the other phones get it over the Wi‑Fi mesh.
3. `docker compose start backend`. The phones bridge the cancel to the server.

Or end it from the browser: **End alarm** on LIVE.

### 6. All clear
Every phone returns to normal mode, location sharing stops, positions collected for that alarm are deleted, and LIVE is back to the raise form.

---

**If something goes wrong**
* Phone stuck on *Waiting for the first sync…*: check the laptop IP (`./siren.sh url`), the firewall (port 8080), and that both are on the same Wi‑Fi.
* Map is grey: the laptop has no Internet, so OSM tiles are missing. Everything else works.
* Phone raise/end fails with a timestamp error: the phone clock is off by more than 5 minutes.
* No marker for a phone: location permission denied or no fix yet indoors.
