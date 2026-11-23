package com.luum.michi.app.core.medialist.domain

import com.luum.michi.app.core.mal.domain.toMalStatus
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.ProviderId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A list date as either provider stores it. AniList's `FuzzyDate` and MAL's
 * `YYYY` / `YYYY-MM` / `YYYY-MM-DD` are both partial, so the mirror preserves
 * exactly the detail the source had.
 */
internal data class MirrorDate(
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
)

/**
 * One provider-neutral list entry as the mirror sees it. `score` and `priority`
 * are in the **source** account's scale (`scoreFormat` says which one), so the
 * translator is the single place that normalizes them.
 */
internal data class MirrorEntry(
    val sourceProvider: ProviderId,
    val aniListId: Int,
    val malId: Int?,
    val type: MediaType,
    val status: MediaListStatus?,
    val progress: Int,
    val progressVolumes: Int?,
    val score: Float,
    val scoreFormat: ScoreFormat,
    val repeat: Int,
    val priority: Int,
    val notes: String,
    val isPrivate: Boolean,
    val isRewatching: Boolean,
    val startedAt: MirrorDate?,
    val completedAt: MirrorDate?,
    val updatedAt: Long? = null,
)

/** The values a MAL mirror write needs. Private/hidden/advanced never appear here. */
internal data class MalMirrorValues(
    val status: String,
    val score: Int,
    val progress: Int,
    val progressVolumes: Int?,
    val priority: Int,
    val repeat: Int,
    val comments: String,
    val isRewatching: Boolean,
    val startDate: MirrorDate?,
    val finishDate: MirrorDate?,
)

/** The values an AniList mirror write needs. Private/hidden/advanced are omitted. */
internal data class AniListMirrorValues(
    val status: MediaListStatus,
    val score: Float,
    val progress: Int,
    val progressVolumes: Int?,
    val priority: Int,
    val repeat: Int,
    val notes: String,
    val startedAt: MirrorDate?,
    val completedAt: MirrorDate?,
)

/**
 * Pure field translation between the two list models. It deliberately knows
 * nothing about HTTP, repositories or the outbox.
 */
internal object MirrorTranslator {

    fun toMal(entry: MirrorEntry): MalMirrorValues? {
        val status = entry.status ?: return null
        val isManga = entry.type == MediaType.MANGA
        val malStatus = when (status) {
            MediaListStatus.REPEATING -> if (isManga) "reading" else "watching"
            else -> status.toMalStatus(isManga) ?: return null
        }
        return MalMirrorValues(
            status = malStatus,
            score = toMalScore(entry.score, entry.scoreFormat),
            progress = entry.progress.coerceAtLeast(0),
            progressVolumes = if (isManga) entry.progressVolumes?.coerceAtLeast(0) else null,
            priority = if (entry.sourceProvider == ProviderId.Mal) {
                entry.priority.coerceIn(0, 2)
            } else {
                priorityToMal(entry.priority)
            },
            repeat = entry.repeat.coerceAtLeast(0),
            comments = entry.notes,
            isRewatching = entry.isRewatching || status == MediaListStatus.REPEATING,
            startDate = entry.startedAt,
            finishDate = entry.completedAt,
        )
    }

    fun toAniList(entry: MirrorEntry, targetFormat: ScoreFormat): AniListMirrorValues? {
        val status = entry.status ?: return null
        val targetStatus = if (
            entry.sourceProvider == ProviderId.Mal &&
            (entry.isRewatching || status == MediaListStatus.REPEATING)
        ) {
            MediaListStatus.REPEATING
        } else {
            status
        }
        return AniListMirrorValues(
            status = targetStatus,
            score = toAniListScore(entry.score, entry.scoreFormat, targetFormat),
            progress = entry.progress.coerceAtLeast(0),
            progressVolumes = entry.progressVolumes?.coerceAtLeast(0),
            priority = if (entry.sourceProvider == ProviderId.Mal) {
                priorityToAniList(entry.priority)
            } else {
                entry.priority.coerceIn(0, 5)
            },
            repeat = entry.repeat.coerceAtLeast(0),
            notes = entry.notes,
            startedAt = entry.startedAt,
            completedAt = entry.completedAt,
        )
    }

