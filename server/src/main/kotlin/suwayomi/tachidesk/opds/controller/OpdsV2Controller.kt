package suwayomi.tachidesk.opds.controller

import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.i18n.MR
import suwayomi.tachidesk.opds.constants.OpdsConstants
import suwayomi.tachidesk.opds.dto.OpdsMangaFilter
import suwayomi.tachidesk.opds.impl.Opds2FeedBuilder
import suwayomi.tachidesk.opds.impl.Opds2FeedBuilder.BASE_URL
import suwayomi.tachidesk.opds.impl.OpdsProgression
import suwayomi.tachidesk.opds.model.OpdsAuthenticationDocument
import suwayomi.tachidesk.opds.model.OpdsAuthenticationFlow
import suwayomi.tachidesk.opds.model.OpdsProgressionDocument
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.UserPermission
import suwayomi.tachidesk.server.user.hasPermission
import suwayomi.tachidesk.server.user.requireUserWithBasicFallback
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation

/**
 * Controller for the OPDS 2.0 catalog and the OPDS Progression of its
 * publications.
 */
object OpdsV2Controller {
    private fun Context.requireUser() = getAttribute(Attribute.TachideskUser).requireUserWithBasicFallback(this)

    private fun Context.locale() = LocalizationHelper.ctxToLocale(this, queryParam("lang"))

    private fun Context.serve(
        type: String,
        build: suspend () -> String?,
    ) {
        future {
            JavalinSetup.future { build() }.thenApply { body ->
                contentType(type).result(body ?: throw NotFoundResponse())
            }
        }
    }

    private fun Context.serveProgression(action: suspend () -> Pair<HttpStatus, OpdsProgressionDocument?>) {
        future {
            JavalinSetup
                .future {
                    try {
                        val (status, document) = action()
                        Triple(status, OpdsConstants.TYPE_OPDS_PROGRESSION, document?.let { Json.encodeToString(it) })
                    } catch (e: OpdsProgression.ProblemException) {
                        Triple(e.status, OpdsConstants.TYPE_PROBLEM, Json.encodeToString(e.problem))
                    }
                }.thenApply { (status, type, body) ->
                    status(status)
                    // An empty payload means there is no progression yet
                    body?.let { contentType(type).result(it) }
                }
        }
    }

    private fun authenticationDocument(ctx: Context) =
        Json.encodeToString(
            OpdsAuthenticationDocument(
                id = "${ctx.url().substringBefore(BASE_URL)}$BASE_URL/auth",
                title = MR.strings.opds_feeds_root.localized(ctx.locale()),
                authentication = listOf(OpdsAuthenticationFlow(OpdsConstants.AUTH_BASIC)),
            ),
        )

    /**
     * Serves the authentication document with every 401, as required by the
     * Authentication for OPDS and OPDS Progression specifications.
     */
    fun sendAuthenticationDocument(ctx: Context) {
        if (ctx.status() != HttpStatus.UNAUTHORIZED) return
        ctx.header(
            "Link",
            "<$BASE_URL/auth>; rel=\"${OpdsConstants.LINK_REL_AUTH_DOCUMENT}\"; type=\"${OpdsConstants.TYPE_OPDS_AUTHENTICATION}\"",
        )
        ctx.contentType(OpdsConstants.TYPE_OPDS_AUTHENTICATION).result(authenticationDocument(ctx))
    }

