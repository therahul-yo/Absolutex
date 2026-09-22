package com.absolutex.feature.reader

import android.content.Context
import com.absolutex.source.PageReadability

/** Do not substitute discovered slots for an unknown original total. */
internal fun Context.recoveryNotice(report: PageReadability): String = report.totalPageCount?.let { total ->
    getString(R.string.reader_recovery_known, report.readablePageCount, total)
} ?: getString(R.string.reader_recovery_unknown, report.readablePageCount)
