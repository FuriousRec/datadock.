package io.github.furiousrec.datadock;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.api-key=integration-test-key-at-least-32-characters")
class ApiIT {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static {
        POSTGRES.start();
    }
    static final String KEY = "integration-test-key-at-least-32-characters";
    static final String CSV = "sold_at,sku,category,quantity,unit_price\n"
            + "2026-09-01,A,Accessories,2,49.90\n"
            + "2026-09-02,B,Displays,1,199.00\n";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate database;
    final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        database.execute("TRUNCATE sale, import_batch RESTART IDENTITY CASCADE");
    }

    @Test
    void importsAndCalculatesRevenue() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
        assertEquals(new BigDecimal("298.80"),
                database.queryForObject("SELECT SUM(quantity * unit_price) FROM sale", BigDecimal.class));
        var response = get("/api/v1/analytics/summary?from=2026-09-01&to=2026-09-01", true);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("99.80"), response.body());
        assertEquals(200, get("/api/v1/sales?page=0&size=1", true).statusCode());
        assertEquals(200, get("/api/v1/analytics/categories", true).statusCode());
    }

    @Test
    void preservesIsoDatesAcrossImportReadAndAnalytics() throws Exception {
        var dates = java.util.List.of("0001-01-01", "1500-03-01", "1582-10-04",
                "1582-10-05", "1582-10-10", "1582-10-14", "1582-10-15", "9999-12-31");
        StringBuilder csv = new StringBuilder("sold_at,sku,category,quantity,unit_price\n");
        for (String date : dates) {
            csv.append(date).append(",HISTORICAL,History,1,2.50\n");
        }
        assertEquals(201, upload(csv.toString()).statusCode());
        // Read as text to check stored values independently of the JDBC date mapper.
        assertEquals(dates, database.queryForList(
                "SELECT sold_at::text FROM sale ORDER BY sold_at", String.class));

        for (String date : dates) {
            var sales = get("/api/v1/sales?from=" + date + "&to=" + date, true);
            assertEquals(200, sales.statusCode());
            assertTrue(sales.body().contains("\"soldAt\":\"" + date + "\""),
                    sales.body());
        }

        String range = "?from=1582-10-10&to=1582-10-14";
        var daily = get("/api/v1/analytics/daily" + range, true);
        assertEquals(200, daily.statusCode());
        assertEquals("[{\"date\":\"1582-10-10\",\"rows\":1,\"units\":1,\"revenue\":2.50},"
                + "{\"date\":\"1582-10-14\",\"rows\":1,\"units\":1,\"revenue\":2.50}]",
                daily.body());
        var summary = get("/api/v1/analytics/summary" + range, true);
        assertEquals(200, summary.statusCode());
        assertEquals("{\"rows\":2,\"units\":2,\"revenue\":5.00,\"currency\":\"EUR\"}",
                summary.body());
        var products = get("/api/v1/analytics/products" + range, true);
        assertEquals(200, products.statusCode());
        assertEquals("[{\"sku\":\"HISTORICAL\",\"rows\":2,\"units\":2,\"revenue\":5.00}]",
                products.body());
        var categories = get("/api/v1/analytics/categories" + range, true);
        assertEquals(200, categories.statusCode());
        assertEquals("[{\"category\":\"History\",\"units\":2,\"revenue\":5.00}]",
                categories.body());
    }

    @Test
    void paginatesExistingIsoDatesWithoutCalendarCutoverShifts() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        // Seed using SQL date literals so a broken import cannot hide read/query bugs.
        database.execute("UPDATE sale SET sold_at = DATE '1582-10-10' WHERE sku = 'A'");
        database.execute("UPDATE sale SET sold_at = DATE '1582-10-11' WHERE sku = 'B'");
        long firstId = database.queryForObject("SELECT id FROM sale WHERE sku = 'A'", Long.class);

        var first = get("/api/v1/sales?size=1", true);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"soldAt\":\"1582-10-10\""), first.body());
        var next = get("/api/v1/sales?size=1&afterSoldAt=1582-10-10&afterId=" + firstId, true);
        assertEquals(200, next.statusCode());
        assertTrue(next.body().contains("\"sku\":\"B\""), next.body());
        assertTrue(next.body().contains("\"soldAt\":\"1582-10-11\""), next.body());
        assertFalse(next.body().contains("\"sku\":\"A\""), next.body());
    }

    @Test
    void resolvesSaleImportIdsToProvenanceMetadata() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        var saleResponse = get("/api/v1/sales?size=1", true);
        assertEquals(200, saleResponse.statusCode());

        var importId = database.queryForObject(
                "SELECT id FROM import_batch", java.util.UUID.class);
        assertTrue(saleResponse.body().contains(
                "\"importId\":\"" + importId + "\""), saleResponse.body());

        var provenance = get("/api/v1/imports/" + importId, true);
        assertEquals(200, provenance.statusCode());
        assertTrue(provenance.body().contains("\"id\":\"" + importId + "\""),
                provenance.body());
        assertTrue(provenance.body().contains("\"rows\":2"), provenance.body());
        assertTrue(provenance.body().matches(
                ".*\\\"sha256\\\":\\\"[0-9a-f]{64}\\\".*"),
                provenance.body());
        assertTrue(provenance.body().contains("\"createdAt\":"), provenance.body());

        var missing = get(
                "/api/v1/imports/00000000-0000-0000-0000-000000000000", true);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("was not found"), missing.body());
        assertEquals(401, get("/api/v1/imports/" + importId, false).statusCode());
    }

    @Test
    void listsRecentImportsWithStableCursorPagination() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        String secondCsv = "sold_at,sku,category,quantity,unit_price\n"
                + "2026-10-01,C,Accessories,1,5.00\n";
        assertEquals(201, upload(secondCsv).statusCode());

        var olderId = database.queryForObject(
                "SELECT id FROM import_batch WHERE row_count = 2",
                java.util.UUID.class);
        var newerId = database.queryForObject(
                "SELECT id FROM import_batch WHERE row_count = 1",
                java.util.UUID.class);
        database.update("""
                UPDATE import_batch SET created_at = '2026-10-01T00:00:00Z'
                WHERE id = ?
                """, olderId);
        database.update("""
                UPDATE import_batch SET created_at = '2026-10-02T00:00:00Z'
                WHERE id = ?
                """, newerId);

        var firstPage = get("/api/v1/imports?size=1", true);
        assertEquals(200, firstPage.statusCode());
        assertTrue(firstPage.body().contains("\"id\":\"" + newerId + "\""),
                firstPage.body());
        assertFalse(firstPage.body().contains("\"id\":\"" + olderId + "\""),
                firstPage.body());

        var nextPage = get("/api/v1/imports?size=1"
                + "&beforeCreatedAt=2026-10-02T00:00:00Z"
                + "&beforeId=" + newerId, true);
        assertEquals(200, nextPage.statusCode());
        assertTrue(nextPage.body().contains("\"id\":\"" + olderId + "\""),
                nextPage.body());
        assertFalse(nextPage.body().contains("\"id\":\"" + newerId + "\""),
                nextPage.body());

        assertEquals(400, get(
                "/api/v1/imports?beforeId=" + newerId, true).statusCode());
        assertEquals(400, get(
                "/api/v1/imports?beforeCreatedAt=2026-10-02T00:00:00Z",
                true).statusCode());
        assertEquals(400, get("/api/v1/imports?size=0", true).statusCode());
        assertEquals(400, get("/api/v1/imports?size=101", true).statusCode());
        assertEquals(401, get("/api/v1/imports", false).statusCode());
    }

    @Test
    void returnsDailyAnalyticsInChronologicalOrder() throws Exception {
        assertEquals(201, upload(CSV).statusCode());

        var response = get(
                "/api/v1/analytics/daily?from=2026-09-01&to=2026-09-30",
                true);
        assertEquals(200, response.statusCode());
        assertEquals(
                "[{\"date\":\"2026-09-01\",\"rows\":1,\"units\":2,"
                        + "\"revenue\":99.80},"
                        + "{\"date\":\"2026-09-02\",\"rows\":1,\"units\":1,"
                        + "\"revenue\":199.00}]",
                response.body());

        var accessories = get(
                "/api/v1/analytics/daily?from=2026-09-01&to=2026-09-30"
                        + "&category=Accessories",
                true);
        assertEquals(200, accessories.statusCode());
        assertEquals(
                "[{\"date\":\"2026-09-01\",\"rows\":1,\"units\":2,"
                        + "\"revenue\":99.80}]",
                accessories.body());

        var missingCategory = get(
                "/api/v1/analytics/daily?category=Missing",
                true);
        assertEquals(200, missingCategory.statusCode());
        assertEquals("[]", missingCategory.body());

        var empty = get(
                "/api/v1/analytics/daily?from=2026-10-01&to=2026-10-31",
                true);
        assertEquals(200, empty.statusCode());
        assertEquals("[]", empty.body());

        assertEquals(400, get(
                "/api/v1/analytics/daily?from=2026-10-02&to=2026-10-01",
                true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/daily?category=%20", true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/daily?category=" + "x".repeat(81),
                true).statusCode());
        assertEquals(401, get("/api/v1/analytics/daily", false).statusCode());
    }

    @Test
    void ranksProductsByExactRevenueWithDeterministicTies() throws Exception {
        String productCsv = "sold_at,sku,category,quantity,unit_price\n"
                + "2026-09-01,A,Accessories,2,10.00\n"
                + "2026-09-02,A,Accessories,1,5.00\n"
                + "2026-09-01,C,Displays,2,15.00\n"
                + "2026-09-01,B,Displays,1,30.00\n";
        assertEquals(201, upload(productCsv).statusCode());

        var all = get(
                "/api/v1/analytics/products?from=2026-09-01&to=2026-09-30",
                true);
        assertEquals(200, all.statusCode());
        assertEquals(
                "[{\"sku\":\"B\",\"rows\":1,\"units\":1,\"revenue\":30.00},"
                        + "{\"sku\":\"C\",\"rows\":1,\"units\":2,\"revenue\":30.00},"
                        + "{\"sku\":\"A\",\"rows\":2,\"units\":3,\"revenue\":25.00}]",
                all.body());

        var limited = get("/api/v1/analytics/products?limit=2", true);
        assertEquals(200, limited.statusCode());
        assertFalse(limited.body().contains("\"sku\":\"A\""), limited.body());
        assertTrue(limited.body().contains("\"sku\":\"B\""), limited.body());
        assertTrue(limited.body().contains("\"sku\":\"C\""), limited.body());

        var filtered = get(
                "/api/v1/analytics/products?from=2026-09-02&to=2026-09-02",
                true);
        assertEquals(200, filtered.statusCode());
        assertEquals(
                "[{\"sku\":\"A\",\"rows\":1,\"units\":1,\"revenue\":5.00}]",
                filtered.body());

        var displays = get(
                "/api/v1/analytics/products?category=Displays",
                true);
        assertEquals(200, displays.statusCode());
        assertEquals(
                "[{\"sku\":\"B\",\"rows\":1,\"units\":1,\"revenue\":30.00},"
                        + "{\"sku\":\"C\",\"rows\":1,\"units\":2,\"revenue\":30.00}]",
                displays.body());

        var accessories = get(
                "/api/v1/analytics/products?category=Accessories",
                true);
        assertEquals(200, accessories.statusCode());
        assertEquals(
                "[{\"sku\":\"A\",\"rows\":2,\"units\":3,\"revenue\":25.00}]",
                accessories.body());

        var missingCategory = get(
                "/api/v1/analytics/products?category=Missing",
                true);
        assertEquals(200, missingCategory.statusCode());
        assertEquals("[]", missingCategory.body());

        var empty = get(
                "/api/v1/analytics/products?from=2026-10-01&to=2026-10-31",
                true);
        assertEquals(200, empty.statusCode());
        assertEquals("[]", empty.body());

        assertEquals(400, get(
                "/api/v1/analytics/products?from=2026-09-02&to=2026-09-01",
                true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/products?limit=0", true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/products?limit=101", true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/products?category=%20", true).statusCode());
        assertEquals(400, get(
                "/api/v1/analytics/products?category=" + "x".repeat(81),
                true).statusCode());
        assertEquals(401, get(
                "/api/v1/analytics/products", false).statusCode());
    }

    @Test
    void rejectsInvalidRowsWithoutPartialWrites() throws Exception {
        assertEquals(400, upload(CSV + "2026-09-02,C,Accessories,-1,1\n").statusCode());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class));
    }

    @Test
    void databaseFailureAfterFirstBatchRollsBackAndAllowsExactRetry() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        var originalImports = database.queryForList("SELECT * FROM import_batch ORDER BY id");
        var originalSales = database.queryForList("SELECT * FROM sale ORDER BY id");

        // All rows pass CSV validation. Row 501 fails only in PostgreSQL, after
        // the service has executed its first 500-row JDBC batch.
        StringBuilder csv = new StringBuilder("sold_at,sku,category,quantity,unit_price\n");
        for (int index = 1; index <= 501; index++) {
            String sku = index == 501 ? "DATABASE_REJECT" : "ROW_" + index;
            csv.append("2026-11-01,").append(sku).append(",Rollback,1,2.00\n");
        }
        String failedCsv = csv.toString();
        database.execute("""
                ALTER TABLE sale ADD CONSTRAINT test_reject_last_row
                CHECK (sku <> 'DATABASE_REJECT')
                """);
        try {
            var failure = upload(failedCsv);
            assertEquals(500, failure.statusCode(), failure.body());
            assertFalse(failure.body().contains("test_reject_last_row"), failure.body());
            assertFalse(failure.body().contains("DATABASE_REJECT"), failure.body());
            assertEquals(originalImports,
                    database.queryForList("SELECT * FROM import_batch ORDER BY id"));
            assertEquals(originalSales,
                    database.queryForList("SELECT * FROM sale ORDER BY id"));
        } finally {
            // This constraint exists only in the disposable test database.
            database.execute("ALTER TABLE sale DROP CONSTRAINT test_reject_last_row");
        }

        // A failed transaction must not retain the upload hash and poison retries.
        var retried = upload(failedCsv);
        assertEquals(201, retried.statusCode(), retried.body());
        assertEquals(501, database.queryForObject(
                "SELECT COUNT(*) FROM sale WHERE category = 'Rollback'", Integer.class));
        assertEquals(503, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class));
        assertEquals(409, upload(failedCsv).statusCode());
        assertEquals(503, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
    }

    @Test
    void duplicateUploadsDoNotDoubleCount() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        assertEquals(409, upload(CSV).statusCode());
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
    }

    @Test
    void simultaneousDuplicatesCommitOnlyOnce() throws Exception {
        var first = CompletableFuture.supplyAsync(() -> uploadUnchecked(CSV));
        var second = CompletableFuture.supplyAsync(() -> uploadUnchecked(CSV));
        var statuses = java.util.stream.Stream.of(first.get(), second.get()).sorted().toList();
        assertEquals(java.util.List.of(201, 409), statuses);
        assertEquals(2, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
    }


    @Test
    void filtersSalesByExactCategory() throws Exception {
        assertEquals(201, upload(CSV).statusCode());

        var accessories = get("/api/v1/sales?category=Accessories", true);
        assertEquals(200, accessories.statusCode());
        assertTrue(accessories.body().contains("\"category\":\"Accessories\""),
                accessories.body());
        assertFalse(accessories.body().contains("\"category\":\"Displays\""),
                accessories.body());

        var missing = get("/api/v1/sales?category=Missing", true);
        assertEquals(200, missing.statusCode());
        assertEquals("[]", missing.body());
    }

    @Test
    void filtersSalesByImportWithoutMixingOverlappingUploads() throws Exception {
        var importId = uploadOverlappingBatches();
        var otherImportId = database.queryForObject(
                "SELECT import_id FROM sale WHERE sku = 'C'", java.util.UUID.class);

        var all = get("/api/v1/sales?importId=" + importId, true);
        assertEquals(200, all.statusCode());
        assertTrue(all.body().contains("\"sku\":\"A\""), all.body());
        assertTrue(all.body().contains("\"sku\":\"B\""), all.body());
        assertFalse(all.body().contains("\"sku\":\"C\""), all.body());
        assertFalse(all.body().contains(otherImportId.toString()), all.body());

        var filtered = get("/api/v1/sales?importId=" + importId
                + "&category=Accessories&from=2026-09-01&to=2026-09-01", true);
        assertEquals(200, filtered.statusCode());
        assertTrue(filtered.body().contains("\"sku\":\"A\""), filtered.body());
        assertFalse(filtered.body().contains("\"sku\":\"B\""), filtered.body());
        assertFalse(filtered.body().contains(otherImportId.toString()), filtered.body());

        assertEquals(401, get("/api/v1/sales?importId=" + importId, false).statusCode());
        assertEquals(400, get("/api/v1/sales?importId=not-a-uuid", true).statusCode());
        var missing = get("/api/v1/sales?importId=00000000-0000-0000-0000-000000000000", true);
        assertEquals(200, missing.statusCode());
        assertEquals("[]", missing.body());
    }

    @Test
    void importScopedSalesSupportOffsetAndCursorPagination() throws Exception {
        var importId = uploadOverlappingBatches();
        long firstId = database.queryForObject(
                "SELECT id FROM sale WHERE import_id = ? AND sku = 'A'", Long.class, importId);
        var first = get("/api/v1/sales?importId=" + importId + "&size=1", true);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"id\":" + firstId + ","), first.body());

        var offset = get("/api/v1/sales?importId=" + importId + "&size=1&page=1", true);
        assertEquals(200, offset.statusCode());
        assertTrue(offset.body().contains("\"sku\":\"B\""), offset.body());
        var cursor = get("/api/v1/sales?importId=" + importId
                + "&size=1&afterSoldAt=2026-09-01&afterId=" + firstId, true);
        assertEquals(200, cursor.statusCode());
        assertEquals(offset.body(), cursor.body());

        var combined = get("/api/v1/sales?importId=" + importId
                + "&category=Displays&size=1&afterSoldAt=2026-09-01&afterId=" + firstId, true);
        assertEquals(200, combined.statusCode());
        assertEquals(cursor.body(), combined.body());
    }

    private java.util.UUID uploadOverlappingBatches() throws Exception {
        assertEquals(201, upload(CSV).statusCode());
        assertEquals(201, upload("sold_at,sku,category,quantity,unit_price\n"
                + "2026-09-01,A,Accessories,5,10.00\n"
                + "2026-09-02,C,Displays,1,15.00\n").statusCode());
        return database.queryForObject("SELECT import_id FROM sale WHERE sku = 'B'", java.util.UUID.class);
    }

    @Test
    void cursorPaginationStaysStableWhenEarlierSalesArrive() throws Exception {
        assertEquals(201, upload(CSV).statusCode());

        var first = get("/api/v1/sales?size=1", true);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"sku\":\"A\""), first.body());
        long firstId = database.queryForObject(
                "SELECT id FROM sale WHERE sku = 'A'", Long.class);

        String earlierCsv = "sold_at,sku,category,quantity,unit_price\n"
                + "2026-08-31,EARLIER,Accessories,1,5.00\n";
        assertEquals(201, upload(earlierCsv).statusCode());

        var shiftedOffset = get("/api/v1/sales?page=1&size=1", true);
        assertTrue(shiftedOffset.body().contains("\"sku\":\"A\""),
                shiftedOffset.body());

        var stableCursor = get("/api/v1/sales?size=1"
                + "&afterSoldAt=2026-09-01&afterId=" + firstId, true);
        assertEquals(200, stableCursor.statusCode());
        assertTrue(stableCursor.body().contains("\"sku\":\"B\""),
                stableCursor.body());
        assertFalse(stableCursor.body().contains("\"sku\":\"A\""),
                stableCursor.body());
        assertFalse(stableCursor.body().contains("\"sku\":\"EARLIER\""),
                stableCursor.body());
    }

    @Test
    void validatesCursorParameters() throws Exception {
        assertEquals(400, get("/api/v1/sales?afterId=1", true).statusCode());
        assertEquals(400, get(
                "/api/v1/sales?afterSoldAt=2026-09-01", true).statusCode());
        assertEquals(400, get(
                "/api/v1/sales?afterSoldAt=2026-09-01&afterId=0", true).statusCode());
        assertEquals(400, get(
                "/api/v1/sales?afterSoldAt=2026-09-01&afterId=1&page=1", true)
                .statusCode());
        assertEquals(400, get(
                "/api/v1/sales?from=2026-09-02&afterSoldAt=2026-09-01&afterId=1",
                true).statusCode());
    }

    @Test
    void protectsApiAndValidatesQueries() throws Exception {
        assertEquals(401, get("/api/v1/sales", false).statusCode());
        assertEquals(400, get("/api/v1/sales?size=101", true).statusCode());
        assertEquals(400, get("/api/v1/sales?category=%20", true).statusCode());
        assertEquals(400, get("/api/v1/sales?category=" + "x".repeat(81), true).statusCode());
        assertEquals(400, get("/api/v1/analytics/summary?from=2026-09-03&to=2026-09-01", true).statusCode());
        assertEquals(200, get("/actuator/health", false).statusCode());
    }

    private int uploadUnchecked(String csv) {
        try {
            return upload(csv).statusCode();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private HttpResponse<String> upload(String csv) throws Exception {
        String boundary = "DataDockTestBoundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"sales.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        return client.send(HttpRequest.newBuilder(uri("/api/v1/imports"))
                .header("X-API-Key", KEY)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(uri(path));
        if (authenticated) request.header("X-API-Key", KEY);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
