package io.github.aviato28.scopecreep.update

/** Pure update logic shared by every flavour (kept free of Android types so it can be unit tested on the JVM). */
data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val checksumsUrl: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val fraction: Float) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

class Semver private constructor(private val core: List<Int>, private val pre: List<String>) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        for (i in 0 until 3) core[i].compareTo(other.core[i]).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.compareTo(pre.size) // no pre-release ranks higher
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.toLongOrNull(); val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        /** Parses "v1.2.3", "1.2.3-beta.1" (build metadata ignored). Returns null when malformed. */
        fun parse(text: String): Semver? {
            val s = text.trim().removePrefix("v").substringBefore('+')
            val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""").matchEntire(s) ?: return null
            val nums = (1..3).map { m.groupValues[it].toIntOrNull() ?: return null }
            return Semver(nums, m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList())
        }

        /** Strips local build suffixes ("-preview", "-dev") from our own BuildConfig.VERSION_NAME. */
        fun parseInstalled(versionName: String): Semver? =
            parse(versionName.replace("-preview", "").replace("-dev", ""))
    }
}

object Checksums {
    /** Finds the SHA-256 for [fileName] in `sha256sum` output ("<hex>  <name>" or "<hex> *<name>"). Lower-cased. */
    fun find(checksums: String, fileName: String): String? =
        checksums.lineSequence().mapNotNull { line ->
            val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""").matchEntire(line.trim()) ?: return@mapNotNull null
            if (m.groupValues[2].substringAfterLast('/') == fileName) m.groupValues[1].lowercase() else null
        }.firstOrNull()
}

/** True when [latestTag] is strictly newer than the installed version. */
fun isNewer(latestTag: String, installedVersionName: String): Boolean {
    val latest = Semver.parse(latestTag) ?: return false
    val current = Semver.parseInstalled(installedVersionName) ?: return true
    return latest > current
}
