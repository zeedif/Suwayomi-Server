package suwayomi.tachidesk.opds

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.http.HttpStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import suwayomi.tachidesk.manga.impl.util.lang.EMPTY
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.opds.constants.OpdsConstants
import suwayomi.tachidesk.opds.impl.Opds2FeedBuilder
import suwayomi.tachidesk.opds.impl.OpdsProgression
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.defineExceptionHandlers
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Opds2Test : ApplicationTest() {
    /** A library manga with a chapter of 12 pages, returning their ids. */
    private fun createChapter(): Pair<Int, Int> =
        transaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[title] = "Manga"
                        it[url] = "manga"
                        it[sourceReference] = LocalSource.ID
                    }.value
            MangaUserTable.insert {
                it[manga] = mangaId
                it[user] = 1
                it[inLibrary] = true
            }
            val chapterId =
                ChapterTable
                    .insertAndGetId {
                        it[url] = "$mangaId-1"
                        it[name] = "Chapter 1"
                        it[sourceOrder] = 1
                        it[pageCount] = 12
                        it[manga] = mangaId
                        it[memo] = JsonObject.EMPTY
                    }.value
            mangaId to chapterId
        }

    private fun progression(
        modified: String,
        page: Int,
    ) = """
        {"modified":"$modified","device":{"id":"urn:uuid:0b6f3c2e-0000-4000-8000-000000000000","name":"Reader"},
        "progression":${(page + 1) / 12.0}}
        """.trimIndent()

    @Test
    fun chaptersArePublications() {
        val (mangaId, chapterId) = createChapter()

        val feed =
            Json
                .parseToJsonElement(
                    runBlocking { Opds2FeedBuilder.getSeriesChaptersFeed(1, Locale.ENGLISH, mangaId, 1, null, null) }!!,
                ).jsonObject
        val links =
            feed["publications"]!!
                .jsonArray
                .single()
                .jsonObject["links"]!!
                .jsonArray
                .map { it.jsonObject }

        assertTrue(feed["links"]!!.jsonArray.any { it.jsonObject["rel"]?.jsonPrimitive?.content == "self" })
        assertFalse("navigation" in feed)
        val manifest = links.single { it["type"]?.jsonPrimitive?.content == OpdsConstants.TYPE_DIVINA }
        assertEquals(OpdsConstants.LINK_REL_ACQUISITION, manifest["rel"]!!.jsonPrimitive.content)
        val stream = links.single { it["rel"]?.jsonPrimitive?.content == OpdsConstants.LINK_REL_PSE_STREAM }
        assertEquals("12", stream["properties"]!!.jsonObject["numberOfItems"]!!.jsonPrimitive.content)
        assertTrue(links.any { it["href"]?.jsonPrimitive?.content == Opds2FeedBuilder.progressionUrl(chapterId) })
    }

    @Test
    fun progressionKeepsTheLatestOne() {
        val (_, chapterId) = createChapter()

        assertNull(runBlocking { OpdsProgression.get(1, chapterId) })

        val (stored, isFirst) = OpdsProgression.put(1, chapterId, progression("2026-01-02T00:00:00Z", 3))
        assertTrue(isFirst)
        assertTrue(stored.references.single().endsWith("/page/3?opds=true"))

        val fetched = runBlocking { OpdsProgression.get(1, chapterId) }!!
        assertEquals("Reader", fetched.device.name)
        assertEquals(4 / 12.0, fetched.progression)

        val older =
            assertFailsWith<OpdsProgression.ProblemException> { OpdsProgression.put(1, chapterId, progression("2026-01-01T00:00:00Z", 5)) }
        assertEquals(HttpStatus.CONFLICT, older.status)

        val invalid = assertFailsWith<OpdsProgression.ProblemException> { OpdsProgression.put(1, chapterId, "{}") }
        assertEquals(HttpStatus.BAD_REQUEST, invalid.status)
    }

    @Test
    fun unauthorizedResponsesCarryTheAuthenticationDocument() {
        val app =
            Javalin
                .create { config ->
                    config.routes.defineExceptionHandlers()
                    config.routes.before { ctx ->
                        ctx.attribute(Attribute.TachideskUser.name, UserType.Visitor)
                        ctx.attribute(Attribute.TachideskBasic.name, false)
                    }
                    config.routes.apiBuilder { path("api/") { OpdsAPI.defineEndpoints() } }
                }.start("127.0.0.1", 0)
        try {
            fun get(path: String) =
                OkHttpClient()
                    .newCall(Request.Builder().url("http://127.0.0.1:${app.port()}/api/opds/v2.0/$path").build())
                    .execute()

            get("chapters/1/progression").use {
                assertEquals(401, it.code)
                assertTrue(it.header("Content-Type")!!.startsWith(OpdsConstants.TYPE_OPDS_AUTHENTICATION))
                assertTrue(OpdsConstants.LINK_REL_AUTH_DOCUMENT in it.header("Link")!!)
                assertTrue(OpdsConstants.AUTH_BASIC in it.body.string())
            }
            get("auth").use { assertEquals(200, it.code) }
        } finally {
            app.stop()
        }
    }

    @AfterEach
    internal fun tearDown() {
        clearTables(ChapterTable, MangaTable)
    }
}
