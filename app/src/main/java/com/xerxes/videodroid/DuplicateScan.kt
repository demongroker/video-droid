package com.xerxes.videodroid

/**
 * Finds videos that are the same media saved twice.
 *
 * Two shapes exist in the Library:
 *  - the same name repeated with a `_<13-digit timestamp>` collision suffix (what the old
 *    save path produced when a name was already taken);
 *  - the same X post saved under two different names — the native extractor returns
 *    `<account>_-_<text>-[<id>]` while the fallback returns bare text — but both carry the
 *    six-digit status tag.
 *
 * Pure Kotlin (no Android imports) so the grouping is unit tested on the JVM. The caller
 * decides what to do with a group; nothing here deletes or moves anything.
 */
object DuplicateScan {

    /** One saved video; [id] is whatever the caller uses to address the file. */
    data class Entry(val id: String, val name: String)

    /** Quality/aspect tail every name this app writes ends in. */
    private val QUALITY_TAIL = Regex("""_(?:best|4k|1080p|720p|480p)_""", RegexOption.IGNORE_CASE)

    /** The 6-digit status tag X names carry, e.g. `..._861035_...`. */
    private val STATUS_TAG = Regex("""(?:^|_)(\d{6})(?:_|\.)""")

    /** `name_720p_Original_1731234567890.mp4` → `name_720p_original.mp4` */
    private val COLLISION_SUFFIX = Regex("""_(\d{13})(?=\.[a-z0-9]+$)""", RegexOption.IGNORE_CASE)

    /** Identity of a file name, independent of a collision suffix. */
    fun key(name: String): String =
        COLLISION_SUFFIX.replace(name.trim(), "").lowercase()

    /** True when the name carries the `_<timestamp>` suffix the old save path appended. */
    fun hasCollisionSuffix(name: String): Boolean = COLLISION_SUFFIX.containsMatchIn(name)

    /** The X status tag in a name this app produced, or null. */
    fun statusTagOf(name: String): String? {
        if (!QUALITY_TAIL.containsMatchIn(name)) return null
        return STATUS_TAG.find(name)?.groupValues?.get(1)
    }

    /**
     * Groups of two or more entries holding the same media, biggest group first.
     *
     * Entries are expected newest-first, so `group.first()` is the copy worth keeping.
     */
    fun duplicates(entries: List<Entry>): List<List<Entry>> {
        val groups = ArrayList<MutableList<Entry>>()

        val byKey = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) {
            val k = key(e.name)
            if (k.isEmpty()) continue
            byKey.getOrPut(k) { mutableListOf() }.add(e)
        }
        byKey.values.filter { it.size > 1 }.forEach { groups.add(it.toMutableList()) }

        val byTag = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) {
            val tag = statusTagOf(e.name) ?: continue
            byTag.getOrPut(tag) { mutableListOf() }.add(e)
        }
        for (g in byTag.values) {
            if (g.size < 2) continue
            // Already covered by a name-key group?
            if (groups.any { it.map { e -> e.id }.containsAll(g.map { e -> e.id }) }) continue
            groups.add(g.toMutableList())
        }

        return groups.sortedByDescending { it.size }
            .map { g -> g.sortedBy { if (hasCollisionSuffix(it.name)) 1 else 0 } }
    }

    /** Everything except the first entry of each group — the copies to move aside. */
    fun extras(groups: List<List<Entry>>): List<Entry> =
        groups.flatMap { g -> g.drop(1) }
}
