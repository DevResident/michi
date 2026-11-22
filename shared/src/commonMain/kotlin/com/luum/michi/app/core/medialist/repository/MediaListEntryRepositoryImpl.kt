package com.luum.michi.app.core.medialist.repository

import com.luum.michi.app.core.favourite.domain.FavouriteRepository
import com.luum.michi.app.core.favourite.domain.FavouriteTarget
import com.luum.michi.app.core.media.domain.EntityRef
import com.luum.michi.app.core.media.domain.MediaType
import com.luum.michi.app.core.media.domain.requireAniListId
import com.luum.michi.app.core.media.repository.dto.FuzzyDateDto
import com.luum.michi.app.core.media.repository.dto.MediaViewerListEntryDto
import com.luum.michi.app.core.media.repository.dto.toMillisOrNull
import com.luum.michi.app.core.medialist.domain.MediaListEntryRepository
import com.luum.michi.app.core.medialist.domain.MediaListViewerEntry
import com.luum.michi.app.core.medialist.domain.MediaListStatus
import com.luum.michi.app.core.medialist.domain.MirrorDate
import com.luum.michi.app.core.medialist.domain.MirrorField
import com.luum.michi.app.core.medialist.domain.AniListMirrorValues
import com.luum.michi.app.core.medialist.domain.MirrorEntry
import com.luum.michi.app.core.medialist.domain.parseMediaListStatus
import com.luum.michi.app.core.medialist.domain.toApiValue
import com.luum.michi.app.core.model.millisToCalendarParts
import com.luum.michi.app.core.network.domain.AniListGraphQLClient
import com.luum.michi.app.core.network.domain.AniListGraphQLRequest
import com.luum.michi.app.core.network.repository.AniListJson
import com.luum.michi.app.core.network.domain.NetworkError
import com.luum.michi.app.core.network.domain.NetworkResult
import com.luum.michi.app.core.network.domain.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val SaveMediaListEntryMutation = """
mutation SaveMediaListEntry(
  ${'$'}mediaId: Int!,
  ${'$'}status: MediaListStatus,
  ${'$'}progress: Int,
  ${'$'}progressVolumes: Int,
  ${'$'}score: Float,
  ${'$'}advancedScores: [Float],
  ${'$'}notes: String,
  ${'$'}repeat: Int,
  ${'$'}priority: Int,
  ${'$'}private: Boolean,
  ${'$'}hiddenFromStatusLists: Boolean,
  ${'$'}startedAt: FuzzyDateInput,
  ${'$'}completedAt: FuzzyDateInput
) {
  SaveMediaListEntry(
    mediaId: ${'$'}mediaId,
    status: ${'$'}status,
    progress: ${'$'}progress,
    progressVolumes: ${'$'}progressVolumes,
    score: ${'$'}score,
    advancedScores: ${'$'}advancedScores,
    notes: ${'$'}notes,
    repeat: ${'$'}repeat,
    priority: ${'$'}priority,
    private: ${'$'}private,
    hiddenFromStatusLists: ${'$'}hiddenFromStatusLists,
    startedAt: ${'$'}startedAt,
    completedAt: ${'$'}completedAt
  ) {
    id status progress progressVolumes score advancedScores notes repeat priority private hiddenFromStatusLists
    startedAt { year month day }
    completedAt { year month day }
  }
}
"""

private fun fuzzyDateInput(millis: Long?): JsonElement {
    if (millis == null) {
        return buildJsonObject {
            put("year", JsonNull)
            put("month", JsonNull)
            put("day", JsonNull)
        }
    }
    val parts = millisToCalendarParts(millis)
    return buildJsonObject {
        put("year", JsonPrimitive(parts.year))
        put("month", JsonPrimitive(parts.month))
        put("day", JsonPrimitive(parts.day))
    }
}

@Serializable
private data class SaveMediaListEntryResponse(
    @SerialName("SaveMediaListEntry") val entry: MediaViewerListEntryDto? = null,
)

