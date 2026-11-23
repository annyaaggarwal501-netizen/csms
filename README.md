# CSMS: Clothing Shop Management System

A complete shop application for **Mansi Collection** (and any other clothing shop), built by Team Vertex. It follows SRS v1.0.1.
Shop name, address, GST on/off and the shop rules are all set in **Settings**, so another shop can use it without code changes.
## 🚀 Live App

**Live App:**  
https://csms-9qy1.onrender.com/login.html?next=pos.html

### 🔐 Live Demo Credentials

| Role | Username | Password |
|---|---|---|
| Owner (everything) | `owner` | `Mansi2026shop` |
| Sales staff (billing, returns, customers, stock view) | `staff` | `Staff2026shop` |

> These credentials are for accessing the live demo application.

## 🛠️ Run Your Own Version

To create your own version of the application:

1. Clone this repository.
2. Add your own `.env` file with the required credentials.
3. Add your **cloud database connection URL**.
4. Run the application.
5. After running your own instance, you can change the static/default password to your preferred password.

No code changes are required for the basic shop configuration. The shop details and settings can be customized from the application.

It runs the full demo story from start to finish:

> The Owner adds stock. Staff scan items and make a bill with correct GST. Stock goes down. The bill is shared on WhatsApp. The Owner sees it in today's report.

| | |
|---|---|
| Backend | Java 17+, Spring Boot 3.5, Spring Security (session cookie, 2 roles), Spring Data JPA + JdbcTemplate, Flyway |
| Database | **PostgreSQL** on **Neon** (free cloud), shared by every computer, so Owner and Staff always see the same data. The original MySQL 8 files are kept in `db/mysql_original/` |
| Frontend | Plain HTML/CSS/JS in `src/main/resources/static` (no build step) |
| Images | Product photos stored in the database (`product_image`), so every computer sees them |
| Excel | Apache POI |
| Tests | `TaxCalculatorTest` runs all 11 rows of `gst_test_cases.csv`; `DemoStoryIntegrationTest` (16 tests) runs the demo story, checklist and the full-app features against PostgreSQL |
| Live link | Docker image on **Render** (free), see **`docs/DEPLOY_RENDER.md`** |

> **Why PostgreSQL and not MySQL?** Free cloud MySQL plans are no longer available (Aiven retired its free plan),
> while Neon offers free PostgreSQL. The tables, columns, data and rules are the same as in the SRS; only the SQL
> dialect changed (see `docs/CLOUD_DATABASE.md`). This is a change to the SRS's database choice (MySQL 8.0)
> and should be recorded in the next SRS revision.

---

## 1. Set up a laptop (once)

1. Install **JDK 17 or newer** (Temurin works).
2. Open the `csms` folder in IntelliJ IDEA. It is a normal Maven project.
3. Create the **`.env`** file with the Neon connection details (next to `pom.xml`). Copy `.env.example` to `.env` and fill it in.
   The full steps are in **`docs/CLOUD_DATABASE.md`**. Ask the team lead for the password privately; never put it on GitHub or WhatsApp.

No database software is needed on the laptop: the database is in the Neon cloud. To look at the data, use the
**SQL Editor** in the Neon console (or pgAdmin / DBeaver).

## 2. Run the app

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```
You can also run `CsmsApplication` from IntelliJ. Then open **http://localhost:8080**.

The Neon database already contains the tables and data. When you start a newer version of the app, Flyway adds the
new changes (V6, V7, V8 …) automatically and keeps all existing bills, customers and photos.
On start-up Flyway checks this and prints *"Schema "public" is up to date"*. If you ever point the app at an **empty**
database, Flyway creates everything itself.

| Login | Password | Role | Owner PIN |
|---|---|---|---|
| `owner` | `Owner@123` | OWNER: everything | `2580` |
| `staff` | `Staff@123` | SALES_STAFF: billing, products (no cost prices), customers, returns | |

Settings can come from environment variables or the `.env` file, so no password goes into the code:
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SSL_MODE` (`require` for Neon). Other variables:
`PUBLIC_BASE_URL` (see WhatsApp below), `LINK_SECRET`, `COOKIE_SECURE` (`true` on an https link) and
`DEMO_MODE` (`false` in a real shop: hides the demo logins on the login page, and anyone still using `Owner@123` /
`Staff@123` must choose a new password at their next login).

