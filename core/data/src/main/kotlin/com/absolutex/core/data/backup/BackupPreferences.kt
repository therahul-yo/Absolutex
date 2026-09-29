package com.absolutex.core.data.backup

import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.MapPrefBag
import com.absolutex.core.data.settings.PrefCodec
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.snapshot
import java.math.BigDecimal

/** A whitelist derived from the models, never from raw persisted keys. */
internal object BackupPreferences {
    fun encode(app: AppPrefs, reader: ReaderPrefs, rendering: RenderingPrefs): Map<String, Any> {
        val bag = MapPrefBag()
        PrefCodec.encodeApp(app.copy(locations = emptySet()), bag)
        PrefCodec.encodeReader(reader, bag)
        PrefCodec.encodeRendering(rendering, bag)
        bag.putStringSet("fit_by_context", reader.fitMemory.asPairs().map { "${it.key}=${it.value}" }.toSet())
        return bag.snapshot()
    }

    fun validate(raw: Map<String, Any?>): Map<String, Any> {
        val types = encode(AppPrefs(), ReaderPrefs(), RenderingPrefs())
        return raw.filterKeys { it in types }.mapValues { (key, value) ->
            when (types.getValue(key)) {
                is Boolean -> value as? Boolean ?: error("Expected boolean")
                is Int -> (value as? BigDecimal)?.intValueExact() ?: error("Expected integer")
                is Float -> (value as? BigDecimal)?.toFloat()?.also { require(it.isFinite()) }
                    ?: error("Expected number")
                is String -> value as? String ?: error("Expected string")
                else -> (value as? List<*>)?.map { it as? String ?: error("Expected string") }?.toSet()
                    ?: error("Expected string array")
            }
        }
    }
}
