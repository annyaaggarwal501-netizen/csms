-- Week 1 practice queries, PostgreSQL version (Neon SQL Editor, pgAdmin, DBeaver or psql).
-- These are the same queries the Java code runs, so every number on the screens can be checked by hand.
-- (The original MySQL version is in db/mysql_original/ for comparison.)

-- 1. Scan a barcode (VariantController -> GET /api/v1/variants?barcode=)
SELECT v.variant_id, p.name, v.size, v.colour, v.sku, v.selling_price, v.mrp, v.stock_qty,
       p.tax_class, p.hsn_code, p.is_returnable
FROM variant v JOIN product p USING (product_id)
WHERE v.barcode = '2000000000015';

-- 2. Search products (ProductController -> GET /api/v1/products?q=kurti). ILIKE ignores upper/lower case.
SELECT p.product_id, p.name, p.brand, COUNT(v.variant_id) AS variants, SUM(v.stock_qty) AS stock,
       MIN(v.selling_price) AS from_price
FROM product p JOIN variant v USING (product_id)
WHERE p.name ILIKE '%kurti%' AND p.is_active = TRUE
GROUP BY p.product_id, p.name, p.brand;

-- 3. Low-stock list (dashboard). Seed data: 14 at 0 (out of stock) and 33 at 1-2 (low).
SELECT v.sku, p.name, v.size, v.colour, v.stock_qty, v.reorder_level
FROM variant v JOIN product p USING (product_id)
WHERE v.stock_qty <= v.reorder_level
ORDER BY v.stock_qty, p.name;

-- 4. Find a customer by mobile (POS customer lookup)
SELECT * FROM customer WHERE mobile = '9688281126';

-- 5. Today's sales (dashboard "Sales today"). A bill stays a sale of its day after a return,
--    so PARTIALLY_RETURNED / RETURNED count too; returns are reported separately from credit_note.
SELECT COUNT(*) AS bills, COALESCE(SUM(grand_total), 0) AS sales
FROM sale
WHERE status IN ('COMPLETED', 'PARTIALLY_RETURNED', 'RETURNED') AND CAST(completed_at AS DATE) = CURRENT_DATE;

SELECT COUNT(*) AS credit_notes, COALESCE(SUM(amount), 0) AS returns_today
FROM credit_note WHERE CAST(created_at AS DATE) = CURRENT_DATE;

-- 6. GST by rate (dashboard "GST collected today"). CGST = half of the tax rounded, SGST = the rest.
SELECT sl.gst_rate, SUM(sl.quantity) AS pieces, SUM(sl.taxable_value) AS taxable, SUM(sl.tax_amount) AS tax,
       ROUND(SUM(sl.tax_amount) / 2, 2) AS cgst, SUM(sl.tax_amount) - ROUND(SUM(sl.tax_amount) / 2, 2) AS sgst
FROM sale_line sl JOIN sale s USING (sale_id)
WHERE s.status IN ('COMPLETED', 'PARTIALLY_RETURNED', 'RETURNED') AND CAST(s.completed_at AS DATE) = CURRENT_DATE
GROUP BY sl.gst_rate;

-- 7. Check the stock ledger: stock in variant must equal the sum of stock_movement. Must return 0.
SELECT COUNT(*) AS mismatches
FROM variant v
LEFT JOIN (SELECT variant_id, SUM(qty_change) AS total FROM stock_movement GROUP BY variant_id) m USING (variant_id)
WHERE v.stock_qty <> COALESCE(m.total, 0);

-- 8. Product photos: which products have a real photo, which still have the sample picture.
SELECT p.product_id, p.name, i.content_type, octet_length(i.data) AS bytes, i.is_placeholder, i.updated_at
FROM product p LEFT JOIN product_image i USING (product_id)
ORDER BY i.is_placeholder, p.name;

-- 9. The billing transaction: all-or-nothing (what BillingService.complete() does).
--    One Floral Cotton Kurti S/Blue (variant 1, Rs 1,050 incl. 5% GST) sold for cash by staff (user 2).
--    Practise it, then ROLLBACK instead of COMMIT so nothing is saved.
--    (In Neon's SQL Editor run the whole block at once.)
BEGIN;
SELECT stock_qty FROM variant WHERE variant_id = 1 FOR UPDATE;          -- lock the row; a 2nd counter waits here
WITH new_sale AS (
  INSERT INTO sale (invoice_no, customer_id, created_by, status, subtotal, discount_total, taxable_total,
                    cgst_total, sgst_total, round_off, grand_total, completed_at)
  VALUES ('MC-2627-999001', NULL, 2, 'COMPLETED', 1050.00, 0.00, 1000.00, 25.00, 25.00, 0.00, 1050.00, NOW())
  RETURNING sale_id
), line AS (
  INSERT INTO sale_line (sale_id, variant_id, quantity, unit_price, unit_discount, gst_rate, taxable_value, tax_amount)
  SELECT sale_id, 1, 1, 1050.00, 0.00, 5.00, 1000.00, 50.00 FROM new_sale
), pay AS (
  INSERT INTO payment (sale_id, mode, amount) SELECT sale_id, 'CASH', 1050.00 FROM new_sale
)
INSERT INTO stock_movement (variant_id, type, qty_change, ref_type, ref_id, user_id)
SELECT 1, 'SALE', -1, 'SALE', sale_id, 2 FROM new_sale;
UPDATE variant SET stock_qty = stock_qty - 1 WHERE variant_id = 1;      -- CHECK (stock_qty >= 0) stops overselling
ROLLBACK;   -- practice only. The app COMMITs; on any error it rolls back everything.