**Without `.env`** the app looks for a local PostgreSQL (`localhost:5432`, database `clothing_shop`, user `csms`).
Set that up with `db/create_db_user.sql` if you want to work offline.

### Everyone shares one database
Every computer that uses the same `.env` uses the same Neon database: when the Owner records stock-in, the Staff
laptop sees the new stock on its next scan or page refresh. For experiments that should not change the shared data,
create a **branch** in Neon (Branches → Create branch) and put that branch's host in your own `.env`.

### Team test customers (for WhatsApp)
Edit **`db/team_test_customers.sql`**. Replace each `REPLACE_ME` with a team member's own number (with their permission),
then run it in Neon's SQL Editor. These are the **only** customers who are opted in. The 30 seed customers are opted out,
and the app refuses to message them (ERR-WA-001).

### Starting again with fresh data
`db/reset_database.sql` empties the database (all bills, customers and photos are deleted). Start the app afterwards and
Flyway rebuilds V1–V5. On Neon, prefer a separate branch for experiments instead.

### Tests
```bash
./mvnw test                 # 14 GST unit tests (no database needed); the integration tests are skipped
./mvnw test -Dit.db=true    # + 19 integration tests on a LOCAL PostgreSQL (db/create_db_user.sql), never on Neon
scripts/race_test.sh        # app must be running; two counters, one last piece, by real HTTP calls
```
The integration tests use their own database, `clothing_shop_test`, which Flyway wipes and rebuilds on every run. They cover:
- the demo bill (₹7,310, GST 5%/18%, festive offer, low-stock alert)
- a wrong payment saving nothing
- the staff discount limit and Owner PIN (TC-BIL-010)
- two counters racing for the last piece
- returns: non-returnable items, defective write-off, a credit note used for an exchange
- void reversing stock, credit and loyalty points
- hold and resume
- product import
- product photos: sample pictures, upload, JPG/PNG/WebP only, 2 MB limit
- staff never receiving cost prices over HTTP
- credit (udhaar) ledger: credit bill, repayment, void
- profit using the cost price on the day of sale
- a new staff login must change its temporary password first
- owner-only settings and stock adjustments over HTTP, activity log
- adding main categories and sub-categories (duplicates, only two levels)
- credit limit applied on the bill (staff stopped, Owner PIN approves, part cash allowed)
- setting a credit limit while registering a new customer (Owner, or staff with the Owner PIN)

Every test also checks that the stock history still adds up.

---

## 3. What is in the application

### Screens
The menu on the left groups them as **Sell · Stock · Shop · Owner**. On a phone the menu opens from the ☰ button.
The app can be **installed** like an app (Chrome → Install app / Add to Home screen).

| Screen | File | Who |
|---|---|---|
| Login (shop name from Settings) | `login.html` | everyone |
| Dashboard: today, 30-day chart, low stock, alerts, credit to collect | `dashboard.html` | Owner |
| New bill (POS): scan, search, **tap a product photo → choose colour & size**, hold/resume, register a new customer with a credit limit; an unfinished bill is remembered if the page is refreshed | `pos.html` | both |
| Returns + credit note / exchange | `returns.html` | both |
| Customers (consent, history) | `customers.html` | both |
| **Credit (udhaar)**: due / limit / available, history, receive payment, **Set credit limit** (Owner) | `credit.html` | both (limits: Owner) |
| Products with photos, WhatsApp share, CSV/Excel import, bulk photos | `products.html` | both (staff: no cost) |
| Add/edit product (size × colour grid, photo in the same form) | `product-edit.html` | Owner edits, staff view |
| **Stock**: every size/colour, low/out filters, history of each item, adjust (damaged, lost, count) | `stock.html` | both (adjust and values: Owner) |
| Stock-in (purchase) | `stock-in.html` | Owner |
| **Barcode labels**: EAN-13 sticker sheets (65/40/24/14 per A4) | `labels.html` | both |
| **Offers** (whole shop, category or product; start/end time) | `offers.html` | see: both · edit: Owner |
| **Categories**, **Suppliers** | `categories.html`, `suppliers.html` | Owner |
| **Reports**: sales, GST + HSN summary, profit, stock value + slow stock, staff; CSV/Excel/print | `reports.html` | Owner |
| **Activity log**: logins, price changes, voids, stock corrections, settings | `activity.html` | Owner |
| **Staff & logins**: add staff, temporary password, reset, unlock, switch off | `users.html` | Owner |
| **Settings**: shop details, bill footer, GST on/off + GSTIN, discount limit, return window, points | `settings.html` | Owner |
| **My account**: change password, change Owner PIN | `account.html` | everyone |
| Thermal receipt (80 mm print / PDF): Tax Invoice or Bill of Supply, Void bill | `receipt.html?id=` | both (void: Owner, same day) |
| E-bill opened from WhatsApp | `ebill.html?t=` | public, signed link |

