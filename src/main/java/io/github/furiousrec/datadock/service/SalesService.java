package io.github.furiousrec.datadock.service;

import io.github.furiousrec.datadock.domain.Sale;
import io.github.furiousrec.datadock.domain.SalesCsv;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesService {
    private static final Logger log = LoggerFactory.getLogger(SalesService.class);
    private final JdbcTemplate database;

    public SalesService(JdbcTemplate database) {
        this.database = database;
    }

    public record ImportResult(UUID id, int rows, String sha256) {}
    public record SaleView(long id, UUID importId, LocalDate soldAt, String sku,
            String category, int quantity, BigDecimal unitPrice) {}
    public record Summary(long rows, long units, BigDecimal revenue, String currency) {}
    public record CategorySummary(String category, long units, BigDecimal revenue) {}

    @Transactional
    public ImportResult importCsv(byte[] bytes) {
        List<Sale> sales = SalesCsv.parse(bytes);
        String hash = hash(bytes);
        UUID importId = UUID.randomUUID();
        // The UNIQUE constraint also handles simultaneous identical uploads.
        database.update("INSERT INTO import_batch(id, sha256, row_count) VALUES (?, ?, ?)",
                importId, hash, sales.size());
        database.batchUpdate("""
                INSERT INTO sale(import_id, sold_at, sku, category, quantity, unit_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sales, 500, (statement, sale) -> {
            statement.setObject(1, importId);
            statement.setDate(2, Date.valueOf(sale.soldAt()));
            statement.setString(3, sale.sku());
            statement.setString(4, sale.category());
            statement.setInt(5, sale.quantity());
            statement.setBigDecimal(6, sale.unitPrice());
        });
        log.info("Persisted import id={} rows={} (transaction pending commit)", importId, sales.size());
        return new ImportResult(importId, sales.size(), hash);
    }

    @Transactional(readOnly = true)
    public List<SaleView> sales(LocalDate from, LocalDate to, int page, int size) {
        checkRange(from, to);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be 0–1000000 and size must be 1–100.");
        }
        return database.query("""
                SELECT id, import_id, sold_at, sku, category, quantity, unit_price
                FROM sale WHERE sold_at BETWEEN ? AND ?
                ORDER BY sold_at, id LIMIT ? OFFSET ?
                """, (row, index) -> new SaleView(row.getLong("id"),
                row.getObject("import_id", UUID.class), row.getDate("sold_at").toLocalDate(),
                row.getString("sku"), row.getString("category"), row.getInt("quantity"),
                row.getBigDecimal("unit_price")), Date.valueOf(from), Date.valueOf(to),
                size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        checkRange(from, to);
        return database.queryForObject("""
                SELECT COUNT(*) AS rows, COALESCE(SUM(quantity), 0) AS units,
                  COALESCE(SUM(quantity * unit_price), 0) AS revenue
                FROM sale WHERE sold_at BETWEEN ? AND ?
                """, (row, index) -> new Summary(row.getLong("rows"), row.getLong("units"),
                row.getBigDecimal("revenue"), "EUR"), Date.valueOf(from), Date.valueOf(to));
    }

    @Transactional(readOnly = true)
    public List<CategorySummary> categories(LocalDate from, LocalDate to) {
        checkRange(from, to);
        return database.query("""
                SELECT category, SUM(quantity) AS units, SUM(quantity * unit_price) AS revenue
                FROM sale WHERE sold_at BETWEEN ? AND ?
                GROUP BY category ORDER BY revenue DESC, category
                """, (row, index) -> new CategorySummary(row.getString("category"),
                row.getLong("units"), row.getBigDecimal("revenue")), Date.valueOf(from), Date.valueOf(to));
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to.");
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
