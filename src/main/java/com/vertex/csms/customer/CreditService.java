package com.vertex.csms.customer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;
import com.vertex.csms.common.Money;
import com.vertex.csms.security.CurrentUser;

/**
 * Customer credit (udhaar) ledger (REQ-CUS-5). customer.credit_due is the running balance; every change is also
 * written to customer_credit_txn (credit sale, repayment, reversal by void) so the balance can always be explained.
 */
@Service
public class CreditService {

    public static final List<String> MODES = List.of("CASH", "UPI", "CARD");

    private final CustomerRepository customers;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CreditService(CustomerRepository customers, JdbcTemplate jdbc, AuditService audit) {
        this.customers = customers;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Called inside the sale transaction, with the customer row already locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditSale(Customer c, BigDecimal amount, Long saleId, String invoiceNo) {
        c.setCreditDue(c.getCreditDue().add(amount));
        insert(c.getId(), "SALE", amount, saleId, null, "Credit sale " + invoiceNo, c.getCreditDue());
    }

    /** Void of a credit sale: the amount is taken back off the balance. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseSale(Customer c, BigDecimal amount, Long saleId, String invoiceNo) {
        c.setCreditDue(c.getCreditDue().subtract(amount).max(BigDecimal.ZERO));
        insert(c.getId(), "VOID", amount, saleId, null, "Bill " + invoiceNo + " voided", c.getCreditDue());
    }

    public record Repayment(BigDecimal amount, String mode, String note) {}

    @Transactional
    public Map<String, Object> repay(Long customerId, Repayment r) {
        Customer c = customers.lockById(customerId)
                .orElseThrow(() -> ApiException.notFound("INFO-CUS-001", "Customer not found."));
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().scale() > 2) {
            throw ApiException.badRequest("ERR-VAL-001", "Enter the amount received.", "amount");
        }
        BigDecimal amount = Money.round(r.amount());
        if (amount.compareTo(c.getCreditDue()) > 0) {
            throw ApiException.badRequest("ERR-VAL-001", c.getName() + " owes only Rs " + c.getCreditDue() + ".", "amount");
        }
        if (r.mode() == null || !MODES.contains(r.mode())) {
            throw ApiException.badRequest("ERR-VAL-001", "Choose how the money was paid: cash, UPI or card.", "mode");
        }
        String note = r.note() == null ? null : r.note().trim();
        if (note != null && note.length() > 150) note = note.substring(0, 150);
        c.setCreditDue(c.getCreditDue().subtract(amount));
        insert(c.getId(), "REPAYMENT", amount, null, r.mode(), note, c.getCreditDue());
        audit.log("CREDIT_REPAID", "customer", c.getId(), c.getName() + " paid Rs " + amount + " by " + r.mode());
        return Map.of("customerId", c.getId(), "creditDue", c.getCreditDue());
    }

    public List<Map<String, Object>> ledger(Long customerId) {
        return jdbc.queryForList("""
                SELECT t.txn_id, t.type, t.amount, t.sale_id, s.invoice_no, t.mode, t.note, t.balance_after,
                       t.created_at, u.full_name AS user_name
                FROM customer_credit_txn t JOIN app_user u ON u.user_id = t.created_by
                LEFT JOIN sale s ON s.sale_id = t.sale_id
                WHERE t.customer_id = ? ORDER BY t.txn_id DESC LIMIT 200""", customerId);
    }

    /** Everyone who owes money, oldest unpaid credit first (credit dues report). */
    public List<Map<String, Object>> dues() {
        return jdbc.queryForList("""
                SELECT c.customer_id, c.name, c.mobile, c.area, c.credit_due, c.credit_limit,
                       (SELECT MAX(t.created_at) FROM customer_credit_txn t WHERE t.customer_id = c.customer_id AND t.type = 'SALE') AS last_credit,
                       (SELECT MAX(t.created_at) FROM customer_credit_txn t WHERE t.customer_id = c.customer_id AND t.type = 'REPAYMENT') AS last_payment
                FROM customer c WHERE c.credit_due > 0 ORDER BY last_credit NULLS LAST""");
    }

    private void insert(Long customerId, String type, BigDecimal amount, Long saleId, String mode, String note, BigDecimal balance) {
        jdbc.update("""
                INSERT INTO customer_credit_txn (customer_id, type, amount, sale_id, mode, note, balance_after, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", customerId, type, amount, saleId, mode, note, balance, CurrentUser.id());
    }
}