    val authentication =
        handler(
            documentWith = { withOperation { summary("OPDS Authentication Document") } },
            behaviorOf = { ctx ->
                ctx.contentType(OpdsConstants.TYPE_OPDS_AUTHENTICATION).result(authenticationDocument(ctx))
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    val rootFeed =
        handler(
            documentWith = { withOperation { summary("OPDS 2.0 Root Feed") } },
            behaviorOf = { ctx ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) { Opds2FeedBuilder.getRootFeed(userId, ctx.locale()) }
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    /**
     * Serves the series in the library with the OPDS 1.2 cross-filters, or
     * the search results.
     */
    val seriesFeed =
        handler(
            documentWith = { withOperation { summary("OPDS 2.0 Library Series Feed") } },
            behaviorOf = { ctx ->
                val userId = ctx.requireUser()
                val query = ctx.queryParam("query")
                val pageNumber = ctx.queryParam("pageNumber")?.toIntOrNull() ?: 1
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    if (query != null) {
                        Opds2FeedBuilder.getSearchFeed(userId, ctx.locale(), query, pageNumber)
                    } else {
                        Opds2FeedBuilder.getLibraryFeed(userId, ctx.locale(), OpdsMangaFilter.fromContext(ctx), pageNumber)
                    }
                }
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    fun libraryNavigationFeed(section: String) =
        handler(
            queryParam<Int?>("pageNumber"),
            documentWith = { withOperation { summary("OPDS 2.0 Library ${section.replaceFirstChar(Char::titlecase)} Feed") } },
            behaviorOf = { ctx, pageNumber ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    Opds2FeedBuilder.getLibraryNavigationFeed(userId, ctx.locale(), section, pageNumber ?: 1)
                }
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    val exploreSourcesFeed =
        handler(
            queryParam<Int?>("pageNumber"),
            documentWith = { withOperation { summary("OPDS 2.0 Sources Feed") } },
            behaviorOf = { ctx, pageNumber ->
                val user = ctx.getAttribute(Attribute.TachideskUser)
                val userId = user.requireUserWithBasicFallback(ctx)
                val includeNsfw = user.hasPermission(UserPermission.ACCESS_NSFW)
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    Opds2FeedBuilder.getExploreSourcesFeed(userId, ctx.locale(), pageNumber ?: 1, includeNsfw)
                }
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    val exploreSourceFeed =
        handler(
            pathParam<Long>("sourceId"),
            queryParam<Int?>("pageNumber"),
            queryParam<String?>("sort"),
            documentWith = { withOperation { summary("OPDS 2.0 Source Series Feed") } },
            behaviorOf = { ctx, sourceId, pageNumber, sort ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    Opds2FeedBuilder.getExploreSourceFeed(userId, ctx.locale(), sourceId, pageNumber ?: 1, sort ?: "popular")
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val seriesChaptersFeed =
        handler(
            pathParam<Int>("seriesId"),
            queryParam<Int?>("pageNumber"),
            queryParam<String?>("sort"),
            queryParam<String?>("filter"),
            documentWith = { withOperation { summary("OPDS 2.0 Series Chapters Feed") } },
            behaviorOf = { ctx, seriesId, pageNumber, sort, filter ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    Opds2FeedBuilder.getSeriesChaptersFeed(userId, ctx.locale(), seriesId, pageNumber ?: 1, sort, filter)
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    fun chaptersFeed(updates: Boolean) =
        handler(
            queryParam<Int?>("pageNumber"),
            documentWith = { withOperation { summary(if (updates) "OPDS 2.0 Library Updates Feed" else "OPDS 2.0 History Feed") } },
            behaviorOf = { ctx, pageNumber ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_FEED) {
                    Opds2FeedBuilder.getChaptersFeed(userId, ctx.locale(), pageNumber ?: 1, updates)
                }
            },
            withResults = { httpCode(HttpStatus.OK) },
        )

    val publication =
        handler(
            pathParam<Int>("chapterId"),
            documentWith = { withOperation { summary("OPDS 2.0 Chapter Publication") } },
            behaviorOf = { ctx, chapterId ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_OPDS2_PUBLICATION) { Opds2FeedBuilder.getPublication(userId, ctx.locale(), chapterId) }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val manifest =
        handler(
            pathParam<Int>("chapterId"),
            documentWith = { withOperation { summary("Divina Manifest of a Chapter") } },
            behaviorOf = { ctx, chapterId ->
                val userId = ctx.requireUser()
                ctx.serve(OpdsConstants.TYPE_DIVINA) { Opds2FeedBuilder.getPublication(userId, ctx.locale(), chapterId, ctx.url()) }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val progression =
        handler(
            pathParam<Int>("chapterId"),
            documentWith = { withOperation { summary("Get the OPDS Progression of a Chapter") } },
            behaviorOf = { ctx, chapterId ->
                val userId = ctx.requireUser()
                ctx.serveProgression { HttpStatus.OK to OpdsProgression.get(userId, chapterId) }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val updateProgression =
        handler(
            pathParam<Int>("chapterId"),
            documentWith = { withOperation { summary("Update the OPDS Progression of a Chapter") } },
            behaviorOf = { ctx, chapterId ->
                val userId = ctx.requireUser()
                val body = ctx.body()
                ctx.serveProgression {
                    val (document, isFirst) = OpdsProgression.put(userId, chapterId, body)
                    (if (isFirst) HttpStatus.CREATED else HttpStatus.OK) to document
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.CREATED)
                httpCode(HttpStatus.BAD_REQUEST)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.CONFLICT)
            },
        )
}
