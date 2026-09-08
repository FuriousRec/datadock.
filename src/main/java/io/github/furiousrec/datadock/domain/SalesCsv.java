package io.github.furiousrec.datadock.domain;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Bounded UTF-8 CSV reader. All records are validated before persistence starts. */
public final class SalesCsv {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 10_000;
    private static final List<String> HEADER =
            List.of("sold_at", "sku", "category", "quantity", "unit_price");

    private SalesCsv() {}

    public static List<Sale> parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new InvalidCsvException("File must contain 1 to 2097152 bytes.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidCsvException("File must be valid UTF-8.");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = tokenize(text);
        if (records.isEmpty() || !records.get(0).equals(HEADER)) {
            throw new InvalidCsvException("Expected header: " + String.join(",", HEADER));
        }
        if (records.size() == 1) throw new InvalidCsvException("At least one sale is required.");
        List<Sale> sales = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> fields = records.get(index);
            int record = index + 1;
            if (fields.size() != HEADER.size()) {
                throw new InvalidCsvException("Record " + record + ": expected 5 columns.");
            }
            try {
                String dateText = fields.get(0).strip();
                if (!dateText.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new IllegalArgumentException();
                }
                LocalDate date = LocalDate.parse(dateText);
                if (date.getYear() < 1) throw new IllegalArgumentException();
                String sku = label(fields.get(1));
                String category = label(fields.get(2));
                String quantityText = fields.get(3).strip();
                if (!quantityText.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
                int quantity = Integer.parseInt(quantityText);
                if (quantity < 1 || quantity > 10_000) throw new IllegalArgumentException();
                String priceText = fields.get(4).strip();
                if (!priceText.matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) {
                    throw new IllegalArgumentException();
                }
                BigDecimal price = new BigDecimal(priceText).setScale(2);
                sales.add(new Sale(date, sku, category, quantity, price));
            } catch (IllegalArgumentException | java.time.DateTimeException exception) {
                throw new InvalidCsvException("Record " + record
                        + ": invalid date, label, quantity (1–10000), or EUR price (0–999999.99).");
            }
        }
        return List.copyOf(sales);
    }

    private static String label(String value) {
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 80
                || clean.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return clean;
    }

    private static List<List<String>> tokenize(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        boolean touched = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\0') throw new InvalidCsvException("NUL characters are not allowed.");
            touched = true;
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    field.append(character);
                }
            } else if (character == ',' || character == '\n' || character == '\r') {
                row.add(field.toString());
                field.setLength(0);
                closedQuote = false;
                if (row.size() > 5) throw new InvalidCsvException("Too many CSV columns.");
                if (character != ',') {
                    records.add(List.copyOf(row));
                    row.clear();
                    touched = false;
                    if (records.size() > MAX_ROWS + 1) {
                        throw new InvalidCsvException("Maximum 10000 sales per import.");
                    }
                    if (character == '\r' && index + 1 < text.length()
                            && text.charAt(index + 1) == '\n') index++;
                }
            } else if (character == '"' && field.length() == 0 && !closedQuote) {
                quoted = true;
            } else {
                if (closedQuote || character == '"') {
                    throw new InvalidCsvException("Unexpected character around a quoted field.");
                }
                field.append(character);
            }
        }
        if (quoted) throw new InvalidCsvException("Unclosed quoted field.");
        if (touched) {
            row.add(field.toString());
            records.add(List.copyOf(row));
        }
        if (records.size() > MAX_ROWS + 1) {
            throw new InvalidCsvException("Maximum 10000 sales per import.");
        }
        return records;
    }
}
