package com.maxrave.kotlinytmusicscraper.cipher

/**
 * Small text files the cipher engine keeps between app starts, so the first song after a launch
 * does not wait on downloads that rarely change: YouTube's player script (a few MB, the same for
 * days at a time — named by its player id, so a new player is simply a new file) and the remote
 * player table. Implemented per platform, because this module has no file access in common code.
 *
 * Every call must swallow its own errors: a cache that cannot be read is just a cache miss.
 */
interface CipherDiskCache {
    fun read(name: String): String?

    fun write(
        name: String,
        text: String,
    )

    fun delete(name: String)

    /** Names currently stored, for pruning old player scripts. */
    fun names(): List<String>
}

/**
 * [PlayerConfigRepository] that survives restarts through [cache]. The table is fetched at most
 * every six hours (RemotePlayerConfigStore's TTL) and revalidated with its ETag after that, so a
 * relaunch within those hours makes no request for it at all.
 */
internal class DiskPlayerConfigRepository(
    private val url: String,
    private val cache: CipherDiskCache,
) : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = url
    override val defaultSourceUrl: String = url

    override var cachedJson: String = cache.read(JSON_FILE).orEmpty()
        set(value) {
            field = value
            cache.write(JSON_FILE, value)
        }

    private val storedMeta = cache.read(META_FILE)?.split('\n').orEmpty()

    override var cachedAtMs: Long = storedMeta.getOrNull(0)?.toLongOrNull() ?: 0L
        set(value) {
            field = value
            writeMeta()
        }
    override var cachedSourceUrl: String = storedMeta.getOrNull(1).orEmpty()
        set(value) {
            field = value
            writeMeta()
        }
    override var cachedEtag: String = storedMeta.getOrNull(2).orEmpty()
        set(value) {
            field = value
            writeMeta()
        }

    private fun writeMeta() = cache.write(META_FILE, "$cachedAtMs\n$cachedSourceUrl\n$cachedEtag")

    private companion object {
        const val JSON_FILE = "player_configs.json"
        const val META_FILE = "player_configs.meta"
    }
}
