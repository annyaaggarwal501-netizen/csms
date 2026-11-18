-- V6__shop_settings_no_gst_thumbnails.sql
-- 1. Shop settings the Owner can change without touching code. The first one: is the shop GST-registered?
--    Mansi Collection is a small shop that is NOT registered, so bills are a "Bill of Supply" with no tax
--    (SRS REQ-BIL-5, Composition/Unregistered mode). Set to 'true' if the shop registers for GST later.
CREATE TABLE shop_setting (
  setting_key   VARCHAR(50)  PRIMARY KEY,
  setting_value VARCHAR(500) NOT NULL,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO shop_setting (setting_key, setting_value) VALUES ('gst_registered', 'false');

-- 2. Without GST the HSN code is not needed, so it becomes optional (the CHECK rule still applies when it is filled in).
ALTER TABLE product ALTER COLUMN hsn_code DROP NOT NULL;

-- 3. A small copy of each photo (about 320 px) so product lists and the POS load fast.
--    NULL = no small copy (sample pictures are already tiny); the full photo is used instead.
ALTER TABLE product_image ADD COLUMN thumb BYTEA NULL;
