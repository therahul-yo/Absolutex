package com.absolutex.remote.sync

import java.io.IOException

/**
 * Kavita library browsing for identity matching: volume ids, then chapters with their files.
 * The volumes listing does not reliably embed files, but the single-volume route does
 * (server includes Chapters|Files there), so matching walks series → volumes → volume files.
 */
class KavitaLibrary(private val client: KavitaClient) {

    /**
     * Volume ids for file-identity matching. Chapters (with their files) come per volume:
     * the volumes listing does not reliably embed files, but the single-volume route does
     * (server includes Chapters|Files there — verified in VolumeRepository.GetVolumeDtoAsync).
     */
    suspend fun volumeIds(seriesId: Int): List<Int> {
        val response = client.authedRequest("GET", "/api/Series/volumes?seriesId=$seriesId", null)
        if (response.code != HTTP_OK) throw IOException("kavita volumes failed: ${response.code}")
        return parseKavitaVolumeIds(response.body)
    }

    suspend fun volume(volumeId: Int): List<KavitaChapterFiles> {
        val response = client.authedRequest("GET", "/api/Series/volume?volumeId=$volumeId", null)
        if (response.code != HTTP_OK) throw IOException("kavita volume failed: ${response.code}")
        return parseKavitaVolume(response.body)
    }

    /** Library id for progress saves; callers cache it per series (stable per process). */
    suspend fun seriesLibraryId(seriesId: Int): Int {
        val response = client.authedRequest("GET", "/api/Series/$seriesId", null)
        if (response.code != HTTP_OK) throw IOException("kavita series failed: ${response.code}")
        return parseKavitaSeriesLibraryId(response.body)
    }

    /** Every chapter's files in the library, for one identity-map build per sync run. */
    suspend fun allChapterFiles(): List<KavitaChapterFiles> {
        val out = mutableListOf<KavitaChapterFiles>()
        var page = 0
        while (page < MAX_PAGES) {
            val batch = client.seriesPage(page, SERIES_PAGE_SIZE)
            if (batch.isEmpty()) break
            for (series in batch) {
                val seriesId = series.id.toIntOrNull() ?: continue
                for (volumeId in volumeIds(seriesId)) {
                    out += volume(volumeId)
                }
            }
            page++
        }
        return out
    }

    companion object {
        private const val SERIES_PAGE_SIZE = 100
    }
}
