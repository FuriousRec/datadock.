package io.github.furiousrec.datadock.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Shared executable regression checks: Maven/JUnit and dependency-free local runner. */
public final class CoreChecks {
    private static final String HEADER = "sold_at,sku,category,quantity,unit_price\n";
    private static int checks;

    private CoreChecks() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        checks = 0;
        var sales = SalesCsv.parse(bytes(HEADER + "2026-09-01,KB,Accessories,2,49.90\n"));
        check(sales.size() == 1, "one sale");
        check(sales.get(0).revenue().equals(new BigDecimal("99.80")), "decimal arithmetic");
        check(parse("2026-09-01,\"KB,1\",Accessories,1,1.25").get(0).sku().equals("KB,1"),
                "quoted comma");
        check(parse("2026-09-01,\"KB\"\"1\",Accessories,1,1").get(0).sku().equals("KB\"1"),
                "escaped quote");
        check(SalesCsv.parse(bytes("\uFEFF" + HEADER + "2026-09-01,A,B,1,0")).size() == 1, "BOM");
        check(SalesCsv.parse(bytes((HEADER + "2026-09-01,A,B,1,0\n").replace("\n", "\r\n")))
                .size() == 1, "CRLF");
        check(parse("2024-02-29,A,B,10000,999999.99").size() == 1, "valid bounds");
        rejects("", "empty file");
        rejects(HEADER, "header only");
        rejects("bad,header\n", "wrong header");
        rejects(HEADER + "2026-02-29,A,B,1,1", "invalid leap date");
        rejects(HEADER + "0000-01-01,A,B,1,1", "year zero");
        rejects(HEADER + "2026-01-01,A,B,0,1", "zero quantity");
        rejects(HEADER + "2026-01-01,A,B,10001,1", "quantity upper bound");
        rejects(HEADER + "2026-01-01,A,B,-1,1", "negative quantity");
        rejects(HEADER + "2026-01-01,A,B,1,-1", "negative price");
        rejects(HEADER + "2026-01-01,A,B,1,0.001", "fractional cent");
        rejects(HEADER + "2026-01-01,A,B,1,1e3", "exponent");
        rejects(HEADER + "2026-01-01,A,B,1,1000000", "price upper bound");
        rejects(HEADER + "2026-01-01,,B,1,1", "empty SKU");
        rejects(HEADER + "2026-01-01," + "x".repeat(81) + ",B,1,1", "long label");
        rejects(HEADER + "2026-01-01,A,B,1", "missing column");
        rejects(HEADER + "2026-01-01,A,B,1,1,extra", "extra column");
        rejects(HEADER + "2026-01-01,\"A,B,1,1", "unclosed quote");
        rejects(HEADER + "2026-01-01,\"A\"x,B,1,1", "text after closing quote");
        rejects(HEADER + "2026-01-01,A\"x,B,1,1", "quote inside unquoted field");
        rejects(HEADER + "2026-01-01,\"A\nB\",B,1,1", "control character in label");
        rejects(HEADER + "\n", "blank record");
        rejects(HEADER + "2026-01-01,A\0,B,1,1", "NUL");
        rejectsBytes(new byte[]{(byte) 0xc3, (byte) 0x28}, "malformed UTF-8");
        rejectsBytes(new byte[SalesCsv.MAX_BYTES + 1], "size limit");
        String row = "2026-01-01,A,B,1,1\n";
        check(SalesCsv.parse(bytes(HEADER + row.repeat(10_000))).size() == 10_000, "row limit inclusive");
        rejects(HEADER + row.repeat(10_001), "row limit exceeded");
        System.out.println("PASS: " + checks + " CSV/domain checks");
    }

    private static java.util.List<Sale> parse(String rows) {
        return SalesCsv.parse(bytes(HEADER + rows));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void rejects(String text, String name) {
        rejectsBytes(bytes(text), name);
    }

    private static void rejectsBytes(byte[] bytes, String name) {
        try {
            SalesCsv.parse(bytes);
            throw new AssertionError(name + ": expected rejection");
        } catch (InvalidCsvException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
