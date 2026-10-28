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
    void rejectsInvalidRowsWithoutPartialWrites() throws Exception {
        assertEquals(400, upload(CSV + "2026-09-02,C,Accessories,-1,1\n").statusCode());
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM sale", Integer.class));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class));
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
