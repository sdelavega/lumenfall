package com.sdelavega.lumenfall.web

/**
 * In-memory, process-wide ledger of the external domains each web bundle has
 * actually contacted — ported from ÆtherDesk's NetworkPolicy domain log.
 *
 * Only *allowed* requests are recorded: a blocked request never left the
 * device, so listing it would overstate what the bundle did (the block is
 * already surfaced in the bridge log). Deduped, insertion-ordered, capped per
 * bundle. Deliberately not persisted, matching ÆtherDesk: "since this app
 * started" is the honest scope for a live transparency readout — a stale
 * on-disk list from last month says nothing about what the bundle does today,
 * and the wallpaper engine, settings preview, and thumbnailer all run in this
 * one process, so everything a bundle contacts lands here.
 *
 * Thread-safe: requests are observed on WebView's background request threads,
 * reads happen on the UI thread.
 */
object ContactedDomains {

    private const val MAX_PER_BUNDLE = 100

    private val logs = HashMap<String, LinkedHashSet<String>>()

    @Synchronized
    fun log(bundleId: String, host: String) {
        val set = logs.getOrPut(bundleId) { LinkedHashSet() }
        if (host in set) return
        if (set.size >= MAX_PER_BUNDLE) set.remove(set.first())
        set.add(host)
    }

    /** Domains [bundleId] has contacted since process start, in first-contact order. */
    @Synchronized
    fun get(bundleId: String): List<String> = logs[bundleId]?.toList() ?: emptyList()

    @Synchronized
    fun clear(bundleId: String) {
        logs.remove(bundleId)
    }
}
