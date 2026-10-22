package io.github.furiousrec.datadock.api;

import io.github.furiousrec.datadock.domain.InvalidCsvException;
import io.github.furiousrec.datadock.domain.SalesCsv;
import io.github.furiousrec.datadock.service.SalesService;
import io.github.furiousrec.datadock.service.SalesService.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class SalesController {
    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > SalesCsv.MAX_BYTES) throw new InvalidCsvException("Maximum CSV size is 2 MiB.");
        return sales.importCsv(file.getBytes());
    }

    @GetMapping("/imports/{id}")
    public ImportView importById(@PathVariable java.util.UUID id) {
        return sales.importById(id);
    }

    @GetMapping("/sales")
    public List<SaleView> list(@RequestParam(defaultValue = "0001-01-01") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "9999-12-31") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate afterSoldAt,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return sales.sales(from, to, category, afterSoldAt, afterId, page, size);
    }

    @GetMapping("/analytics/summary")
    public Summary summary(@RequestParam(defaultValue = "0001-01-01") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "9999-12-31") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sales.summary(from, to);
    }

    @GetMapping("/analytics/categories")
    public List<CategorySummary> categories(@RequestParam(defaultValue = "0001-01-01") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "9999-12-31") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sales.categories(from, to);
    }
}
