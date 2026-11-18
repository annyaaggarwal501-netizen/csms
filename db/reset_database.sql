-- "Same data for everyone": empty the database, then start the app again.
-- Flyway re-runs V1 (schema), V2 (seed data), V3, V4 and V5 (sample product pictures) on startup.
--
-- Run it connected to the database you want to reset:
--   Neon  : Neon console -> SQL Editor (branch + database neondb), paste and Run.
--   Local : psql -h localhost -U csms -d clothing_shop -f db/reset_database.sql
--
-- WARNING: this deletes ALL data in that database - bills, customers, uploaded photos.
-- On Neon, a safer way to experiment is a separate branch (Branches -> Create branch).
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
