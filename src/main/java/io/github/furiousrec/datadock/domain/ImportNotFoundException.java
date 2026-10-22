package io.github.furiousrec.datadock.domain;

import java.util.UUID;

public final class ImportNotFoundException extends RuntimeException {
    public ImportNotFoundException(UUID id) {
        super("Import " + id + " was not found.");
    }
}
