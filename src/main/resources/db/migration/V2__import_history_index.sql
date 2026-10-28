CREATE INDEX import_batch_created_at_id_idx
    ON import_batch(created_at DESC, id DESC);