private fun FuzzyDateDto?.toMirrorDate(): MirrorDate? {
    val year = this?.year ?: return null
    return MirrorDate(year = year, month = month, day = day)
}

private const val SaveMediaListProgressMutation = """
mutation SaveMediaListProgress(
  ${'$'}mediaId: Int!,
  ${'$'}progress: Int,
  ${'$'}progressVolumes: Int,
  ${'$'}status: MediaListStatus
) {
  SaveMediaListEntry(
    mediaId: ${'$'}mediaId,
    progress: ${'$'}progress,
    progressVolumes: ${'$'}progressVolumes,
    status: ${'$'}status
  ) {
    id status progress progressVolumes score advancedScores notes repeat priority private hiddenFromStatusLists
    startedAt { year month day }
    completedAt { year month day }
  }
}
"""

@Serializable
private data class SaveProgressResponse(
    @SerialName("SaveMediaListEntry") val entry: MediaViewerListEntryDto? = null,
)

private const val DeleteMediaListEntryMutation = """
mutation DeleteMediaListEntry(${'$'}id: Int!) {
  DeleteMediaListEntry(id: ${'$'}id) { deleted }
}
"""

@Serializable
private data class DeleteMediaListEntryResponse(
    @SerialName("DeleteMediaListEntry") val payload: JsonElement? = null,
)

private const val SaveMirrorMutation = """
mutation MirrorMediaListEntry(
  ${'$'}mediaId: Int!,
  ${'$'}status: MediaListStatus,
  ${'$'}progress: Int,
  ${'$'}progressVolumes: Int,
  ${'$'}score: Float,
  ${'$'}notes: String,
  ${'$'}repeat: Int,
  ${'$'}priority: Int,
  ${'$'}startedAt: FuzzyDateInput,
  ${'$'}completedAt: FuzzyDateInput
) {
  SaveMediaListEntry(
    mediaId: ${'$'}mediaId,
    status: ${'$'}status,
    progress: ${'$'}progress,
    progressVolumes: ${'$'}progressVolumes,
    score: ${'$'}score,
    notes: ${'$'}notes,
    repeat: ${'$'}repeat,
    priority: ${'$'}priority,
    startedAt: ${'$'}startedAt,
    completedAt: ${'$'}completedAt
  ) {
    id
  }
}
"""

@Serializable
private data class SaveMirrorResponse(
    @SerialName("SaveMediaListEntry") val entry: JsonElement? = null,
)

private fun mirrorDateInput(date: MirrorDate?): JsonElement = buildJsonObject {
    put("year", date?.year?.let(::JsonPrimitive) ?: JsonNull)
    put("month", date?.month?.let(::JsonPrimitive) ?: JsonNull)
    put("day", date?.day?.let(::JsonPrimitive) ?: JsonNull)
}

