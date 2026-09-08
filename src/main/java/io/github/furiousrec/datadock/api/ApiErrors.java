package io.github.furiousrec.datadock.api;

import io.github.furiousrec.datadock.domain.InvalidCsvException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({InvalidCsvException.class, IllegalArgumentException.class})
    ProblemDetail invalidInput(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicateImport() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This exact CSV file was already imported. No rows were added.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "Maximum CSV size is 2 MiB.");
    }
}