    /**
     * Normalizes an AniList score to 0–100. 0 is "no score" and stays 0.
     * Smileys use thirds: 1 → 33, 2 → 67, 3 → 100.
     */
    fun normalizeScore(value: Float, format: ScoreFormat): Float {
        if (value <= 0f) return 0f
        return when (format) {
            ScoreFormat.POINT_100 -> value
            ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_10 -> value * 10f
            ScoreFormat.POINT_5_STARS -> value * 20f
            ScoreFormat.POINT_3_SMILEYS -> (value / 3f) * 100f
        }.coerceIn(0f, 100f)
    }

    /** 0–100 → MAL's integer 0–10. A non-zero score never becomes "unrated". */
    fun toMalScore(value: Float, sourceFormat: ScoreFormat): Int {
        val percent = normalizeScore(value, sourceFormat)
        if (percent <= 0f) return 0
        return (percent / 10f).roundToInt().coerceIn(1, 10)
    }

    /**
     * The MAL integers an AniList score may correspond to: the floor and the
     * ceiling of the score on MAL's 0–10 scale. A score that lands exactly on
     * an integer only accepts that integer. So 8.5 accepts 8 and 9, 87/100
     * accepts 8 and 9, and 9/10 only accepts 9. A non-zero score never accepts
     * MAL's 0 ("unrated").
     */
    fun malScoreCandidates(value: Float, format: ScoreFormat): Set<Int> {
        val percent = normalizeScore(value, format)
        if (percent <= 0f) return setOf(0)
        val tenths = percent / 10f
        val rounded = tenths.roundToInt()
        if (abs(tenths - rounded) < SCORE_EPSILON) {
            return setOf(rounded.coerceIn(1, 10))
        }
        return setOf(
            floor(tenths).toInt().coerceIn(1, 10),
            ceil(tenths).toInt().coerceIn(1, 10),
        )
    }

    /**
     * Two scores are the same note when their possible MAL integers overlap.
     * MAL only stores integers, so a decimal AniList score covers both
     * neighbouring integers: 8.5 matches 8 and 9, 87/100 matches 8 and 9.
     */
    fun scoresEquivalent(
        first: Float,
        firstFormat: ScoreFormat,
        second: Float,
        secondFormat: ScoreFormat,
    ): Boolean {
        val candidates = malScoreCandidates(first, firstFormat)
        return malScoreCandidates(second, secondFormat).any { it in candidates }
    }

    /** 0–100 → the target account's AniList format, quantized to its step. */
    fun toAniListScore(
        value: Float,
        sourceFormat: ScoreFormat,
        targetFormat: ScoreFormat,
    ): Float {
        val percent = normalizeScore(value, sourceFormat)
        if (percent <= 0f) return 0f
        return when (targetFormat) {
            ScoreFormat.POINT_100 ->
                percent.roundToInt().coerceIn(1, 100).toFloat()
            ScoreFormat.POINT_10_DECIMAL ->
                ((percent / 5f).roundToInt() * 0.5f).coerceIn(0.5f, 10f)
            ScoreFormat.POINT_10 ->
                (percent / 10f).roundToInt().coerceIn(1, 10).toFloat()
            ScoreFormat.POINT_5_STARS ->
                (percent / 20f).roundToInt().coerceIn(1, 5).toFloat()
            ScoreFormat.POINT_3_SMILEYS -> when {
                percent <= (100f / 3f) -> 1f
                percent <= (200f / 3f) -> 2f
                else -> 3f
            }
        }
    }

    /** AniList's 0–5 priority → MAL's 0–2. */
    fun priorityToMal(priority: Int): Int = when {
        priority <= 0 -> 0
        priority <= 2 -> 1
        else -> 2
    }

    /** MAL's 0–2 priority → AniList's 0/1/3 (MAL has no medium tier). */
    fun priorityToAniList(priority: Int): Int = when {
        priority <= 0 -> 0
        priority == 1 -> 1
        else -> 3
    }

    /** Treats float noise around an exact 0–10 integer as that integer. */
    private const val SCORE_EPSILON = 0.0001f
}
