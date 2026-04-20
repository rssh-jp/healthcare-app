package com.healthcare.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE walking_sessions ADD COLUMN sessionUuid TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            "ALTER TABLE walking_sessions ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'"
        )
        database.execSQL(
            "ALTER TABLE walking_sessions ADD COLUMN firestoreDocId TEXT"
        )
        // 既存行に UUID v4 近似値を付与（SQLite randomblob を使用）
        database.execSQL(
            """
            UPDATE walking_sessions SET sessionUuid =
                lower(hex(randomblob(4))) || '-' ||
                lower(hex(randomblob(2))) || '-4' ||
                substr(lower(hex(randomblob(2))), 2) || '-' ||
                substr('89ab', abs(random() % 4) + 1, 1) ||
                substr(lower(hex(randomblob(2))), 2) || '-' ||
                lower(hex(randomblob(6)))
            """.trimIndent()
        )
    }
}
