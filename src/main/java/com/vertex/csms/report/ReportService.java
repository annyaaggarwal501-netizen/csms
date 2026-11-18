package com.vertex.csms.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.vertex.csms.billing.TaxCalculator;

/**
 * Dashboard and sales report. Plain SQL (the same queries the team practised in Week 1), so every number
 * can be checked by hand in MySQL Workbench.
 *
 * "Sold" bills = COMPLETED, PARTIALLY_RETURNED or RETURNED: a bill stays a sale of its day even after a
 * return; the return shows separately (credit notes) and is subtracted in "net after returns".
 */
@Service
public class ReportService {

    static final String SOLD = " s.status IN ('COMPLETED','PARTIALLY_RETURNED','RETURNED')";

    private final JdbcTemplate jdbc;
    private final com.vertex.csms.common.SettingsService settings;

    public ReportService(JdbcTemplate jdbc, com.vertex.csms.common.SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Map<String, Object> dashboard() {
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today);
        out.put("gstRegistered", settings.isGstRegistered());
        out.putAll(sales(today, today, false));

        out.put("lowStockCount", jdbc.queryForObject("""
                SELECT COUNT(*) FROM variant v JOIN product p USING (product_id)
                WHERE p.is_active = TRUE AND v.stock_qty <= v.reorder_level AND v.stock_qty > 0""", Long.class));
        out.put("outOfStockCount", jdbc.queryForObject("""
                SELECT COUNT(*) FROM variant v JOIN product p USING (product_id)
                WHERE p.is_active = TRUE AND v.stock_qty = 0""", Long.class));
        out.put("lowStock", jdbc.queryForList("""
                SELECT v.variant_id, v.sku, p.name, v.size, v.colour, v.stock_qty, v.reorder_level
                FROM variant v JOIN product p USING (product_id)
                WHERE p.is_active = TRUE AND v.stock_qty <= v.reorder_level
                ORDER BY v.stock_qty, p.name LIMIT 60"""));
        out.put("topVariants", jdbc.queryForList("""
                SELECT p.name, v.size, v.colour, v.sku, SUM(sl.quantity) AS qty, SUM(sl.taxable_value + sl.tax_amount) AS value
                FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                JOIN variant v ON v.variant_id = sl.variant_id JOIN product p ON p.product_id = v.product_id
                WHERE """ + SOLD + " AND s.completed_at >= ? GROUP BY v.variant_id, p.name, v.size, v.colour, v.sku"
                + " ORDER BY qty DESC, value DESC LIMIT 5", today.minusDays(29).atStartOfDay()));
        out.put("last30Days", jdbc.queryForList("""
                SELECT CAST(s.completed_at AS DATE) AS day, COUNT(*) AS bills, SUM(s.grand_total) AS sales
                FROM sale s WHERE """ + SOLD + " AND s.completed_at >= ? GROUP BY CAST(s.completed_at AS DATE) ORDER BY day",
                today.minusDays(29).atStartOfDay()));
        out.put("openAlerts", jdbc.queryForList("""
                SELECT alert_id, alert_type, severity, message, created_at FROM alert_log
                WHERE resolved_at IS NULL ORDER BY alert_id DESC LIMIT 15"""));
        out.put("credit", jdbc.queryForMap(
                "SELECT COUNT(*) AS customers, COALESCE(SUM(credit_due), 0) AS total FROM customer WHERE credit_due > 0"));
        out.put("pendingWhatsApp", jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_message WHERE status = 'QUEUED'", Long.class));
        out.put("recentBills", jdbc.queryForList("""
                SELECT s.sale_id, s.invoice_no, s.status, s.completed_at, s.grand_total, c.name AS customer_name,
                       u.full_name AS cashier
                FROM sale s JOIN app_user u ON u.user_id = s.created_by
                LEFT JOIN customer c ON c.customer_id = s.customer_id
                WHERE """ + SOLD + " ORDER BY s.sale_id DESC LIMIT 8"));
        return out;
    }

    /** Sales report for a date range, both dates included (REQ-REP-1, REQ-REP-2). */
    public Map<String, Object> sales(LocalDate from, LocalDate to, boolean withBills) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS bills, COALESCE(SUM(s.subtotal), 0) AS gross, COALESCE(SUM(s.discount_total), 0) AS discounts,
                       COALESCE(SUM(s.taxable_total), 0) AS taxable, COALESCE(SUM(s.cgst_total), 0) AS cgst,
                       COALESCE(SUM(s.sgst_total), 0) AS sgst, COALESCE(SUM(s.round_off), 0) AS round_off,
                       COALESCE(SUM(s.grand_total), 0) AS net_sales
                FROM sale s WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ?", start, end);
        Map<String, Object> returns = jdbc.queryForMap("""
                SELECT COUNT(*) AS credit_notes, COALESCE(SUM(amount), 0) AS returns_value
                FROM credit_note WHERE created_at >= ? AND created_at < ?""", start, end);
        long bills = ((Number) totals.get("bills")).longValue();
        BigDecimal net = (BigDecimal) totals.get("net_sales");
        totals.put("avg_bill", bills == 0 ? BigDecimal.ZERO : net.divide(BigDecimal.valueOf(bills), 2, java.math.RoundingMode.HALF_UP));
        totals.putAll(returns);
        totals.put("net_after_returns", net.subtract((BigDecimal) returns.get("returns_value")));
        out.put("totals", totals);

        // GST by rate: taxable, CGST, SGST per slab (the week-1 "GST by rate" query).
        List<Map<String, Object>> gst = jdbc.queryForList("""
                SELECT sl.gst_rate, SUM(sl.quantity) AS qty, SUM(sl.taxable_value) AS taxable, SUM(sl.tax_amount) AS tax
                FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ? GROUP BY sl.gst_rate ORDER BY sl.gst_rate",
                start, end);
        for (Map<String, Object> row : gst) {
            TaxCalculator.Split split = TaxCalculator.split((BigDecimal) row.get("tax"));
            row.put("cgst", split.cgst());
            row.put("sgst", split.sgst());
        }
        out.put("gstByRate", gst);
        out.put("payments", jdbc.queryForList("""
                SELECT pay.mode, COUNT(*) AS count, SUM(pay.amount) AS amount
                FROM payment pay JOIN sale s ON s.sale_id = pay.sale_id
                WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ? GROUP BY pay.mode ORDER BY amount DESC",
                start, end));

