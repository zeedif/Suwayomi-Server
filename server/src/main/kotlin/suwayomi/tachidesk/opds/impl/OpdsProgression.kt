package suwayomi.tachidesk.opds.impl

import io.javalin.http.HttpStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.impl.sync.KoreaderSyncService
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.opds.model.OpdsProblem
import suwayomi.tachidesk.opds.model.OpdsProgressionDevice
import suwayomi.tachidesk.opds.model.OpdsProgressionDocument
import suwayomi.tachidesk.opds.util.OpdsDateUtil
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/**
 * OPDS Progression of the chapters, which keeps the last progression with the
 * device that sent it and leaves the conflicts to the clients. The progress
 * from KOReader Sync is combined as its conflict strategies dictate.
 */
object OpdsProgression {
    private val json = Json { ignoreUnknownKeys = true }

    private val serverDevice = OpdsProgressionDevice("urn:suwayomi:server", "Suwayomi-Server")

    class ProblemException(
        val status: HttpStatus,
        val problem: OpdsProblem,
    ) : Exception(problem.title)

    private val invalidPayload =
        ProblemException(
            HttpStatus.BAD_REQUEST,
            OpdsProblem(
                "https://registry.opds.io/error#progression-invalid-payload",
                "Progression could not be updated due to an invalid payload.",
            ),
        )

    private fun getChapter(
        userId: Int,
        chapterId: Int,
    ): ResultRow =
        transaction {
            ChapterTable
                .getWithUserData(userId)
                .selectAll()
                .where { ChapterTable.id eq chapterId }
                .firstOrNull()
        } ?: throw ProblemException(HttpStatus.NOT_FOUND, OpdsProblem("about:blank", "Not Found"))

    private fun ResultRow.toDocument(
        lastPageRead: Int,
        lastReadAt: Long,
        device: OpdsProgressionDevice,
    ) = OpdsProgressionDocument(
        title = this[ChapterTable.name],
        modified = OpdsDateUtil.formatEpochMillisForOpds(lastReadAt * 1000),
        device = device,
        progression = ((lastPageRead + 1).toDouble() / this[ChapterTable.pageCount]).coerceIn(0.0, 1.0),
        references = listOf(OpdsEntryBuilder.pageUrl(this[ChapterTable.manga].value, this[ChapterTable.sourceOrder], "$lastPageRead")),
    )

    private fun saveProgress(
        userId: Int,
        chapter: ResultRow,
        lastPageRead: Int,
        lastReadAt: Long,
        device: OpdsProgressionDevice,
    ) {
        transaction {
            ChapterUserTable.upsert(ChapterUserTable.chapter, ChapterUserTable.user) {
                it[user] = userId
                it[this.chapter] = chapter[ChapterTable.id]
                it[this.lastPageRead] = lastPageRead
                it[this.lastReadAt] = lastReadAt
                if (lastPageRead == chapter[ChapterTable.pageCount] - 1) it[isRead] = true
                it[progressDeviceId] = device.id
                it[progressDeviceName] = device.name
                it[progressDeviceAt] = lastReadAt
            }
        }
    }

    /**
     * @return null if there is no progression yet.
     */
    suspend fun get(
        userId: Int,
        chapterId: Int,
    ): OpdsProgressionDocument? {
        val chapter = getChapter(userId, chapterId)
        if (chapter[ChapterTable.pageCount] <= 0) return null
        val lastReadAt = chapter.getOrNull(ChapterUserTable.lastReadAt) ?: 0
        val local =
            if (lastReadAt > 0) {
                // The device applies until someone else changes the progress
                val device =
                    chapter.getOrNull(ChapterUserTable.progressDeviceId)?.takeIf {
                        chapter.getOrNull(ChapterUserTable.progressDeviceAt) == lastReadAt
                    }
                chapter.toDocument(
                    chapter.getOrNull(ChapterUserTable.lastPageRead) ?: 0,
                    lastReadAt,
                    device?.let { OpdsProgressionDevice(it, chapter.getOrNull(ChapterUserTable.progressDeviceName) ?: it) } ?: serverDevice,
                )
            } else {
                null
            }
        val remote = KoreaderSyncService.checkAndPullProgress(userId, chapterId) ?: return local
        val remoteDevice = OpdsProgressionDevice("urn:uuid:${UUID.nameUUIDFromBytes(remote.device.toByteArray())}", remote.device)
        if (remote.shouldUpdate) saveProgress(userId, chapter, remote.pageRead, remote.timestamp, remoteDevice)
        // On a conflict the newer progression is returned for the client to
        // prompt, and kept once it sends it back
        return if (remote.shouldUpdate || (remote.isConflict && remote.timestamp > lastReadAt)) {
            chapter.toDocument(remote.pageRead, remote.timestamp, remoteDevice)
        } else {
            local
        }
    }

    /**
     * @return the stored progression and whether it is the first one.
     */
    fun put(
        userId: Int,
        chapterId: Int,
        body: String,
    ): Pair<OpdsProgressionDocument, Boolean> {
        val progression = runCatching { json.decodeFromString<OpdsProgressionDocument>(body) }.getOrNull()
        val modified = progression?.let { runCatching { Instant.parse(it.modified) }.getOrNull() }
        if (progression == null || modified == null || progression.progression !in 0.0..1.0 ||
            !runCatching { URI(progression.device.id).isAbsolute }.getOrDefault(false) || progression.device.name.isBlank()
        ) {
            throw invalidPayload
        }
        val chapter = getChapter(userId, chapterId)
        val pageCount = chapter[ChapterTable.pageCount]
        if (pageCount <= 0) throw invalidPayload
        val pageReference = Regex("/manga/${chapter[ChapterTable.manga].value}/chapter/${chapter[ChapterTable.sourceOrder]}/page/(\\d+)")
        val lastPageRead =
            progression.references.firstNotNullOfOrNull { reference ->
                pageReference
                    .find(reference)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.takeIf { it < pageCount }
            } ?: (ceil(progression.progression * pageCount - 1e-6).toInt() - 1).coerceIn(0, pageCount - 1)
        // A device clock ahead of the server would block the progressions sent
        // from the others
        val lastReadAt = minOf(modified.epochSecond, Instant.now().epochSecond)
        val storedLastReadAt = chapter.getOrNull(ChapterUserTable.lastReadAt) ?: 0
        if (storedLastReadAt > lastReadAt) {
            throw ProblemException(
                HttpStatus.CONFLICT,
                OpdsProblem("https://registry.opds.io/error#progression-date", "A more recent progression point is already available."),
            )
        }
        saveProgress(userId, chapter, lastPageRead, lastReadAt, progression.device)
        KoreaderSyncService.schedulePushProgress(userId, chapterId)
        return chapter.toDocument(lastPageRead, lastReadAt, progression.device) to (storedLastReadAt == 0L)
    }
}
