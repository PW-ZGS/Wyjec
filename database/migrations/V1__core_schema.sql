-- Siren (Wyjec) — operational DB, alarm event log and status store.
-- Mirrors siren_db_schema.png. Edge local store lives on devices (see mobile/).

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE org_domain       AS ENUM ('CIV', 'MED', 'MIL');
CREATE TYPE criticality      AS ENUM ('CRITICAL', 'NON_CRITICAL');
CREATE TYPE relay_mode       AS ENUM ('RELAY', 'RECEIVE_ONLY');
CREATE TYPE device_platform  AS ENUM ('ANDROID', 'IOS', 'DESKTOP', 'LORA_NODE', 'VEHICLE', 'FIXED', 'PAGER', 'BRIDGE');
CREATE TYPE device_status    AS ENUM ('ACTIVE', 'SUSPENDED', 'REVOKED');
CREATE TYPE bearer           AS ENUM ('BLE', 'WIFI_DIRECT', 'LORA_P2P', 'PMR', 'INTERNET', 'CELLULAR', 'SATELLITE', 'LORAWAN', 'SMS_CB', 'PAGING');
CREATE TYPE key_epoch_status AS ENUM ('NEXT', 'CURRENT', 'GRACE', 'RETIRED', 'COMPROMISED');
CREATE TYPE scenario_status  AS ENUM ('DRAFT', 'PUBLISHED', 'RETIRED');
CREATE TYPE alarm_event_type AS ENUM ('RAISE', 'CANCEL');
CREATE TYPE task_state       AS ENUM ('PENDING', 'EN_ROUTE', 'IN_PROGRESS', 'DONE', 'BLOCKED');

-- ───────────── Identity & devices ─────────────

-- Deploying body (municipality, hospital, military unit). parent_id builds a hierarchy.
CREATE TABLE organization (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name      text       NOT NULL,
    domain    org_domain NOT NULL,
    parent_id uuid REFERENCES organization (id)
);

-- Anyone who can receive tasks or control alarms.
CREATE TABLE person (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid    NOT NULL REFERENCES organization (id),
    display_name    text    NOT NULL,
    role            text,
    phone           text,
    is_active       boolean NOT NULL DEFAULT true
);

-- Security profile per device type. Holds relay rules, allowed poll bearers, position reporting.
CREATE TABLE device_class (
    id                  smallint PRIMARY KEY,
    code                text        NOT NULL UNIQUE,
    criticality         criticality NOT NULL,
    default_relay_mode  relay_mode  NOT NULL,
    may_poll_bearers    text[]      NOT NULL DEFAULT '{}',
    may_report_position boolean     NOT NULL DEFAULT false
);

-- Physical endpoint. person_id is null for citizen relays / infrastructure.
CREATE TABLE device (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id           uuid REFERENCES person (id),
    device_class_id     smallint        NOT NULL REFERENCES device_class (id),
    platform            device_platform NOT NULL,
    relay_mode_override relay_mode,
    pager_capcode       text,
    status              device_status   NOT NULL DEFAULT 'ACTIVE',
    last_seen_at        timestamptz
);
CREATE INDEX device_person_idx ON device (person_id);

-- Media each device supports, in priority order.
CREATE TABLE device_bearer (
    device_id uuid     NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    bearer    bearer   NOT NULL,
    priority  smallint NOT NULL,
    address   text,
    PRIMARY KEY (device_id, bearer)
);

