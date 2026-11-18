# Put the shop app online (live link) – Render + Neon

After these steps the shop has one link, for example **https://mansi-shop.onrender.com**, that works on every
phone and computer. The data stays in the same Neon database you already use.

```
 Phone / PC browser  ──https──►  Render (runs the Java app, free)  ──SSL──►  Neon (PostgreSQL database, free)
```

You need: a **GitHub** account, a **Render** account (sign in with GitHub) and the Neon connection details from your `.env`.
It takes about 20 minutes the first time.

---

## Step 1 – Put the code on GitHub (private)

1. github.com → **New repository** → name `shop-manager` → choose **Private** → Create.
2. Upload the `csms` folder. The easiest way is **GitHub Desktop**:
   File → Add local repository → choose the `csms` folder → "create a repository" → Publish → keep **Private** ticked.
3. Check on github.com that **`.env` is NOT in the list of files.** (`.gitignore` keeps it out. The password must never be on GitHub.)

## Step 2 – Create the web service on Render

1. render.com → sign in with GitHub → **New → Blueprint**.
2. Choose the `shop-manager` repository. Render reads `render.yaml` and shows one web service, `shop-manager`.
3. Render asks for the values marked secret. Fill them in from your `.env`:

   | Key | Value |
   |---|---|
   | `DB_HOST` | your Neon host, **without** `-pooler` (e.g. `ep-xxxx-xxxx.c-3.ap-southeast-1.aws.neon.tech`) |
   | `DB_PASSWORD` | your Neon password |
   | `PUBLIC_BASE_URL` | leave empty for now |

   These are already set by `render.yaml`: `DB_NAME=neondb`, `DB_USER=neondb_owner`, `DB_SSL_MODE=require`,
   `COOKIE_SECURE=true`, `DEMO_MODE=false` and a random `LINK_SECRET`.
4. Click **Apply**. The first build takes 5–10 minutes (Render downloads Java and builds the app from the `Dockerfile`).
   When the log shows `Started CsmsApplication`, the service is **Live**.
5. Copy the link at the top of the page (e.g. `https://shop-manager-xxxx.onrender.com`).
   Go to **Environment** → set `PUBLIC_BASE_URL` to that link → **Save** (Render restarts the app). WhatsApp e-bills now open on any phone.

## Step 3 – First login on the live link

Because `DEMO_MODE=false`, the demo passwords are not shown, and **every login still using `Owner@123` or `Staff@123`
must choose a new password** before it can do anything.

1. Open the link → log in as `owner` / `Owner@123` → choose a new strong password.
2. **My account → Owner PIN:** change the PIN from `2580` to a new PIN.
3. **Settings:** check the shop name, address, phone, bill footer and GST choice.
4. **Staff & logins:** add one login for each staff member (each gets a temporary password to change at first login).
   Switch off the demo `staff` login if nobody uses it.
5. On each phone/PC open the link in Chrome → menu → **Install app** (or *Add to Home screen*). It opens like an app.

## Good to know

- **Free plan sleeps.** If nobody opens the app for 15 minutes, Render puts it to sleep; the next visit takes about a
  minute to wake up, after that it is fast. Open the link a minute before the shop opens. A paid Render plan never sleeps.
- **Updates.** Change the code → commit and push in GitHub Desktop → Render builds and restarts by itself (about 5 minutes).
  New database changes (V9, V10 …) are applied automatically by Flyway at start-up; existing data is kept.
- **Same database for laptops.** A laptop with the same `.env` still works (`./mvnw spring-boot:run`) and shares the same
  data as the live link. Use a Neon **branch** for experiments so the shop's real data is never touched.
- **Backups.** Neon keeps a restore history of the database (Neon console → *Restore*; the free plan keeps a short window,
  see the console for the current limit). Once a month also download the reports as Excel/CSV, or make a full copy:
  `pg_dump "postgresql://neondb_owner:PASSWORD@HOST/neondb?sslmode=require" > shop_backup.sql`.
- **Secrets.** The Neon password lives only in Render's *Environment* page and in your private `.env`. If it was ever shared
  (chat, screenshot), reset it in Neon (Roles → Reset password) and update Render and `.env`.

## If something goes wrong

| What you see | What to do |
|---|---|
| Build fails on Render | Open **Logs**. Usually a typo in the code; fix, commit, push. |
| `password authentication failed` in the log | `DB_PASSWORD` or `DB_HOST` is wrong (host must not contain `-pooler`). |
| Login page loads but login says the server can't be reached | The app is still waking up; wait a minute and try again. |
| WhatsApp e-bill opens `localhost` | `PUBLIC_BASE_URL` is not set to the live link (Step 2.5). |
| Someone is locked out after 5 wrong passwords | Owner → **Staff & logins** → **Unlock** (or **Reset password**). |
| The Owner forgot the password | In Neon's SQL Editor run `db/reset_owner_password.sql` (sets a temporary password that must be changed at login). |
