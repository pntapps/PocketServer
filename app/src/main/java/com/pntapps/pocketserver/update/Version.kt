package com.pntapps.pocketserver.update

/**
 * Semantic version as used in release tags: "v1.2.3", "1.2", "1.2.3-beta.2", "1.2.3+build5".
 * Ordering follows semver 2.0: a pre-release sorts before its release, build metadata is ignored.
 */
data class Version(val core: List<Int>, val pre: List<String>) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(core.size, other.core.size)) {
            val c = core.getOrElse(i) { 0 }.compareTo(other.core.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        when {
            pre.isEmpty() && other.pre.isEmpty() -> return 0
            pre.isEmpty() -> return 1
            other.pre.isEmpty() -> return -1
        }
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = comparePre(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString() = core.joinToString(".") + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): Version? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val core = m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val pre = m.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() }) return null
            return Version(core, pre)
        }

        /** True only when both parse and [remote] is strictly newer, so a weird tag never triggers an update. */
        fun isNewer(remote: String, current: String): Boolean {
            val r = parse(remote) ?: return false
            val c = parse(current) ?: return false
            return r > c
        }

        // Numeric identifiers compare numerically and sort before alphanumeric ones.
        private fun comparePre(a: String, b: String): Int {
            val x = a.toLongOrNull(); val y = b.toLongOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