### API
All endpoints are under `/api/v1`. Errors come back as `{code, message, field, timestamp}` using the SRS error codes.
On screen, people see only the kind of message and the text ("Error: …", "Warning: …", "Note: …"); the codes stay in the API
and the server log.

| Method | Endpoint | Who |
|---|---|---|
| POST | `/auth/login`, `/auth/logout` · GET `/auth/me` | everyone |
| GET | `/variants?barcode=` (or `?sku=`), `/variants/search?q=`, `/variants/low-stock` | Owner, Staff |
| GET / POST / PUT | `/products`, `/products/{id}` | read: both · create/edit: Owner |
| GET / POST / PUT | `/categories` (`?all=true`) | read: both · edit: Owner |
| GET / POST / PUT | `/offers`, `/offers/{id}` | read: both · edit: Owner |
| GET / POST / PUT | `/suppliers`, `/suppliers/{id}` | Owner |
| GET | `/stock?filter=all\|low\|out&q=&categoryId=`, `/stock/summary`, `/stock/{variantId}/movements`, `/stock/labels?ids=` / `?purchaseId=` | both (values: Owner) |
| POST | `/stock/adjustments` `{variantId, qtyChange, reason, note}` | Owner |
| POST / GET | `/purchases` (stock-in) | Owner |
| POST | `/sales/quote` (price the cart), `/sales` (complete) · GET `/sales/{id}`, `/sales?date=` | Owner, Staff |
| POST / GET / DELETE | `/sales/hold`, `/sales/held`, `/sales/held/{id}/resume`, `/sales/held/{id}` | Owner, Staff |
| POST | `/sales/{id}/void` `{reason, ownerPin}` | Owner (or Staff with the Owner PIN) |
| POST | `/products/import/preview`, `/products/import/commit` (multipart `file`) | Owner |
| GET | `/customers?mobile=` / `?q=`, `/customers/lookup?mobile=`, `/customers/{id}/history` | Owner, Staff |
| POST / PUT | `/customers`, `/customers/{id}`, `/customers/{id}/consent` | Owner, Staff |
| GET / POST | `/customers/credit-dues`, `/customers/{id}/credit`, `/customers/{id}/credit/repay` `{amount, mode, note}` | Owner, Staff |
| PUT | `/customers/{id}/credit-limit` `{creditLimit}` | Owner |
| GET / POST | `/returns/lookup?invoice=`, `/returns` · GET `/credit-notes/{cnNo}` | Owner, Staff |
| POST / GET | `/whatsapp/ebill/{saleId}`, `/whatsapp/product/{id}`, `/whatsapp/log` | Owner, Staff |
| GET | `/reports/dashboard`, `/reports/sales?from=&to=`, `/reports/sales.xlsx?from=&to=`, `/reports/profit?from=&to=`, `/reports/stock-value`, `/reports/staff?from=&to=` | Owner |
| GET / PUT | `/settings` (read: everyone), PUT `/settings` | Owner |
| GET / POST / PUT | `/users`, `/users/{id}`, `/users/{id}/reset-password`, `/users/{id}/unlock` · GET `/audit?action=&limit=` | Owner |
| POST | `/auth/change-password` `{currentPassword, newPassword}` (everyone), `/auth/change-pin` (Owner) | |
| GET / POST | `/alerts`, `/alerts/{id}/resolve` | Owner |
| GET | `/public/ebill/{token}`, `/public/shop`, `/public/login-info` | no login |

A Postman collection is in `docs/CSMS.postman_collection.json`.

