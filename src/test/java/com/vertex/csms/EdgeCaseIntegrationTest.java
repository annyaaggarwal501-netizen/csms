package com.vertex.csms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.vertex.csms.billing.BillingService.CartLine;
import com.vertex.csms.billing.BillingService.CompleteRequest;
import com.vertex.csms.billing.PaymentMode;
import com.vertex.csms.billing.VoidService;
import com.vertex.csms.customer.CreditService.Repayment;
import com.vertex.csms.returns.ReturnService;

/**
 * Edge cases for payments, credit notes, credit limits, repayments, customers, categories and stock:
 * the boundaries and wrong inputs a busy counter can hit. Every case checks that nothing is saved when it
 * must not be, and that the stock history still adds up.
 *
 * Variant 1 is "Floral Cotton Kurti S/Blue", Rs 1,050 (GST included, 5% slab).
 */
class EdgeCaseIntegrationTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired com.vertex.csms.security.DemoLogins demoLogins;

    private static final long KURTI = 1L;          // Rs 1,050
    private static final String BILL = "1050";

    @BeforeEach
    void enoughStock() throws Exception {
        // Many cases sell the same kurti: top it up through the normal stock adjustment (keeps the ledger right).
        if (stock(KURTI) < 30) {
            mvc.perform(post("/api/v1/stock/adjustments").session(httpLogin("owner", "Owner@123"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"variantId\":1,\"qtyChange\":50,\"reason\":\"Counting correction\"}"))
                    .andExpect(status().isOk());
        }
        login(STAFF);                                   // the HTTP call above clears the test's login
    }

    // ================= payments =================

    @Test
    void emptyCartIsRefused() {
        assertCode(() -> billing.complete(new CompleteRequest(null, List.of(), List.of(pay(PaymentMode.CASH, "10")), null)),
                "ERR-BIL-001");
    }

    @Test
    void paymentsMustMatchTheBillExactly() {
        int before = count("sale");
        assertCode(() -> sell(null, pay(PaymentMode.CASH, "1049.99")), "ERR-BIL-003");                 // 1 paisa short
        assertCode(() -> sell(null, pay(PaymentMode.CASH, "1100")), "ERR-BIL-003");                    // cash typed too high
        assertCode(() -> billing.complete(new CompleteRequest(null, List.of(line(KURTI, 1)), List.of(), null)), "ERR-BIL-003");
        assertThat(count("sale")).isEqualTo(before);
        sell(null, pay(PaymentMode.UPI, "600"), pay(PaymentMode.UPI, "450"));                            // two UPI rows are fine
        assertThat(count("sale")).isEqualTo(before + 1);
        assertLedgerConsistent();
    }

    @Test
    void onlyCashMayBeMoreThanTheBillAndNoNonCashOverpayIsSaved() {
        int before = count("sale");
        assertCode(() -> sell(null, pay(PaymentMode.CARD, "1051")), "ERR-BIL-003");
        assertCode(() -> sell(null, pay(PaymentMode.UPI, "800"), pay(PaymentMode.CARD, "300")), "ERR-BIL-003");
        assertThat(count("sale")).isEqualTo(before);
        sell(null, pay(PaymentMode.CARD, BILL));                                                        // exactly the bill
        sell(null, pay(PaymentMode.CARD, "1000"), pay(PaymentMode.CASH, "50"));
        assertThat(count("sale")).isEqualTo(before + 2);
    }

    @Test
    void zeroOrNegativePaymentsAndQuantitiesAreRejectedOverHttp() throws Exception {
        MockHttpSession staff = httpLogin("staff", "Staff@123");
        for (String body : List.of(
                "{\"lines\":[{\"variantId\":1,\"quantity\":1}],\"payments\":[{\"mode\":\"CASH\",\"amount\":0}]}",
                "{\"lines\":[{\"variantId\":1,\"quantity\":1}],\"payments\":[{\"mode\":\"CASH\",\"amount\":-1050}]}",
                "{\"lines\":[{\"variantId\":1,\"quantity\":0}],\"payments\":[{\"mode\":\"CASH\",\"amount\":1050}]}",
                "{\"lines\":[{\"variantId\":1,\"quantity\":1,\"manualDiscount\":-5}],\"payments\":[{\"mode\":\"CASH\",\"amount\":1050}]}")) {
            mvc.perform(post("/api/v1/sales").session(staff).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void pointsAndCreditWithoutCustomerAreRefused() {
        assertCode(() -> sell(null, pay(PaymentMode.POINTS, BILL)), "ERR-VAL-001");
        assertCode(() -> sell(null, pay(PaymentMode.CREDIT, BILL)), "ERR-VAL-001");
    }

    @Test
    void sellingMoreThanInStockSavesNothing() {
        int have = stock(KURTI), before = count("sale");
        BigDecimal price = new BigDecimal(BILL).multiply(BigDecimal.valueOf(have + 1));
        assertCode(() -> billing.complete(new CompleteRequest(null, List.of(line(KURTI, have + 1)),
                List.of(new com.vertex.csms.billing.BillingService.PaymentRequest(PaymentMode.CASH, price, null)), null)), "ERR-INV-001");
        assertThat(stock(KURTI)).isEqualTo(have);
        assertThat(count("sale")).isEqualTo(before);
        assertLedgerConsistent();
    }

    // ================= credit notes =================

    @Test
    void creditNoteRules() {
        String cn = creditNote();                                                                         // worth Rs 1,050
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, BILL, "CN-0000-99999")), "ERR-VAL-001");  // unknown
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, BILL, " ")), "ERR-VAL-001");              // no number
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, "1050.01", cn), pay(PaymentMode.CASH, "0.01")),
                "ERR-BIL-003");

        sell(null, payRef(PaymentMode.CREDIT_NOTE, "1000", cn), pay(PaymentMode.CASH, "50"));             // part used
        assertThat(cnBalance(cn)).isEqualByComparingTo("50");
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, "100", cn), pay(PaymentMode.CASH, "950")),
                "ERR-VAL-001");                                                                          // only 50 left
        var last = sell(null, payRef(PaymentMode.CREDIT_NOTE, "50", cn), pay(PaymentMode.CASH, "1000"));  // the rest
        assertThat(cnBalance(cn)).isEqualByComparingTo("0");
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, "1", cn), pay(PaymentMode.CASH, "1049")), "ERR-VAL-001");

        login(OWNER);                                                                                    // void gives it back
        voids.voidSale(last.saleId(), new VoidService.VoidRequest("Wrong bill", null));
        assertThat(cnBalance(cn)).isEqualByComparingTo("50");
        login(STAFF);

        jdbc.update("UPDATE credit_note SET valid_to = CURRENT_DATE - 1 WHERE cn_no = ?", cn);           // expired
        assertCode(() -> sell(null, payRef(PaymentMode.CREDIT_NOTE, "50", cn), pay(PaymentMode.CASH, "1000")), "ERR-VAL-001");
        assertLedgerConsistent();
    }

    // ================= credit limit on the bill =================

    @Test
    void creditUpToExactlyTheLimitIsAllowed() {
        long c = newCustomer("9811100001", "2100");
        sell(c, pay(PaymentMode.CREDIT, BILL));
        sell(c, pay(PaymentMode.CREDIT, BILL));                                                          // due = limit exactly
        assertThat(due(c)).isEqualByComparingTo("2100");
        assertCode(() -> sell(c, pay(PaymentMode.CREDIT, "1"), pay(PaymentMode.CASH, "1049")), "ERR-CUS-003");  // Rs 1 over
        sell(c, pay(PaymentMode.CASH, BILL));                                                            // cash is always fine
    }

    @Test
    void noLimitMeansNoCreditForStaffButTheOwnerMayGiveIt() {
        long c = newCustomer("9811100002", "0");
        assertCode(() -> sell(c, pay(PaymentMode.CREDIT, BILL)), "ERR-CUS-003");
        assertThat(due(c)).isEqualByComparingTo("0");
        login(OWNER);
        var s = sell(c, pay(PaymentMode.CREDIT, BILL));
        assertThat(s.warnings()).anyMatch(w -> w.contains("over the credit limit"));
        assertThat(due(c)).isEqualByComparingTo(BILL);
        // already over the limit: staff cannot add even Rs 1 more
        login(STAFF);
        assertCode(() -> sell(c, pay(PaymentMode.CREDIT, "1"), pay(PaymentMode.CASH, "1049")), "ERR-CUS-003");
        // a void takes the credit back off
        login(OWNER);
        voids.voidSale(s.saleId(), new VoidService.VoidRequest("Customer cancelled", null));
        assertThat(due(c)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT balance_after FROM customer_credit_txn WHERE customer_id = ? "
                + "ORDER BY txn_id DESC LIMIT 1", BigDecimal.class, c)).isEqualByComparingTo("0");
    }

    @Test
    void ownerPinRules() {
        long c = newCustomer("9811100003", "500");
        assertCode(() -> sell(c, "", pay(PaymentMode.CREDIT, BILL)), "ERR-CUS-003");                    // empty PIN
        assertCode(() -> sell(c, "12", pay(PaymentMode.CREDIT, BILL)), "ERR-CUS-003");                  // too short
        assertCode(() -> sell(c, "abcd", pay(PaymentMode.CREDIT, BILL)), "ERR-CUS-003");                // not digits
        sell(c, "2580", pay(PaymentMode.CREDIT, BILL));
        assertThat(count("audit_log WHERE action = 'CREDIT_LIMIT_OVERRIDE' AND details LIKE '%approved with Owner PIN%'"))
                .isGreaterThanOrEqualTo(1);
    }

    // ================= repayments and limits =================

    @Test
    void repaymentRules() {
        long c = newCustomer("9811100004", "5000");
        sell(c, pay(PaymentMode.CREDIT, BILL));
        for (String bad : List.of("0", "-10", "1050.01", "10.005")) {
            assertCode(() -> credit.repay(c, new Repayment(new BigDecimal(bad), "CASH", null)), "ERR-VAL-001");
        }
        assertCode(() -> credit.repay(c, new Repayment(null, "CASH", null)), "ERR-VAL-001");
        assertCode(() -> credit.repay(c, new Repayment(BigDecimal.TEN, "CHEQUE", null)), "ERR-VAL-001");
        assertCode(() -> credit.repay(c, new Repayment(BigDecimal.TEN, null, null)), "ERR-VAL-001");
        assertCode(() -> credit.repay(999999L, new Repayment(BigDecimal.TEN, "CASH", null)), "INFO-CUS-001");
        assertThat(due(c)).isEqualByComparingTo(BILL);                                                   // nothing changed

        credit.repay(c, new Repayment(new BigDecimal("50.50"), "UPI", "x".repeat(400)));               // long note is cut
        credit.repay(c, new Repayment(new BigDecimal("999.50"), "CARD", null));                         // pays it all
        assertThat(due(c)).isEqualByComparingTo("0");
        assertCode(() -> credit.repay(c, new Repayment(BigDecimal.ONE, "CASH", null)), "ERR-VAL-001");  // owes nothing now
        assertThat(jdbc.queryForObject("SELECT MAX(LENGTH(note)) FROM customer_credit_txn WHERE customer_id = ?",
                Integer.class, c)).isLessThanOrEqualTo(150);
    }

    @Test
    void creditLimitValues() {
        long c = newCustomer("9811100005", "0");
        login(OWNER);
        for (String bad : List.of("-1", "10000000.01", "100.123")) {
            assertCode(() -> credit.setLimit(c, new BigDecimal(bad)), "ERR-VAL-001");
        }
        assertCode(() -> credit.setLimit(c, null), "ERR-VAL-001");
        credit.setLimit(c, new BigDecimal("10000000"));                                                 // 1 crore is the top
        credit.setLimit(c, BigDecimal.ZERO);                                                            // back to no credit
        login(STAFF);
        assertCode(() -> credit.setLimit(c, BigDecimal.TEN), "ERR-ACC-003");
    }

    // ================= customers =================

    @Test
    void mobileNumbersAreCleanedAndChecked() throws Exception {
        MockHttpSession staff = httpLogin("staff", "Staff@123");
        mvc.perform(post("/api/v1/customers").session(staff).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rekha Sharma\",\"mobile\":\"+91 98111-00006\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mobile").value("9811100006"));
        mvc.perform(post("/api/v1/customers").session(staff).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rekha Sharma\",\"mobile\":\"09811100006\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR-CUS-002"));   // same number
        for (String bad : List.of("5811100007", "98111", "98111000071", "abcdefghij")) {
            mvc.perform(post("/api/v1/customers").session(staff).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test Person\",\"mobile\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR-CUS-001"));
        }
    }

    // ================= categories =================

    @Test
    void categoryNamesAreCheckedAndTidied() throws Exception {
        MockHttpSession owner = httpLogin("owner", "Owner@123");
        for (String bad : List.of("A", "   ", "x".repeat(51))) {
            mvc.perform(post("/api/v1/categories").session(owner).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + bad + "\",\"parentId\":null}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));
        }
        mvc.perform(post("/api/v1/categories").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Kids   Party  Wear \",\"parentId\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kids Party Wear"));
        mvc.perform(post("/api/v1/categories").session(owner).contentType(MediaType.APPLICATION_JSON)    // same name, other place
                        .content("{\"name\":\"Kids Party Wear\",\"parentId\":1}"))
                .andExpect(status().isOk());
    }

    // ================= search =================

    @Test
    void searchFindsByNameSkuColourAndBarcodeAndTreatsWildcardsAsText() throws Exception {
        MockHttpSession staff = httpLogin("staff", "Staff@123");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/variants/search")
                        .param("q", "kurti").session(staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].productName").exists())
                .andExpect(jsonPath("$[0].costPrice").doesNotExist());                                  // staff: no cost
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/variants/search")
                        .param("q", "floral cotton kurti s blue").session(staff))                      // name + size + colour
                .andExpect(jsonPath("$[0].sku").value("KUR-0001-S-BLU"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/variants/search")
                        .param("q", "2000000000015").session(staff))                                   // exact barcode
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(1));
        for (String wild : List.of("%", "_", "%%", "\\")) {                                           // not "match everything"
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/variants/search")
                            .param("q", wild).session(staff))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
        login(STAFF);
    }

    // ================= login page =================

    @Test
    void loginPageShowsOnlyDemoLoginsThatStillWork() throws Exception {
        demoLogins.refresh();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/login-info"))
                .andExpect(jsonPath("$.demoLogins.length()").value(2));
        String staffHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'staff'", String.class);
        try {
            MockHttpSession staff = httpLogin("staff", "Staff@123");
            mvc.perform(post("/api/v1/auth/change-password").session(staff).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"Staff@123\",\"newPassword\":\"Counter2026\"}"))
                    .andExpect(status().isOk());
            long until = System.currentTimeMillis() + 10_000;                 // re-checked in the background
            while (demoLogins.working().size() != 1 && System.currentTimeMillis() < until) Thread.sleep(100);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/public/login-info"))
                    .andExpect(jsonPath("$.demoLogins.length()").value(1))
                    .andExpect(jsonPath("$.demoLogins[0].username").value("owner"));
        } finally {
            jdbc.update("UPDATE app_user SET password_hash = ? WHERE username = 'staff'", staffHash);
            demoLogins.refresh();
            login(STAFF);
        }
    }

    // ================= helpers =================

    private com.vertex.csms.billing.BillingService.CompleteResult sell(Long customer,
            com.vertex.csms.billing.BillingService.PaymentRequest... pays) {
        return sell(customer, null, pays);
    }

    private com.vertex.csms.billing.BillingService.CompleteResult sell(Long customer, String ownerPin,
            com.vertex.csms.billing.BillingService.PaymentRequest... pays) {
        return billing.complete(new CompleteRequest(customer, List.of(new CartLine(KURTI, 1, null)), List.of(pays), ownerPin));
    }

    private static com.vertex.csms.billing.BillingService.PaymentRequest payRef(PaymentMode mode, String amount, String ref) {
        return new com.vertex.csms.billing.BillingService.PaymentRequest(mode, new BigDecimal(amount), ref);
    }

    /** A credit note worth Rs 1,050: sell the kurti, then return it. */
    private String creditNote() {
        var s = sell(null, pay(PaymentMode.CASH, BILL));
        long lineId = receipts.bySaleId(s.saleId(), false).lines().get(0).lineId();
        return returns.process(new ReturnService.ReturnRequest(s.invoiceNo(),
                List.of(new ReturnService.ReturnLine(lineId, 1, false)), "Size issue", null)).cnNo();
    }

    private BigDecimal cnBalance(String cn) {
        return jdbc.queryForObject("SELECT balance FROM credit_note WHERE cn_no = ?", BigDecimal.class, cn);
    }

    private long newCustomer(String mobile, String limit) {
        jdbc.update("DELETE FROM customer_credit_txn WHERE customer_id IN (SELECT customer_id FROM customer WHERE mobile = ?)", mobile);
        jdbc.update("UPDATE sale SET customer_id = NULL WHERE customer_id IN (SELECT customer_id FROM customer WHERE mobile = ?)", mobile);
        jdbc.update("DELETE FROM customer WHERE mobile = ?", mobile);
        return jdbc.queryForObject("INSERT INTO customer (name, mobile, area, credit_limit) VALUES ('Edge Case', ?, 'Test', ?) "
                + "RETURNING customer_id", Long.class, mobile, new BigDecimal(limit));
    }

    @SuppressWarnings("unused")
    private static void assertFails(Runnable r) {
        assertThatThrownBy(r::run);
    }
}
