package org.simpmusic.lyrics.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

// Title cleaning, the matching rules and KuGou's instrumental notice follow BitChord's LyricsQuery.kt,
// LrcRed.kt and KuGou.kt (GPL-3.0).

/** Close enough that it is the same cut: lrc.red lists exact lengths. */
internal const val EXACT_DURATION_TOLERANCE_SECONDS = 3.0

/** Community catalogues round lengths and time slightly different uploads. */
internal const val COMMUNITY_DURATION_TOLERANCE_SECONDS = 5.0

private val STAMP = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
private val STAMPED = Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")
private val CREDIT = Regex(""".+][^\[]+[:：].+""")
private val INSTRUMENTAL = Regex("""纯音乐.*欣赏""")
private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")
private val WHITESPACE = Regex("""\s+""")
private val BRACKETED = Regex("""[(\[（][^)\]）]*[)\]）]""")
private val TITLE_PARTS = Regex("""\s+[-–—]\s+""")
private val ARTIST_SEPARATORS =
    Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+và\s+)\s*""", RegexOption.IGNORE_CASE)

private val CREDITS =
    listOf(
        // (feat. X), [ft. X], (with X).
        Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        // The same, unbracketed and running to the end of the title.
        Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
        // How the upload was labelled, not what was recorded.
        Regex(
            """\s*[(\[]\s*(official\s*)?(music\s*)?(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
    )

/** Bracket words that make it a different recording rather than a different label. */
private val VERSION_WORDS =
    setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella", "acapella",
        "demo", "edit", "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
    )

// A table, since this module also targets iOS and java.text.Normalizer is JVM-only. Covers Vietnamese.
private val ACCENT_FOLDS: Map<Char, Char> =
    mapOf(
        'a' to "àáạảãâầấậẩẫăằắặẳẵäåāąǎ",
        'c' to "çćč",
        'd' to "đď",
        'e' to "èéẹẻẽêềếệểễëēęě",
        'g' to "ğ",
        'i' to "ìíịỉĩîïīı",
        'l' to "ł",
        'n' to "ñńň",
        'o' to "òóọỏõôồốộổỗơờớợởỡöøōő",
        'r' to "ř",
        's' to "śšş",
        't' to "ţť",
        'u' to "ùúụủũưừứựửữûüūůű",
        'y' to "ỳýỵỷỹÿ",
        'z' to "źżž",
    ).flatMap { (plain, marked) -> marked.map { it to plain } }.toMap()

/**
 * A YouTube title as a lyrics database would have indexed it. Credits and upload labels come off;
 * "(Remix)", "(Live)", "(Acoustic)" stay, because taking them off asks for a different recording and
 * brings back confidently wrong words.
 */
fun String.forLyricsSearch(): String {
    var name = this
    CREDITS.forEach { name = it.replace(name, " ") }
    return name
        .replace(WHITESPACE, " ")
        .trim()
        .trimEnd(',', '-', '–', '—')
        .trim()
        .ifBlank { trim() }
}

/** YouTube's auto-generated channel names, as they reach the player for licensed music. */
fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic")
        .removeSuffix("VEVO")
        .trim()
        .ifBlank { trim() }

private fun folded(text: String): String =
    text
        .lowercase()
        .map { ACCENT_FOLDS[it] ?: it }
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()

private fun compact(text: String): String = folded(text).replace(" ", "")

private fun artistKeys(artist: String): Set<String> =
    artist
        .split(ARTIST_SEPARATORS)
        .map(::compact)
        .filter { it.isNotEmpty() }
        .toSet()

/** Title outside its brackets; "Artist - Song" and "Song - Remaster" offer either side, minus the artist's name. */
private fun titleCores(
    title: String,
    artists: Set<String>,
): Set<String> {
    val parts =
        title
            .replace(BRACKETED, " ")
            .split(TITLE_PARTS)
            .map(::compact)
            .filter { it.isNotEmpty() }
    return parts.filterNot { it in artists }.ifEmpty { parts }.toSet()
}

private fun versionWords(
    title: String,
    artist: String,
): Set<String> = (folded(title).split(' ') - folded(artist).split(' ').toSet()).filter { it in VERSION_WORDS }.toSet()

private fun sharesAnArtist(
    a: Set<String>,
    b: Set<String>,
): Boolean =
    a.any { x ->
        b.any { y ->
            val (short, long) = if (x.length < y.length) x to y else y to x
            short == long || (short.length >= 4 && long.contains(short))
        }
    }

/**
 * Whether a catalogue's entry is this recording. Every test is a requirement, not a score: a miss
 * lets the next source answer, and the wrong words in time with the right song are worse than none.
 */
internal fun isSameRecording(
    candidateTitle: String,
    candidateArtist: String,
    candidateDuration: Double?,
    title: String,
    artist: String,
    durationSeconds: Int?,
    toleranceSeconds: Double,
): Boolean {
    val wantedArtists = artistKeys(artist)
    val theirArtists = artistKeys(candidateArtist)
    if (wantedArtists.isNotEmpty() && !sharesAnArtist(wantedArtists, theirArtists)) return false
    if (titleCores(candidateTitle, theirArtists).intersect(titleCores(title, wantedArtists)).isEmpty()) return false
    if (versionWords(candidateTitle, candidateArtist) != versionWords(title, artist)) return false
    if (durationSeconds == null || durationSeconds <= 0) return true
    // An entry with no length cannot be shown to be this cut: LRCLIB has some, and they answered for
    // lengths nothing else matched.
    return candidateDuration != null && abs(candidateDuration - durationSeconds) <= toleranceSeconds
}

private fun distance(
    candidateDuration: Double?,
    durationSeconds: Int?,
): Double = if (candidateDuration == null || durationSeconds == null) 0.0 else abs(candidateDuration - durationSeconds)

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** The entry LRCLIB should answer with: this recording, timed lyrics first, then the closest length. */
internal fun bestLrclibMatch(
    results: List<LrclibObject>,
    title: String,
    artist: String,
    durationSeconds: Int?,
): LrclibObject? =
    results
        .filter {
            isSameRecording(
                it.trackName ?: it.name,
                it.artistName.orEmpty(),
                it.duration?.toDouble(),
                title,
                artist,
                durationSeconds,
                COMMUNITY_DURATION_TOLERANCE_SECONDS,
            )
        }.minWithOrNull(
            compareBy<LrclibObject> { it.syncedLyrics.isNullOrEmpty() }
                .thenBy { distance(it.duration?.toDouble(), durationSeconds) },
        )

/** The recording code of the lrc.red hit that is this recording; safe to put in a path. */
internal fun lrcRedIsrc(
    json: String,
    title: String,
    artist: String,
    durationSeconds: Int?,
): String? =
    Json
        .parseToJsonElement(json)
        .jsonObject["hits"]
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }
        .filter {
            ISRC.matches(it.text("isrc").orEmpty()) &&
                isSameRecording(
                    it.text("title").orEmpty(),
                    it.text("artist").orEmpty(),
                    it.number("duration"),
                    title,
                    artist,
                    durationSeconds,
                    EXACT_DURATION_TOLERANCE_SECONDS,
                )
        }.minByOrNull { distance(it.number("duration"), durationSeconds) }
        ?.text("isrc")

