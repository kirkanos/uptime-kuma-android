package net.kirkanos.uptimekuma.core.domain.editor

import net.kirkanos.uptimekuma.core.model.MonitorTag

/**
 * Changes needed to turn a monitor's [current] tags into [desired] ones. A monitor tag row is identified by
 * `(tagId, value)`; Kuma allows the same tag several times with different values.
 *
 * When a tag occurs exactly once on both sides with a different value, the change becomes an
 * `editMonitorTag` (which rewrites all rows of that tag/monitor); everything else is delete + add.
 */
data class TagDiff(
    val toAdd: List<MonitorTag>,
    val toDelete: List<MonitorTag>,
    /** Pairs of (old row, new value). */
    val toEdit: List<Pair<MonitorTag, String>>,
) {
    val isEmpty: Boolean get() = toAdd.isEmpty() && toDelete.isEmpty() && toEdit.isEmpty()

    companion object {
        fun of(
            current: List<MonitorTag>,
            desired: List<MonitorTag>,
        ): TagDiff {
            val key: (MonitorTag) -> Pair<Long, String> = { it.tagId to it.value.trim() }
            val currentKeys = current.map(key).toSet()
            val desiredKeys = desired.map(key).toSet()
            var add = desired.filter { key(it) !in currentKeys }.distinctBy(key)
            var delete = current.filter { key(it) !in desiredKeys }.distinctBy(key)
            val edits = mutableListOf<Pair<MonitorTag, String>>()
            for (old in delete.toList()) {
                val sameTagCurrent = current.count { it.tagId == old.tagId }
                val sameTagDesired = desired.count { it.tagId == old.tagId }
                val replacement = add.singleOrNull { it.tagId == old.tagId }
                if (sameTagCurrent == 1 && sameTagDesired == 1 && replacement != null) {
                    edits += old to replacement.value.trim()
                    add = add - replacement
                    delete = delete - old
                }
            }
            return TagDiff(toAdd = add, toDelete = delete, toEdit = edits)
        }
    }
}