-- Server–App symmetric PSK, stored only as an HSM reference.
CREATE TABLE device_credential (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id    uuid        NOT NULL REFERENCES device (id),
    psk_hsm_ref  text        NOT NULL,
    valid_from   timestamptz NOT NULL DEFAULT now(),
    valid_to     timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX device_credential_device_idx ON device_credential (device_id);

-- ───────────── Scenarios & tasks ─────────────

CREATE TABLE alarm_definition (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             uuid NOT NULL REFERENCES organization (id),
    code                        text NOT NULL UNIQUE,
    name                        text NOT NULL,
    current_scenario_version_id uuid
);

-- Immutable, versioned response plan published as a signed bundle.
CREATE TABLE scenario_version (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alarm_definition_id uuid            NOT NULL REFERENCES alarm_definition (id),
    version             int             NOT NULL,
    status              scenario_status NOT NULL DEFAULT 'DRAFT',
    bundle_object_key   text,
    bundle_sha256       bytea,
    published_at        timestamptz,
    UNIQUE (alarm_definition_id, version)
);

ALTER TABLE alarm_definition
    ADD CONSTRAINT alarm_definition_current_version_fk
        FOREIGN KEY (current_scenario_version_id) REFERENCES scenario_version (id);

-- Named place with geometry (school, ED triage, bunker, equipment store).
CREATE TABLE location (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid                   NOT NULL REFERENCES organization (id),
    name            text                   NOT NULL,
    geom            geography(Point, 4326) NOT NULL,
    address         text
);

-- Scenario-scoped change of relay behaviour for a device class.
CREATE TABLE relay_policy_override (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_version_id uuid       NOT NULL REFERENCES scenario_version (id),
    device_class_id     smallint   NOT NULL REFERENCES device_class (id),
    relay_mode          relay_mode NOT NULL,
    UNIQUE (scenario_version_id, device_class_id)
);

-- Block of tasks performed at one location.
CREATE TABLE task_group (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_version_id uuid NOT NULL REFERENCES scenario_version (id),
    name                text NOT NULL,
    location_id         uuid REFERENCES location (id),
    order_no            int  NOT NULL
);

-- Links a task group to responsible people. rank = 1 primary, 2+ deputies.
CREATE TABLE assignment (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_group_id uuid     NOT NULL REFERENCES task_group (id),
    person_id     uuid     NOT NULL REFERENCES person (id),
    rank          smallint NOT NULL DEFAULT 1,
    UNIQUE (task_group_id, person_id)
);

-- Single ordered step within a group.
CREATE TABLE task (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_group_id         uuid    NOT NULL REFERENCES task_group (id),
    order_no              int     NOT NULL,
    description           text    NOT NULL,
    requires_confirmation boolean NOT NULL DEFAULT false
);

-- ───────────── Keys & authorization ─────────────

-- Asymmetric alarm key pair per period. Public key stored, private key in HSM.
CREATE TABLE key_epoch (
    id                  int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id     uuid             NOT NULL REFERENCES organization (id),
    public_key          bytea            NOT NULL,
    private_key_hsm_ref text             NOT NULL,
    valid_from          timestamptz      NOT NULL,
    valid_to            timestamptz      NOT NULL,
    status              key_epoch_status NOT NULL
);

-- Who may raise and/or cancel which alarm.
CREATE TABLE alarm_controller (
    person_id           uuid    NOT NULL REFERENCES person (id),
    alarm_definition_id uuid    NOT NULL REFERENCES alarm_definition (id),
    can_raise           boolean NOT NULL DEFAULT false,
    can_cancel          boolean NOT NULL DEFAULT false,
    granted_by          uuid REFERENCES person (id),
    PRIMARY KEY (person_id, alarm_definition_id)
);

-- Last downloaded scenario versions and key epochs per device.
CREATE TABLE device_sync_state (
    device_id            uuid PRIMARY KEY REFERENCES device (id),
    scenario_version_ids uuid[] NOT NULL DEFAULT '{}',
    key_epoch_ids        int[]  NOT NULL DEFAULT '{}',
    last_sync_at         timestamptz
);

-- ───────────── Alarm events (append-only event log) ─────────────

-- Every raise / cancel seen by the server. Legal / audit record, write-once.
CREATE TABLE alarm_event (
    id                  uuid PRIMARY KEY,               -- message ID from signed payload
    alarm_instance_id   uuid             NOT NULL,      -- groups raise + cancel
    alarm_definition_id uuid             NOT NULL REFERENCES alarm_definition (id),
    scenario_version_id uuid             NOT NULL REFERENCES scenario_version (id),
    event_type          alarm_event_type NOT NULL,
    origin_device_id    uuid             NOT NULL REFERENCES device (id),
    key_epoch_id        int              NOT NULL REFERENCES key_epoch (id),
    created_at          timestamptz      NOT NULL,      -- signed timestamp
    received_at         timestamptz      NOT NULL DEFAULT now(),
    first_bearer        bearer,
    signed_payload      bytea            NOT NULL,
    signature           bytea            NOT NULL,
    verified            boolean          NOT NULL
);
CREATE INDEX alarm_event_instance_idx ON alarm_event (alarm_instance_id, created_at);
CREATE INDEX alarm_event_created_idx ON alarm_event (created_at);

CREATE FUNCTION alarm_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'alarm_event is append-only';
END $$;

CREATE TRIGGER alarm_event_no_update BEFORE UPDATE OR DELETE ON alarm_event
    FOR EACH ROW EXECUTE FUNCTION alarm_event_append_only();

-- ───────────── Status (time-series, retention policy) ─────────────

-- Which device received which alarm, when, via which bearer, after how many hops.
CREATE TABLE alarm_reception (
    alarm_instance_id uuid        NOT NULL,
    device_id         uuid        NOT NULL REFERENCES device (id),
    received_at       timestamptz NOT NULL,
    via_bearer        bearer,
    hop_count         smallint,
    acknowledged_at   timestamptz,
    PRIMARY KEY (alarm_instance_id, device_id)
);

-- Device positions during an active alarm only; expires_at enforces data minimization.
CREATE TABLE position_report (
    device_id         uuid                   NOT NULL REFERENCES device (id),
    reported_at       timestamptz            NOT NULL,
    alarm_instance_id uuid                   NOT NULL,
    geom              geography(Point, 4326) NOT NULL,
    accuracy_m        real,
    expires_at        timestamptz            NOT NULL,
    PRIMARY KEY (device_id, reported_at)
);
CREATE INDEX position_report_instance_idx ON position_report (alarm_instance_id, device_id, reported_at DESC);

-- Per-task status during an alarm (PENDING > EN_ROUTE > IN_PROGRESS > DONE / BLOCKED).
CREATE TABLE task_progress (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alarm_instance_id uuid        NOT NULL,
    task_id           uuid        NOT NULL REFERENCES task (id),
    person_id         uuid        NOT NULL REFERENCES person (id),
    state             task_state  NOT NULL,
    reported_at       timestamptz NOT NULL,
    note              text
);
CREATE INDEX task_progress_instance_idx ON task_progress (alarm_instance_id, task_id, reported_at DESC);