### Business rules implemented
- **GST (REQ-BIL-2..5).** Rates come from the `gst_slab` table. APPAREL tests the 5% slab first: if net ÷ 1.05 ≤ ₹2,500, the rate is 5%, otherwise 18%. FABRIC is 5% at any price. Discounts apply **before** tax. CGST = tax/2 rounded half-up, and SGST = the rest. WARN-GST-001 appears for net prices from ₹2,625.01 to ₹2,950. The receipt shows a tax summary for each rate.
- **Atomic sale (REQ-BIL-7).** One `@Transactional` method does all of this together:
  - locks the variants (`SELECT … FOR UPDATE`, in id order)
  - prices the cart again on the server
  - checks that payments equal the rounded total
  - inserts the sale, lines and payments
  - deducts stock with SALE movements
  - updates loyalty points and credit
  - assigns an invoice number

  If anything fails, nothing is saved.
- **Invoice numbers.** `MC-2627-000001` restarts each financial year. They come from `doc_sequence` (V3), which is locked inside the sale transaction, so numbers have no gaps and no duplicates. Credit notes use `CN-2627-00001`.
- **Offers.** The best item-level PERCENT/FLAT offer is applied automatically, by product or category (including the parent category). For example, "Festive 15% off on Sarees" runs 1 Oct – 15 Nov 2026. A manual discount does not stack with an offer: the larger one wins. Staff can give up to 10% (changeable in Settings); above that needs the Owner PIN (ERR-BIL-005), and the override is written to the activity log.
- **Hold bill (F8).** Parks the cart as a HELD sale (no invoice number, no stock change), so the counter can serve the next customer. Any counter can resume it. Resuming while another bill is open parks that bill first. Held bills older than 24 hours are discarded.
- **Void bill (REQ-BIL-9).** Owner only (or Staff with the Owner PIN), only on the same day, and a reason is required. It puts stock back with VOID movements, takes back loyalty points, reduces credit due and restores the balance of any credit note used to pay. The invoice number stays in the series, marked CANCELLED, so there are no GST gaps. A VOID_BILL alert goes to the dashboard. For older bills, use Returns.
- **GST or no GST (Settings).** The app works for any shop. Owner → **Settings** → choose
  *"not GST-registered"* or *"GST-registered"*.
  - **Not registered (default):** bills are a **Bill of Supply** with no tax lines; HSN codes and GST columns are hidden.
  - **Registered:** full Tax Invoice with CGST/SGST, HSN codes and GST reports.

  Bills already made keep their type. The setting is stored in the database (`shop_setting`, V6).
