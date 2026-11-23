package com.vertex.csms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.vertex.csms.billing.BillingService;
import com.vertex.csms.billing.BillingService.CartLine;
import com.vertex.csms.billing.BillingService.CompleteRequest;
import com.vertex.csms.billing.BillingService.PaymentRequest;
import com.vertex.csms.billing.HeldBillService;
import com.vertex.csms.billing.PaymentMode;
import com.vertex.csms.billing.ReceiptService;
import com.vertex.csms.billing.VoidService;
import com.vertex.csms.common.ApiException;
import com.vertex.csms.product.ProductImportService;
import com.vertex.csms.returns.ReturnService;
import com.vertex.csms.security.Role;
import com.vertex.csms.security.UserPrincipal;

/**
 * Shared set-up for the integration tests: one Spring context, one PostgreSQL test database (clothing_shop_test,
 * wiped and rebuilt by Flyway once per run), the demo logins and small helpers.
 * Run with: ./mvnw test -Dit.db=true
 */
@EnabledIfSystemProperty(named = "it.db", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/clothing_shop_test",
        "spring.datasource.username=csms",
        "spring.datasource.password=csms_dev_pw",
        "spring.flyway.clean-disabled=false"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(CleanDatabase.class)
abstract class IntegrationTestBase {

    static {
        // Same as CsmsApplication.main(): the shop works in India time.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
    }

    static final UserPrincipal OWNER = new UserPrincipal(1L, "owner", "Renu Yadav", Role.OWNER, false);
    static final UserPrincipal STAFF = new UserPrincipal(2L, "staff", "Counter Staff", Role.SALES_STAFF, false);

    @Autowired BillingService billing;
    @Autowired HeldBillService held;
    @Autowired VoidService voids;
    @Autowired ReturnService returns;
    @Autowired ReceiptService receipts;
    @Autowired ProductImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.vertex.csms.common.SettingsService settings;
    @Autowired com.vertex.csms.customer.CreditService credit;
    @Autowired com.vertex.csms.report.ReportService reports;

    @BeforeEach
    void setUp() {
        // Keep the festive saree offer running whatever day the tests run.
        jdbc.update("UPDATE offer SET valid_from = NOW() - INTERVAL '1 day', valid_to = NOW() + INTERVAL '30 days'");
        jdbc.update("INSERT INTO customer (name, mobile, area, wa_opt_in, consent_at, consent_method, consent_by, credit_limit) "
                + "VALUES ('Team Tester (TEST)', '9000000001', 'Team Vertex', TRUE, NOW(), 'SIGNED_FORM', 1, 5000) "
                + "ON CONFLICT (mobile) DO NOTHING");
        login(STAFF);
        // Most tests check the GST rules, so the shop is GST-registered unless a test says otherwise.
        settings.setGstRegistered(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    // ---------- helpers ----------

    protected MockHttpSession httpLogin(String user, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    protected BigDecimal due(long customer) {
        return jdbc.queryForObject("SELECT credit_due FROM customer WHERE customer_id = ?", BigDecimal.class, customer);
    }

    protected static void login(UserPrincipal p) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }

    protected static CartLine line(long variantId, int qty) {
        return new CartLine(variantId, qty, null);
    }

    protected static PaymentRequest pay(PaymentMode mode, String amount) {
        return new PaymentRequest(mode, new BigDecimal(amount), null);
    }

    protected long customerId() {
        return jdbc.queryForObject("SELECT customer_id FROM customer WHERE mobile = '9000000001'", Long.class);
    }

    protected int stock(long variantId) {
        return jdbc.queryForObject("SELECT stock_qty FROM variant WHERE variant_id = ?", Integer.class, variantId);
    }

    protected int count(String fromWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromWhere, Integer.class);
    }

    protected void assertLedgerConsistent() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM variant v
                LEFT JOIN (SELECT variant_id, SUM(qty_change) AS total FROM stock_movement GROUP BY variant_id) m USING (variant_id)
                WHERE v.stock_qty <> COALESCE(m.total, 0)""", Integer.class)).as("stock ledger mismatches").isZero();
    }

    protected static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(code));
    }
}
