-- Alarms are system-wide: no affected area. The raiser's short description travels in the signed payload.
ALTER TABLE alarm_instance
    ADD COLUMN description text,
    DROP COLUMN area_center,
    DROP COLUMN area_radius_m;

-- NEED HELP: a participant cannot carry out an assigned responsibility.
ALTER TABLE alarm_reception ADD COLUMN help_requested_at timestamptz;
