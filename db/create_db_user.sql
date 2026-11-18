-- LOCAL PostgreSQL only (not needed for Neon - Neon already gives you a database and user).
-- Run ONCE on your laptop as the "postgres" super-user, e.g. in pgAdmin's Query Tool or:
--     psql -U postgres -f db/create_db_user.sql
-- Creates the app's login and its databases. Flyway creates the tables the first time the app starts.
CREATE ROLE csms LOGIN PASSWORD 'csms_dev_pw';
CREATE DATABASE clothing_shop OWNER csms ENCODING 'UTF8';
-- Used only by the automated integration tests (./mvnw test -Dit.db=true); wiped on every test run.
CREATE DATABASE clothing_shop_test OWNER csms ENCODING 'UTF8';
