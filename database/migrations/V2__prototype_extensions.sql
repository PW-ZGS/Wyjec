-- Prototype-only tables. They stand in for infrastructure the target architecture keeps
-- outside the operational DB (HSM, object store, IAM) or add display data the UI needs.

-- Stand-in for the HSM: secrets addressed by *_hsm_ref columns (PSKs, alarm private keys).
CREATE TABLE prototype_hsm_secret (
    ref        text PRIMARY KEY,
    secret     bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Stand-in for the object store holding published scenario bundles.
CREATE TABLE prototype_object_store (
    object_key   text PRIMARY KEY,
    content      bytea       NOT NULL,
    content_type text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Server-side projection of an alarm instance (raise + optional cancel) for the operations view.
-- display_no gives the human-friendly "EV#4323" number; area is the affected zone on the map.
CREATE TABLE alarm_instance (
    id                     uuid PRIMARY KEY,
    display_no             bigserial UNIQUE,
    alarm_definition_id    uuid        NOT NULL REFERENCES alarm_definition (id),
    scenario_version_id    uuid        NOT NULL REFERENCES scenario_version (id),
    raised_at              timestamptz NOT NULL,
    raised_by_device_id    uuid        NOT NULL REFERENCES device (id),
    cancelled_at           timestamptz,
    cancelled_by_device_id uuid REFERENCES device (id),
    area_center            geography(Point, 4326),
    area_radius_m          int
);
ALTER SEQUENCE alarm_instance_display_no_seq RESTART WITH 4321;

-- Admin panel login. Each operator acts through a console device of a person.
CREATE TABLE operator_account (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username          text NOT NULL UNIQUE,
    password_hash     text NOT NULL,
    person_id         uuid NOT NULL REFERENCES person (id),
    console_device_id uuid NOT NULL REFERENCES device (id)
);

-- Devices driven by the demo simulator instead of a real phone.
CREATE TABLE demo_simulated_device (
    device_id uuid PRIMARY KEY REFERENCES device (id)
);