        if (withBills) {
            out.put("daily", jdbc.queryForList("""
                    SELECT CAST(s.completed_at AS DATE) AS day, COUNT(*) AS bills, SUM(s.subtotal) AS gross,
                           SUM(s.discount_total) AS discounts, SUM(s.taxable_total) AS taxable,
                           SUM(s.cgst_total + s.sgst_total) AS gst, SUM(s.grand_total) AS net_sales
                    FROM sale s WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ?"
                    + " GROUP BY CAST(s.completed_at AS DATE) ORDER BY day", start, end));
            out.put("hsn", jdbc.queryForList("""
                    SELECT p.hsn_code, sl.gst_rate, SUM(sl.quantity) AS qty, SUM(sl.taxable_value) AS taxable,
                           SUM(sl.tax_amount) AS tax
                    FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                    JOIN variant v ON v.variant_id = sl.variant_id JOIN product p ON p.product_id = v.product_id
                    WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ?"
                    + " GROUP BY p.hsn_code, sl.gst_rate ORDER BY p.hsn_code, sl.gst_rate", start, end));
            out.put("bills", jdbc.queryForList("""
                    SELECT s.sale_id, s.invoice_no, s.completed_at, s.status, c.name AS customer_name, u.full_name AS cashier,
                           (SELECT SUM(quantity) FROM sale_line sl WHERE sl.sale_id = s.sale_id) AS items,
                           s.subtotal, s.discount_total, s.taxable_total, s.cgst_total, s.sgst_total, s.round_off,
                           s.grand_total,
                           (SELECT STRING_AGG(CONCAT(pay.mode, ' ', pay.amount), ', ')
                              FROM payment pay WHERE pay.sale_id = s.sale_id) AS payments
                    FROM sale s JOIN app_user u ON u.user_id = s.created_by
                    LEFT JOIN customer c ON c.customer_id = s.customer_id
                    WHERE """ + SOLD + " AND s.completed_at >= ? AND s.completed_at < ? ORDER BY s.sale_id", start, end));
            out.put("creditNotes", jdbc.queryForList("""
                    SELECT cn.cn_no, cn.created_at, s.invoice_no, cn.amount, cn.balance, cn.reason
                    FROM credit_note cn JOIN sale s ON s.sale_id = cn.sale_id
                    WHERE cn.created_at >= ? AND cn.created_at < ? ORDER BY cn.cn_id""", start, end));
        }
        return out;
    }

    /**
     * Profit report (owner). Profit = what we earned without tax (taxable value) minus what the goods cost us
     * (cost price saved on the bill line at the time of sale). Returned pieces are left out.
     */
    public Map<String, Object> profit(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        String kept = "(sl.quantity - sl.returned_qty)";
        String revenue = "SUM(sl.taxable_value * " + kept + " / sl.quantity)";
        String cost = "SUM(COALESCE(sl.unit_cost, v.cost_price) * " + kept + ")";
        String from_ = """
                FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                JOIN variant v ON v.variant_id = sl.variant_id JOIN product p ON p.product_id = v.product_id
                JOIN category c ON c.category_id = p.category_id
                WHERE""" + SOLD + " AND s.completed_at >= ? AND s.completed_at < ? AND sl.quantity > sl.returned_qty ";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        Map<String, Object> totals = jdbc.queryForMap("SELECT COALESCE(SUM" + kept + ", 0) AS qty, COALESCE(ROUND("
                + revenue + ", 2), 0) AS revenue, COALESCE(ROUND(" + cost + ", 2), 0) AS cost " + from_, start, end);
        addProfit(totals);
        out.put("totals", totals);
        List<Map<String, Object>> byCategory = jdbc.queryForList("SELECT c.name AS category, SUM" + kept + " AS qty, ROUND("
                + revenue + ", 2) AS revenue, ROUND(" + cost + ", 2) AS cost " + from_
                + " GROUP BY c.name ORDER BY revenue DESC", start, end);
        byCategory.forEach(ReportService::addProfit);
        out.put("byCategory", byCategory);
        List<Map<String, Object>> byProduct = jdbc.queryForList("SELECT p.product_id, p.name, c.name AS category, SUM"
                + kept + " AS qty, ROUND(" + revenue + ", 2) AS revenue, ROUND(" + cost + ", 2) AS cost " + from_
                + " GROUP BY p.product_id, p.name, c.name ORDER BY revenue DESC LIMIT 100", start, end);
        byProduct.forEach(ReportService::addProfit);
        out.put("byProduct", byProduct);
        return out;
    }

    private static void addProfit(Map<String, Object> row) {
        BigDecimal revenue = row.get("revenue") == null ? BigDecimal.ZERO : (BigDecimal) row.get("revenue");
        BigDecimal cost = row.get("cost") == null ? BigDecimal.ZERO : (BigDecimal) row.get("cost");
        BigDecimal profit = revenue.subtract(cost);
        row.put("profit", profit);
        row.put("margin_percent", revenue.signum() == 0 ? BigDecimal.ZERO
                : profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, java.math.RoundingMode.HALF_UP));
    }

    /** Stock value by category (at cost and at selling price) plus slow-moving stock (owner). */
    public Map<String, Object> stockValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byCategory", jdbc.queryForList("""
                SELECT c.name AS category, COUNT(DISTINCT p.product_id) AS products, SUM(v.stock_qty) AS pieces,
                       SUM(v.stock_qty * v.cost_price) AS cost_value, SUM(v.stock_qty * v.selling_price) AS sale_value
                FROM variant v JOIN product p ON p.product_id = v.product_id JOIN category c ON c.category_id = p.category_id
                WHERE p.is_active = TRUE GROUP BY c.name ORDER BY cost_value DESC"""));
        out.put("totals", jdbc.queryForMap("""
                SELECT COALESCE(SUM(v.stock_qty), 0) AS pieces, COALESCE(SUM(v.stock_qty * v.cost_price), 0) AS cost_value,
                       COALESCE(SUM(v.stock_qty * v.selling_price), 0) AS sale_value
                FROM variant v JOIN product p ON p.product_id = v.product_id WHERE p.is_active = TRUE"""));
        out.put("slowMoving", jdbc.queryForList("""
                SELECT p.product_id, p.name, v.variant_id, v.sku, v.size, v.colour, v.stock_qty,
                       v.stock_qty * v.cost_price AS cost_value,
                       (SELECT MAX(s.completed_at) FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                         WHERE sl.variant_id = v.variant_id AND""" + SOLD + """
                       ) AS last_sold
                FROM variant v JOIN product p ON p.product_id = v.product_id
                WHERE p.is_active = TRUE AND v.stock_qty > 0
                  AND NOT EXISTS (SELECT 1 FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                                   WHERE sl.variant_id = v.variant_id AND""" + SOLD + """
                                     AND s.completed_at >= ?)
                ORDER BY cost_value DESC LIMIT 100""", LocalDate.now().minusDays(60).atStartOfDay()));
        return out;
    }

    /** Sales by staff member (owner): who billed how much. */
    public List<Map<String, Object>> staffSales(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT u.full_name, u.username, COUNT(*) AS bills, SUM(s.grand_total) AS sales,
                       SUM(s.discount_total) AS discounts
                FROM sale s JOIN app_user u ON u.user_id = s.created_by
                WHERE""" + SOLD + """
                 AND s.completed_at >= ? AND s.completed_at < ?
                GROUP BY u.full_name, u.username ORDER BY sales DESC""", from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    /** Excel export of the sales report (REQ-REP-6). */
    @SuppressWarnings("unchecked")
    public byte[] salesXlsx(LocalDate from, LocalDate to) throws IOException {
        Map<String, Object> r = sales(from, to, true);
        Map<String, Object> t = (Map<String, Object>) r.get("totals");
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);

            Sheet sum = wb.createSheet("Summary");
            int row = 0;
            row = title(sum, st, row, settings.shop().name() + " - Sales Report");
            if (settings.isGstRegistered()) row = kv(sum, st, row, "GSTIN", settings.shop().gstin());
            row = kv(sum, st, row, "Period", from + " to " + to);
            row = kv(sum, st, row, "Generated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")));
            row++;
            Object[][] lines = {
                    {"Bills", t.get("bills")}, {"Gross sales (MRP-side, before discount)", t.get("gross")},
                    {"Discounts", t.get("discounts")}, {"Taxable value", t.get("taxable")},
                    {"CGST", t.get("cgst")}, {"SGST", t.get("sgst")}, {"Round-off", t.get("round_off")},
                    {"Net sales (bill totals)", t.get("net_sales")}, {"Average bill", t.get("avg_bill")},
                    {"Returns (credit notes issued)", t.get("returns_value")},
                    {"Net after returns", t.get("net_after_returns")}};
            for (Object[] l : lines) {
                row = kv(sum, st, row, (String) l[0], l[1]);
            }
            row++;
            row = table(sum, st, row, "GST by rate", (List<Map<String, Object>>) r.get("gstByRate"),
                    new String[]{"gst_rate", "qty", "taxable", "cgst", "sgst", "tax"},
                    new String[]{"GST %", "Pieces", "Taxable value", "CGST", "SGST", "Total GST"});
            row++;
            table(sum, st, row, "Payment modes", (List<Map<String, Object>>) r.get("payments"),
                    new String[]{"mode", "count", "amount"}, new String[]{"Mode", "Payments", "Amount"});
            sum.setColumnWidth(0, 40 * 256);
            for (int c = 1; c < 6; c++) sum.setColumnWidth(c, 16 * 256);

            sheet(wb, st, "Bills", (List<Map<String, Object>>) r.get("bills"),
                    new String[]{"invoice_no", "completed_at", "status", "customer_name", "cashier", "items", "subtotal",
                            "discount_total", "taxable_total", "cgst_total", "sgst_total", "round_off", "grand_total", "payments"},
                    new String[]{"Invoice", "Date & time", "Status", "Customer", "Cashier", "Pieces", "Gross", "Discount",
                            "Taxable", "CGST", "SGST", "Round-off", "Bill total", "Payments"});
            sheet(wb, st, "Daily", (List<Map<String, Object>>) r.get("daily"),
                    new String[]{"day", "bills", "gross", "discounts", "taxable", "gst", "net_sales"},
                    new String[]{"Date", "Bills", "Gross", "Discounts", "Taxable", "GST", "Net sales"});
            sheet(wb, st, "HSN summary", (List<Map<String, Object>>) r.get("hsn"),
                    new String[]{"hsn_code", "gst_rate", "qty", "taxable", "tax"},
                    new String[]{"HSN", "GST %", "Pieces", "Taxable value", "GST"});
            sheet(wb, st, "Credit notes", (List<Map<String, Object>>) r.get("creditNotes"),
                    new String[]{"cn_no", "created_at", "invoice_no", "amount", "balance", "reason"},
                    new String[]{"Credit note", "Date & time", "Against bill", "Amount", "Balance", "Reason"});

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ---------- small Excel helpers ----------

    private static final class Styles {
        final CellStyle header, money, title, date;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(big);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd-mm-yyyy hh:mm"));
        }
    }

    private int title(Sheet s, Styles st, int row, String text) {
        Cell c = s.createRow(row).createCell(0);
        c.setCellValue(text);
        c.setCellStyle(st.title);
        return row + 1;
    }

    private int kv(Sheet s, Styles st, int row, String key, Object value) {
        Row r = s.createRow(row);
        r.createCell(0).setCellValue(key);
        put(r.createCell(1), value, st);
        return row + 1;
    }

    private int table(Sheet s, Styles st, int row, String caption, List<Map<String, Object>> data, String[] cols, String[] heads) {
        Cell cap = s.createRow(row++).createCell(0);
        cap.setCellValue(caption);
        cap.setCellStyle(st.header);
        Row h = s.createRow(row++);
        for (int i = 0; i < heads.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(heads[i]);
            c.setCellStyle(st.header);
        }
        for (Map<String, Object> m : data) {
            Row r = s.createRow(row++);
            for (int i = 0; i < cols.length; i++) {
                put(r.createCell(i), m.get(cols[i]), st);
            }
        }
        return row;
    }

    private void sheet(Workbook wb, Styles st, String name, List<Map<String, Object>> data, String[] cols, String[] heads) {
        Sheet s = wb.createSheet(name);
        table(s, st, 0, name, data, cols, heads);
        for (int i = 0; i < cols.length; i++) {
            s.setColumnWidth(i, (i == 0 || heads[i].contains("Date") || heads[i].equals("Payments") ? 22 : 14) * 256);
        }
        s.createFreezePane(0, 2);
    }

    private void put(Cell c, Object v, Styles st) {
        if (v == null) {
            c.setBlank();
        } else if (v instanceof BigDecimal b) {
            c.setCellValue(b.doubleValue());
            c.setCellStyle(st.money);
        } else if (v instanceof Number n) {
            c.setCellValue(n.doubleValue());
        } else if (v instanceof LocalDateTime d) {
            c.setCellValue(d);
            c.setCellStyle(st.date);
        } else if (v instanceof java.sql.Timestamp ts) {
            c.setCellValue(ts.toLocalDateTime());
            c.setCellStyle(st.date);
        } else {
            c.setCellValue(String.valueOf(v));
        }
    }
}
