package io.github.furiousrec.datadock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Sale(LocalDate soldAt, String sku, String category, int quantity, BigDecimal unitPrice) {
    public BigDecimal revenue() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
