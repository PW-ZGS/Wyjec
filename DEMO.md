# Demo script (≈10 minutes)

**Before the meeting**
1. `./siren.sh reset` gives a clean database and fresh keys.
2. Phone on the same Wi‑Fi as the laptop: open Siren and enroll as **Tomasz Lewandowski**. Turn the volume up.
3. Browser full-screen on <http://localhost:8080>, logged in as **admin / siren**.
4. Optional: a second phone enrolled as **Ania** or **Maja**, for the mesh demo.

---

### 1. Always ready (1 min)
*Phone, home screen.* "Normally Siren is a pocket library: every procedure on the phone, **stored offline**." Open *Procedures → Drone strike* to show Tomasz's own tasks only. "A lost phone reveals almost nothing."

### 2. Raise the alarm (1 min)
*Live page → Drone strike card → mark the area on the map → Raise alarm now.*
"The alarm is **digitally signed** with the city's key and goes out to everyone in the scenario."
The phone screams and opens a full-screen red to-do list.

### 3. Where to go, what to do (2 min)
*Phone.* Tap **I've got it**, then **On my way** on the first task. The **Go** button opens navigation.
*Browser.* The map fills up: green, yellow and red people, the purple affected area, and the roles in *Map references*. Click a person to see each task's status. KPIs at the bottom show delivered, read and tasks done.
"The duty officer sees in real time who received the alarm, where people are and how far each task has got."

### 4. Triple use (1 min)
*Scenarios page.* Click through **Air raid** (school principal), **Mass-casualty incident** (hospital ED) and **Mobilization** (army). "Same engine, different scenario." Optionally log in as `hospital` and raise the MCI.

### 5. Works without the server (2 min)
1. `docker compose stop backend` takes the server down.
2. On Tomasz's phone, open **Raise Drone strike** (he is an authorized controller). The phone signs the alarm itself.
3. The second phone rings anyway, delivered over the Wi‑Fi mesh, with the signature checked on the phone.
4. `docker compose start backend`. The phones bridge the alarm to the server, and it appears in the **Event log** with first bearer `WIFI_DIRECT`.

"The authenticity of an alarm does not depend on the server or on the medium."

### 6. Trust (1 min)
*Event log.* Each raise and cancel shows its signature, key epoch and *verified* badge. The key epochs table below shows rotation: CURRENT, then NEXT pre-distributed to phones.

### 7. All clear
On the phone (as controller) or with **Cancel alarm** in the browser. Every phone gets a signed cancel.

---

**If something goes wrong**
* Phone says *offline*: check the laptop IP (`./siren.sh url`), the firewall (port 8080), and that both are on the same Wi‑Fi.
* Map is grey: the laptop has no Internet, so OSM tiles are missing. Everything else works.
* Phone shows *Server refused: request timestamp…*: the phone clock is off by more than 5 minutes.
* No phone at hand: on the **Simulator** page, tick *Autopilot drives this phone* for Tomasz, then everything runs on the laptop.