- **Product photos.** One photo per product, stored in the database (`product_image`), so every computer and phone sees it.
  - **Adding:** choose or take the photo **inside the Add Product form**; the same **Save product** click saves the product
    and the photo. The 📷 **Photo** button in the product list changes a photo without opening the product.
  - **Many at once:** **📷 Bulk photos** on the Products page. Select all files; each is matched to its product by
    file name (`9.jpg` → product 9, or `Banarasi Silk Saree.jpg` → that product).
  - **Real-looking sample photos:** `docs/PHOTO_PROMPTS.md` has a ready Gemini/ChatGPT prompt for each of the 50
    products (catalogue style, pastel café setting). Generate, save as `<product number>.jpg`, then use Bulk photos.
  - **Size:** phone photos are made smaller in the browser (JPEG, at most 1200 px).
  - **Speed:** the server also stores a small 320 px copy (thumbnail), used by the product list and the POS.
  - **Rules:** JPG, PNG or WebP up to 2 MB; anything else is refused with ERR-PRD-006 (checked from the file's content).
  - **Product photos:** realistic catalogue photos for the 50 seeded products are in `db/product_photos/` (`9.jpg` = product 9)
    and already in the Neon database. For a new database, upload them with **📷 Bulk photos**. At start-up the app makes the
    small copy (thumbnail) for any photo that has none (`ThumbnailBackfill`).
  - **Drawn sample pictures:** an empty database first gets drawn catalogue-style pictures, until real photos are added.
    They are made by `scripts/make_sample_pictures.py` (V7). No photos are copied from the internet. V7 never replaces
    a real photo.
- **Bulk import (REQ-PRD-5).** Upload a .csv or .xlsx; the column format is in `db/products_import.csv`.
  - **Preview** checks every row and marks it VALID, WARNING or INVALID, with error codes. Nothing is saved at this step.
  - Errors it catches: duplicate barcodes or sizes/colours (ERR-PRD-002), selling price below cost (ERR-PRD-003), bad HSN, unknown category, files over 5 MB or empty (ERR-PRD-001), and wrong file types (ERR-PRD-005).
  - Warnings: WARN-GST-001, an HSN that doesn't match the tax class, and a category name used under more than one parent (write `Kids > Winterwear` to choose).
  - **Import** saves the good rows in one transaction. Rows for the same product name and category become one product. An existing product gets the new sizes and colours added.
  - Gender comes from the category's parent. Innerwear, blouses, nightwear and infant wear default to non-returnable.
  - `db/sample_import_new_products.csv` is a ready-made demo file: 8 good rows, 3 deliberately bad.
- **Payments.** Cash (with change), UPI, Card, Credit (needs a customer, see the credit limit below) and Credit note (balance and expiry are checked). Split payments are allowed. A cash row has no reference box; UPI, card, credit and credit note rows do.
  A bill can be saved only when the payments add up to the total and, for the cash part, the **cash received** from the
  customer is at least that amount (quick buttons: Exact, ₹100/₹500/₹2,000 rounded up); the change to return is shown.
- **Stock.** Every change goes through `StockService`, which writes `stock_movement`. Stock cannot go negative (ERR-INV-001). LOW_STOCK and OUT_OF_STOCK alerts are raised without duplicates and close themselves after a stock-in. Stock-in updates the weighted-average cost.
- **Returns.** Only within 7 days; after that the Owner PIN is needed (WARN-RET-001). Non-returnable items are blocked (ERR-RET-003), and you can't return more than was sold (ERR-RET-001). Returned items go back into stock; a "defective" tick writes them off instead. The credit note is valid for 90 days. Loyalty points are reversed. "Exchange" opens the POS with the credit note already applied as a payment.
- **Credit (udhaar).** A credit bill adds to the customer's due and writes a SALE row to `customer_credit_txn`; a payment
  (cash/UPI/card) writes a REPAYMENT row; voiding a credit bill writes a VOID row. Every row keeps the balance after it,
  so the amount owed can always be explained. Existing credit was copied into the ledger by V8.
- **Credit limit.** Each customer has a limit (the most they may owe; 0 = no credit). On a bill, staff cannot put more on
  credit than the available amount (limit − already due): they take the rest in cash/UPI, or the Owner approves with the PIN
  (ERR-CUS-003). The Owner can go over it after a confirmation. Overrides go to the activity log and the dashboard alerts.
  The Owner sets limits on the Credit page or the customer form; a limit can also be set while registering a new customer on
  New bill (staff need the Owner PIN). Every change is in the activity log.
- **Remembered on this device.** The unfinished bill (items, customer, payments) survives a refresh or closed tab and comes
  back with fresh prices; chosen filters and tabs (Stock, Reports, Offers, Credit, Customers), the barcode label list and the
  last username are remembered too. Stored per user in the browser; never prices, stock or passwords.
- **Profit.** Each bill line saves the item's cost price at the moment of sale (`sale_line.unit_cost`, V8), so a later
  purchase at a different cost does not change old profit. Profit = sale value without GST − cost; returned pieces are left out.
- **Stock adjustments.** Owner only, with a reason (Damaged, Lost, Counting correction, Sample, Other + note). Written as an
  ADJUSTMENT movement, never below 0, and logged in the activity log.
- **Barcode labels.** Standard EAN-13 drawn in the browser (no plugin), checked with a barcode reader for every sheet size.
  Print a whole stock-in bill at once (one label per piece received) or chosen items.
- **Staff logins.** The Owner creates a login with a temporary password; the person must choose their own password before
  they can use anything (ERR-ACC-006). Passwords: at least 8 characters with a letter and a number. At least one active Owner
  always remains, and you cannot switch off your own login.
- **Activity log (`audit_log`, V8).** Logins (and wrong passwords), new logins, password resets, price changes, new products,
  voids, PIN discount overrides, stock adjustments, credit payments, WhatsApp consent, offers, categories, suppliers and
  settings. It cannot be edited from the app.
- **Security.**
  - BCrypt cost 12 for passwords.
  - The account locks after 5 failed logins; the Owner can unlock it.
  - Session cookie: HttpOnly, SameSite=Strict, 30-minute idle timeout.
  - The server checks the role on every call.
  - **Cost prices are never sent to Staff.** The field is left out of the JSON.
- **WhatsApp (Click-to-Chat mode).** The app builds a `wa.me/91…` link with the message already filled in, and staff press Send.
  - **E-bill:** a signed link (HMAC) to `ebill.html`, so other bills can't be opened by guessing numbers.
  - **Product share:** the browser draws a product card image. On a phone it opens the share sheet with the image and text. On a PC it downloads the image and opens WhatsApp Web.
  - Every link is checked against consent, and messages are logged in `wa_message`.

### What V3–V8 add
- **V3:** `doc_sequence` (invoice and credit note counters), `credit_note.reason`, and two indexes.
- **V4:** `sale.void_reason`, `voided_by` and `voided_at`.
- **V5:** the `product_image` table and one sample picture per seeded product.
- **V6:** `shop_setting` (GST on/off, default off), optional HSN code, photo thumbnails.
- **V7:** better drawn sample pictures (replaces only pictures that are still samples).
- **V8:** forced password change and last login on `app_user`, `sale_line.unit_cost` (profit), `customer_credit_txn`
  (credit ledger, filled from existing credit bills), `audit_log`, and shop details/rules in `shop_setting`.

V1 and V2 have the same tables, columns, rules and data as the delivered MySQL files; they are only written in
PostgreSQL syntax. The delivered MySQL originals are in `db/mysql_original/`.

### Not included yet (later versions per the SRS)
WhatsApp Cloud API and campaigns (the app uses Click-to-Chat links), loyalty points redemption, expenses (rent, salary) in the
profit report, and the QR code on receipts. Backups: Neon keeps a restore history (see `docs/DEPLOY_RENDER.md`).

---

## 4. WhatsApp from a phone
On the live link (Render) set `PUBLIC_BASE_URL` to that link and e-bills work from anywhere. When running on a laptop,
the customer's phone must be able to reach the laptop:
- **Same Wi-Fi:** find the laptop's IP (`ipconfig` / `ifconfig`). Start the app with `PUBLIC_BASE_URL=http://192.168.x.x:8080`, and allow port 8080 in the firewall.
- **Anywhere:** use a tunnel (for example `ngrok http 8080`) and set `PUBLIC_BASE_URL` to the tunnel URL.

## 5. Project structure
```
csms/
├── src/main/java/com/vertex/csms/
│   ├── common/      ApiException, error handler, Money, FinancialYear, DocNumberService, settings, activity log
│   ├── security/    AppUser, login/lockout, roles, Owner PIN, staff logins, password rules
│   ├── product/     Product, Variant, Category, EAN-13 barcodes, product + variant APIs
│   ├── inventory/   StockService (the only place stock changes), stock screen + adjustments, labels, Purchase, suppliers, alerts
│   ├── billing/     TaxCalculator, GstSlab, Offer, Sale, SaleLine, Payment, BillingService, receipts
│   ├── returns/     ReturnService, CreditNote
│   ├── customer/    Customer, consent, history, credit (udhaar) ledger
│   ├── messaging/   WhatsApp links, signed e-bill links, wa_message log
│   └── report/      dashboard, sales, profit, stock value, staff reports, Excel export
├── src/main/resources/
│   ├── db/migration/  V1__schema.sql, V2__seed_data.sql, V3__prototype_additions.sql, V4__void_bill.sql,
│   │                  V5__product_images.sql … V8__full_app_features.sql (all PostgreSQL)
│   ├── static/        the screens (HTML, css/, js/), manifest.json + sw.js (installable app)
│   └── application.properties
├── src/test/java/     TaxCalculatorTest (+ gst_test_cases.csv), DemoStoryIntegrationTest (PostgreSQL)
├── db/                create_db_user.sql, reset_database.sql, week1_queries.sql, team_test_customers.sql,
│                      products_import.csv, sample_import_new_products.csv, customers.csv,
│                      mysql_original/ (the delivered MySQL V1–V4)
├── scripts/           race_test.sh, make_sample_pictures.py (V7), make_placeholder_images.py (V5)
├── Dockerfile, render.yaml   live deployment on Render
└── docs/              DEPLOY_RENDER.md, CLOUD_DATABASE.md, OWNER_GUIDE.md, DEMO_SCRIPT.md, Postman collection
```