/** Hashes of songs that are this recording, closest length first. */
internal fun kugouSongHashes(
    json: String,
    title: String,
    artist: String,
    durationSeconds: Int?,
): List<String> =
    Json
        .parseToJsonElement(json)
        .jsonObject["data"]
        ?.jsonObject
        ?.get("info")
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }
        .filter {
            it.text("hash") != null &&
                isSameRecording(
                    it.text("songname").orEmpty(),
                    it.text("singername").orEmpty(),
                    it.number("duration"),
                    title,
                    artist,
                    durationSeconds,
                    COMMUNITY_DURATION_TOLERANCE_SECONDS,
                )
        }.sortedBy { distance(it.number("duration"), durationSeconds) }
        .mapNotNull { it.text("hash") }

/** The first lyric candidate as (id, access key). */
internal fun kugouCandidate(json: String): Pair<String, String>? {
    val candidate =
        Json
            .parseToJsonElement(json)
            .jsonObject["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject ?: return null
    return (candidate.text("id") ?: return null) to (candidate.text("accesskey") ?: return null)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun kugouLrc(downloadJson: String): String? {
    val content = Json.parseToJsonElement(downloadJson).jsonObject.text("content") ?: return null
    val lrc = stripKugouCredits(Base64.decode(content).decodeToString())
    return lrc.takeIf { it.isNotBlank() && !isInstrumentalNotice(it) }
}

/**
 * KuGou opens a file with a timed "Title - Artist" line and songwriter lines, and may close it with
 * more credits, all of which would otherwise be sung as lyrics. Only an unbroken run of "label: value"
 * lines is cut from each end: BitChord's version cut up to the last such line in the first 30, which
 * also threw away every line sung before a lyric like "she said: stay".
 */
internal fun stripKugouCredits(lrc: String): String {
    val lines = lrc.lineSequence().filter { STAMPED.matches(it) }.toList()
    if (lines.isEmpty()) return ""
    // The title line carries no colon, so the run may start one line in.
    val first = if (lines.size > 1 && !CREDIT.matches(lines[0]) && CREDIT.matches(lines[1])) 1 else 0
    val creditsAtTop = lines.drop(first).takeWhile { CREDIT.matches(it) }.size
    val body = if (creditsAtTop > 0) lines.drop(first + creditsAtTop) else lines
    return body.dropLastWhile { CREDIT.matches(it) }.joinToString("\n")
}

/** For a song it has no words for, KuGou still returns a timed "pure music, please enjoy". */
internal fun isInstrumentalNotice(lrc: String): Boolean {
    val lines =
        lrc
            .lineSequence()
            .map { it.replace(STAMP, "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    return lines.isNotEmpty() && lines.all { INSTRUMENTAL.containsMatchIn(it) }
}

/** The id of the closest Megalobiz sheet that is this recording. */
internal fun megalobizSheetId(
    json: String,
    title: String,
    artist: String,
    durationSeconds: Int?,
): String? =
    Json
        .parseToJsonElement(json)
        .jsonObject["results"]
        ?.jsonArray
        .orEmpty()
        .map { it.jsonObject }
        .filter {
            isSameRecording(
                it.text("title").orEmpty(),
                it.text("artist").orEmpty(),
                it.number("duration"),
                title,
                artist,
                durationSeconds,
                COMMUNITY_DURATION_TOLERANCE_SECONDS,
            )
        }.minByOrNull { distance(it.number("duration"), durationSeconds) }
        ?.text("id")
