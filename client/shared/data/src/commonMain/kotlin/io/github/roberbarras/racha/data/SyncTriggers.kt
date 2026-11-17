package io.github.roberbarras.racha.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Outbox for sync (spec §7.4, ADR-0012): every write to a synchronised table queues the row's id in `sync_queue` in
 * the same transaction, whatever code path made it. Nothing is recorded while sync is off
 * (`sync_state.recording = 0`); changes coming from the server are applied with `applying = 1`, so they are not
 * queued back. Library rows only queue when a user-visible column changes
 * (not on every progress percentage update). Created when the database opens: SQLDelight's SQLite 3.18 dialect
 * cannot parse NEW/OLD, but SQLite runs them fine.
 */
internal object SyncTriggers {
    fun install(driver: SqlDriver) {
        STATEMENTS.forEach { driver.execute(null, it, 0) }
    }

    val STATEMENTS: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS sync_category_insert AFTER INSERT ON category FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'category', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_category_update AFTER UPDATE ON category FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'category', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_category_delete AFTER DELETE ON category FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'category', OLD.key, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_root_insert AFTER INSERT ON category_root FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'categoryRoot', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_root_update AFTER UPDATE OF category_key, sort_order, layout ON category_root FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'categoryRoot', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_root_delete AFTER DELETE ON category_root FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'categoryRoot', OLD.key, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_item_insert AFTER INSERT ON library_item FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'libraryItem', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_item_update AFTER UPDATE ON library_item FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1) AND (OLD.category_key IS NOT NEW.category_key OR OLD.kind IS NOT NEW.kind OR OLD.title IS NOT NEW.title OR OLD.title_override IS NOT NEW.title_override OR OLD.order_override IS NOT NEW.order_override OR OLD.archived IS NOT NEW.archived OR OLD.total_items IS NOT NEW.total_items OR OLD.total_duration_sec IS NOT NEW.total_duration_sec)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'libraryItem', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_item_delete AFTER DELETE ON library_item FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'libraryItem', OLD.id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_section_insert AFTER INSERT ON section FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'section', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_section_update AFTER UPDATE ON section FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1) AND (OLD.kind IS NOT NEW.kind OR OLD.title IS NOT NEW.title OR OLD.title_override IS NOT NEW.title_override OR OLD.sort_order IS NOT NEW.sort_order OR OLD.order_override IS NOT NEW.order_override)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'section', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_section_delete AFTER DELETE ON section FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'section', OLD.id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_element_insert AFTER INSERT ON playable_element FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'element', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_element_update AFTER UPDATE ON playable_element FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1) AND (OLD.section_id IS NOT NEW.section_id OR OLD.title IS NOT NEW.title OR OLD.title_override IS NOT NEW.title_override OR OLD.sort_order IS NOT NEW.sort_order OR OLD.order_override IS NOT NEW.order_override OR OLD.archived IS NOT NEW.archived OR OLD.duration_sec IS NOT NEW.duration_sec OR OLD.skip_intro_sec IS NOT NEW.skip_intro_sec OR OLD.skip_outro_sec IS NOT NEW.skip_outro_sec OR OLD.file_hash IS NOT NEW.file_hash)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'element', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_element_delete AFTER DELETE ON playable_element FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'element', OLD.id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_subtitle_insert AFTER INSERT ON subtitle FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'subtitle', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_subtitle_delete AFTER DELETE ON subtitle FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'subtitle', OLD.id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_progress_insert AFTER INSERT ON progress FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'progress', NEW.element_id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_progress_update AFTER UPDATE ON progress FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'progress', NEW.element_id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_progress_delete AFTER DELETE ON progress FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'progress', OLD.element_id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_bookmark_insert AFTER INSERT ON bookmark FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'bookmark', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_bookmark_update AFTER UPDATE ON bookmark FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'bookmark', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_bookmark_delete AFTER DELETE ON bookmark FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'bookmark', OLD.id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_history_insert AFTER INSERT ON history_entry FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'history', NEW.id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_favorite_insert AFTER INSERT ON favorite FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'favorite', NEW.target_type || ':' || NEW.target_id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_favorite_delete AFTER DELETE ON favorite FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'favorite', OLD.target_type || ':' || OLD.target_id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_summary_insert AFTER INSERT ON summary FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'summary', NEW.target_id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_summary_update AFTER UPDATE ON summary FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'summary', NEW.target_id, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_summary_delete AFTER DELETE ON summary FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'summary', OLD.target_id, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_setting_insert AFTER INSERT ON app_setting FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'setting', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_setting_update AFTER UPDATE ON app_setting FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1) AND (OLD.value IS NOT NEW.value)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'setting', NEW.key, 'UPSERT', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS sync_setting_delete AFTER DELETE ON app_setting FOR EACH ROW WHEN (SELECT recording = 1 AND applying = 0 FROM sync_state WHERE id = 1)
        BEGIN
            INSERT INTO sync_queue(entity_type, entity_id, operation, payload, retries, last_error, created_at) SELECT 'setting', OLD.key, 'DELETE', NULL, 0, NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER);
        END;
        """.trimIndent()
    )
}
