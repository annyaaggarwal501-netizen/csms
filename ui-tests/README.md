# Browser tests – New bill payments

These tests open the app in a real (hidden) Chrome browser and click through the New bill screen like a cashier:
74 checks in three files.

| File | What it checks |
|---|---|
| `payments-basic.test.js` (26) | Single cash, cash received / change / Short, Exact buttons, split cash + UPI / card, a row filling the rest by itself, removing a row, cart changes during a split, UPI only, credit without customer, F9 while locked, bill kept after a refresh |
| `payments-split-credit.test.js` (29) | Bill ₹5,000 paid Cash + UPI + Credit note + Credit + Card at ₹500 each ("₹2,500 left to collect"), saved with all five; customer with a ₹10,000 credit limit (inside, over by ₹500, credit + cash); only cash can be more than the bill (credit / card named, server refuses too) |
| `payments-edge.test.js` (19) | Negative amount, credit note without a number or unknown, two cash rows, grey row switched to card, more pieces than in stock, more than 999 pieces, empty cart, held bill resumed, very large cash received, staff + Owner PIN for credit (cancel, wrong PIN) |

## Run them

**Only against the local app with a local database** – they create bills, returns and customers. They refuse any
address other than `http://localhost:8080`, and they ask the app whether its database is on this computer
(`/api/v1/public/login-info` → `localDatabase`): if the app is connected to Neon, they stop before doing anything.

1. Start the app with the **local** PostgreSQL, not Neon:
   - once: create the local database with `db/create_db_user.sql` (see the main README);
   - rename `.env` to `.env.neon` for the test run (put it back afterwards), then `./mvnw spring-boot:run`.
2. Once, in this folder: `npm install` and `npx playwright install chromium`
3. `npm test` – each file prints PASS/FAIL per check and a summary; the command fails if any check fails.

Logins used: `owner` / `Owner@123` and `staff` / `Staff@123` (change with `OWNER_PASS=… STAFF_PASS=… npm test`).
Set `SHOTS_DIR=/some/folder` to also save screenshots of the payment box.
