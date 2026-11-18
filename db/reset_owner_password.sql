-- Use ONLY when the Owner has forgotten the password. Run in Neon -> SQL Editor.
-- Sets the temporary password  ShopReset2026  for the login 'owner', unlocks it, and forces a new password at login.
-- Change 'owner' below if the Owner's username is different.
UPDATE app_user
SET password_hash = '$2a$12$78a8WOyPqjmEph4rZsw60OZ2CBXJcDUQr0kGfQ9ziU4cvXwCcfb.a',
    failed_attempts = 0,
    locked = FALSE,
    is_active = TRUE,
    must_change_password = TRUE
WHERE username = 'owner';
-- Then log in as owner / ShopReset2026 and choose a new password straight away.