internal class MediaListEntryRepositoryImpl(
    private val graphQLClient: AniListGraphQLClient,
    private val favouriteRepository: FavouriteRepository,
) : MediaListEntryRepository {

    override suspend fun saveEntry(
        mediaRef: EntityRef,
        status: MediaListStatus,
        progress: Int,
        progressVolumes: Int?,
        score: Float,
        advancedScores: List<Float>?,
        notes: String,
        repeat: Int,
        priority: Int,
        isPrivate: Boolean,
        hiddenFromStatusLists: Boolean,
        startedAtMillis: Long?,
        completedAtMillis: Long?,
        mediaType: MediaType,
        changedFields: Set<MirrorField>?,
    ): NetworkResult<MediaListViewerEntry> {
        val mediaId = mediaRef.requireAniListId()
        val variables = buildMap<String, JsonElement> {
            put("mediaId", JsonPrimitive(mediaId))
            put("status", JsonPrimitive(status.toApiValue()))
            put("progress", JsonPrimitive(progress))
            if (progressVolumes != null) put("progressVolumes", JsonPrimitive(progressVolumes))
            put("score", JsonPrimitive(score))
            if (advancedScores != null) {
                put("advancedScores", JsonArray(advancedScores.map { JsonPrimitive(it) }))
            }
            put("notes", JsonPrimitive(notes))
            put("repeat", JsonPrimitive(repeat))
            put("priority", JsonPrimitive(priority))
            put("private", JsonPrimitive(isPrivate))
            put("hiddenFromStatusLists", JsonPrimitive(hiddenFromStatusLists))
            put("startedAt", fuzzyDateInput(startedAtMillis))
            put("completedAt", fuzzyDateInput(completedAtMillis))
        }
        val request = AniListGraphQLRequest(
            query = SaveMediaListEntryMutation,
            variables = JsonObject(variables),
            operationName = "SaveMediaListEntry",
        )

        return when (
            val result = graphQLClient.execute(request) { dataJson ->
                AniListJson.decodeFromJsonElement(SaveMediaListEntryResponse.serializer(), dataJson)
            }
        ) {
            is NetworkResult.Success -> {
                val entry = result.value.entry
                    ?: return NetworkResult.Failure(
                        NetworkError.GraphQL(listOf("SaveMediaListEntry returned no entry")),
                    )
                val parsedStatus = parseMediaListStatus(entry.status) ?: status
                NetworkResult.Success(
                    MediaListViewerEntry(
                        id = entry.id,
                        status = parsedStatus,
                        progress = entry.progress ?: progress,
                        progressVolumes = entry.progressVolumes ?: progressVolumes,
                        score = entry.score?.toFloat() ?: score,
                        advancedScores = entry.advancedScores ?: emptyMap(),
                        notes = entry.notes.orEmpty(),
                        repeat = entry.repeat ?: repeat,
                        priority = entry.priority ?: priority,
                        isPrivate = entry.isPrivate ?: isPrivate,
                        hiddenFromStatusLists = entry.hiddenFromStatusLists ?: hiddenFromStatusLists,
                        startedAtMillis = entry.startedAt?.toMillisOrNull() ?: startedAtMillis,
                        completedAtMillis = entry.completedAt?.toMillisOrNull() ?: completedAtMillis,
                        isRewatching = parsedStatus == MediaListStatus.REPEATING,
                        startedAtDate = entry.startedAt.toMirrorDate(),
                        completedAtDate = entry.completedAt.toMirrorDate(),
                    ),
                )
            }
            is NetworkResult.Failure -> result
        }
    }

    override suspend fun saveProgress(
        mediaRef: EntityRef,
        progress: Int?,
        status: MediaListStatus?,
        progressVolumes: Int?,
        mediaType: MediaType,
    ): NetworkResult<MediaListViewerEntry> {
        val mediaId = mediaRef.requireAniListId()
        val variables = buildMap<String, JsonElement> {
            put("mediaId", JsonPrimitive(mediaId))
            if (progress != null) {
                put("progress", JsonPrimitive(progress))
            }
            if (status != null) {
                put("status", JsonPrimitive(status.toApiValue()))
            }
            if (progressVolumes != null) {
                put("progressVolumes", JsonPrimitive(progressVolumes))
            }
        }
        val request = AniListGraphQLRequest(
            query = SaveMediaListProgressMutation,
            variables = JsonObject(variables),
            operationName = "SaveMediaListProgress",
        )
        return when (
            val result = graphQLClient.execute(request) { dataJson ->
                AniListJson.decodeFromJsonElement(SaveProgressResponse.serializer(), dataJson)
            }
        ) {
            is NetworkResult.Success -> {
                val entry = result.value.entry
                    ?: return NetworkResult.Failure(
                        NetworkError.GraphQL(listOf("SaveMediaListEntry returned no entry")),
                    )
                val parsedStatus = parseMediaListStatus(entry.status) ?: status
                NetworkResult.Success(
                    MediaListViewerEntry(
                        id = entry.id,
                        status = parsedStatus,
                        progress = entry.progress ?: progress ?: 0,
                        progressVolumes = entry.progressVolumes ?: progressVolumes,
                        score = entry.score?.toFloat() ?: 0f,
                        advancedScores = entry.advancedScores ?: emptyMap(),
                        notes = entry.notes.orEmpty(),
                        repeat = entry.repeat ?: 0,
                        priority = entry.priority ?: 0,
                        isPrivate = entry.isPrivate ?: false,
                        hiddenFromStatusLists = entry.hiddenFromStatusLists ?: false,
                        startedAtMillis = entry.startedAt?.toMillisOrNull(),
                        completedAtMillis = entry.completedAt?.toMillisOrNull(),
                        isRewatching = parsedStatus == MediaListStatus.REPEATING,
                        startedAtDate = entry.startedAt.toMirrorDate(),
                        completedAtDate = entry.completedAt.toMirrorDate(),
                    ),
                )
            }
            is NetworkResult.Failure -> result
        }
    }

    /**
     * Mirror-only mutation: writes just the selected fields and deliberately
     * omits private, hidden-from-status-lists and advanced scores.
     */
    suspend fun saveMirror(
        entry: MirrorEntry,
        values: AniListMirrorValues,
        fields: Set<MirrorField>,
    ): NetworkResult<Unit> {
        if (fields.isEmpty()) return NetworkResult.Success(Unit)
        val variables = buildMap<String, JsonElement> {
            put("mediaId", JsonPrimitive(entry.aniListId))
            if (MirrorField.STATUS in fields) {
                put("status", JsonPrimitive(values.status.toApiValue()))
            }
            if (MirrorField.PROGRESS in fields) {
                put("progress", JsonPrimitive(values.progress))
            }
            if (MirrorField.PROGRESS_VOLUMES in fields && entry.type == MediaType.MANGA) {
                put("progressVolumes", JsonPrimitive(values.progressVolumes ?: 0))
            }
            if (MirrorField.SCORE in fields) {
                put("score", JsonPrimitive(values.score))
            }
            if (MirrorField.NOTES in fields) {
                put("notes", JsonPrimitive(values.notes))
            }
            if (MirrorField.REPEAT in fields) {
                put("repeat", JsonPrimitive(values.repeat))
            }
            if (MirrorField.PRIORITY in fields) {
                put("priority", JsonPrimitive(values.priority))
            }
            if (MirrorField.STARTED_AT in fields) {
                put("startedAt", mirrorDateInput(values.startedAt))
            }
            if (MirrorField.COMPLETED_AT in fields) {
                put("completedAt", mirrorDateInput(values.completedAt))
            }
        }
        val request = AniListGraphQLRequest(
            query = SaveMirrorMutation,
            variables = JsonObject(variables),
            operationName = "MirrorMediaListEntry",
        )
        return graphQLClient.execute(request) { dataJson ->
            AniListJson.decodeFromJsonElement(SaveMirrorResponse.serializer(), dataJson)
        }.map { }
    }

    override suspend fun toggleFavourite(
        mediaRef: EntityRef,
        isManga: Boolean,
    ): NetworkResult<Unit> {
        val mediaId = mediaRef.requireAniListId()
        return favouriteRepository.toggleFavourite(
            if (isManga) FavouriteTarget.Manga(mediaId) else FavouriteTarget.Anime(mediaId),
        )
    }

    override suspend fun deleteEntry(entryId: Int, mediaRef: EntityRef): NetworkResult<Unit> {
        val variables = buildMap<String, JsonElement> {
            put("id", JsonPrimitive(entryId))
        }
        val request = AniListGraphQLRequest(
            query = DeleteMediaListEntryMutation,
            variables = JsonObject(variables),
            operationName = "DeleteMediaListEntry",
        )
        return graphQLClient.execute(request) { dataJson ->
            AniListJson.decodeFromJsonElement(DeleteMediaListEntryResponse.serializer(), dataJson)
        }.map { }
    }
}
