package com.local.wxbotprobe.memory.v2

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

internal fun SQLiteDatabase.endTransactionOrStorageFailure() {
    try {
        endTransaction()
    } catch (error: SQLiteException) {
        throw MemoryV2Exception(
            MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
            error.message.orEmpty(),
        )
    }
}
