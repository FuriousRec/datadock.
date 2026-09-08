package io.github.furiousrec.datadock.domain;

import org.junit.jupiter.api.Test;

class SalesCsvTest {
    @Test
    void validatesCsvAndDomainRules() {
        CoreChecks.run();
    }
}
