package org.fossify.phone.helpers

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.models.RecentCall
import java.io.File

/**
 * The calls shown at the last start, so the next start shows them right away; reading the call log and the contacts
 * takes a moment, the fresh list replaces them shortly after. The file is read in the background as soon as the app
 * starts, the call history only takes the result.
 */
object RecentsCache {
    private const val FILE_NAME = "dumberkit_recent_calls.json"
    private const val SIZE = 40

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var preloaded: List<RecentCall>? = null

    fun preload(context: Context) {
        val appContext = context.applicationContext
        ensureBackgroundThread { load(appContext) }
    }

    /** The cached calls, read once and then kept up to date in memory by save(). */
    fun take(context: Context): List<RecentCall> = load(context)

    fun save(context: Context, calls: List<RecentCall>) {
        val appContext = context.applicationContext
        synchronized(lock) {
            preloaded = calls.take(SIZE)
        }
        ensureBackgroundThread {
            try {
                file(appContext).writeText(json.encodeToString(ListSerializer(RecentCall.serializer()), calls.take(SIZE)))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun load(context: Context): List<RecentCall> = synchronized(lock) {
        preloaded ?: try {
            json.decodeFromString(ListSerializer(RecentCall.serializer()), file(context).readText())
        } catch (e: Exception) {
            emptyList()
        }.also { preloaded = it }
    }

    private fun file(context: Context) = File(context.cacheDir, FILE_NAME)
}
