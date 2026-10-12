package suwayomi.tachidesk.opds.impl

import kotlinx.serialization.json.Json
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.i18n.MR
import suwayomi.tachidesk.opds.constants.OpdsConstants
import suwayomi.tachidesk.opds.dto.OpdsChapterListAcqEntry
import suwayomi.tachidesk.opds.dto.OpdsFacet
import suwayomi.tachidesk.opds.dto.OpdsMangaAcqEntry
import suwayomi.tachidesk.opds.dto.OpdsMangaDetails
import suwayomi.tachidesk.opds.dto.OpdsMangaFilter
import suwayomi.tachidesk.opds.dto.OpdsSearchCriteria
import suwayomi.tachidesk.opds.model.Opds2BelongsTo
import suwayomi.tachidesk.opds.model.Opds2Collection
import suwayomi.tachidesk.opds.model.Opds2Facet
import suwayomi.tachidesk.opds.model.Opds2Feed
import suwayomi.tachidesk.opds.model.Opds2FeedMetadata
import suwayomi.tachidesk.opds.model.Opds2Link
import suwayomi.tachidesk.opds.model.Opds2LinkProperties
import suwayomi.tachidesk.opds.model.Opds2Publication
import suwayomi.tachidesk.opds.model.Opds2PublicationMetadata
import suwayomi.tachidesk.opds.repository.ChapterRepository
import suwayomi.tachidesk.opds.repository.MangaRepository
import suwayomi.tachidesk.opds.repository.NavigationRepository
import suwayomi.tachidesk.opds.util.OpdsDateUtil
import suwayomi.tachidesk.opds.util.OpdsStringUtil.encodeForOpdsURL
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.settings.userConfig
import suwayomi.tachidesk.server.settings.value
import java.util.Locale

/**
 * Builds the OPDS 2.0 catalog from the same data as the OPDS 1.2 one, with
 * chapters as publications readable through a Divina manifest.
 */
object Opds2FeedBuilder {
    const val BASE_URL = "/api/opds/v2.0"

    fun progressionUrl(chapterId: Int) = "$BASE_URL/chapters/$chapterId/progression"

    private fun String.withPage(pageNum: Int) = "$this${if ('?' in this) '&' else '?'}pageNumber=$pageNum"

    private fun navigationLink(
        href: String,
        title: String,
        count: Long? = null,
    ) = Opds2Link(
        href,
        OpdsConstants.TYPE_OPDS2_FEED,
        OpdsConstants.LINK_REL_SUBSECTION,
        title,
        properties = count?.let { Opds2LinkProperties(numberOfItems = it) },
    )

    private fun seriesLink(
        manga: OpdsMangaAcqEntry,
        languageTag: String,
    ) = navigationLink("$BASE_URL/series/${manga.id}/chapters?lang=$languageTag", manga.title)

    private fun buildFeed(
        userId: Int,
        locale: Locale,
        title: String,
        selfUrl: String,
        pageNum: Int? = null,
        total: Long? = null,
        hasNextPage: Boolean? = null,
        facets: List<OpdsFacet> = emptyList(),
        navigation: List<Opds2Link> = emptyList(),
        publications: List<Opds2Publication> = emptyList(),
    ): String {
        val languageTag = locale.toLanguageTag()
        val startUrl = "$BASE_URL?lang=$languageTag"
        val itemsPerPage = userConfig.opdsItemsPerPage.value(userId)
        val lastPage = total?.let { ((it + itemsPerPage - 1) / itemsPerPage).toInt() }
        val feed =
            Opds2Feed(
                metadata = Opds2FeedMetadata(title, total, itemsPerPage.takeIf { pageNum != null }, pageNum),
                links =
                    buildList {
                        add(Opds2Link(pageNum?.let { selfUrl.withPage(it) } ?: selfUrl, OpdsConstants.TYPE_OPDS2_FEED, "self"))
                        add(Opds2Link(startUrl, OpdsConstants.TYPE_OPDS2_FEED, OpdsConstants.LINK_REL_START))
                        add(
                            Opds2Link(
                                "$BASE_URL/library/series?lang=$languageTag{&query}",
                                OpdsConstants.TYPE_OPDS2_FEED,
                                OpdsConstants.LINK_REL_SEARCH,
                                templated = true,
                            ),
                        )
                        if (pageNum != null) {
                            if (pageNum > 1) {
                                add(Opds2Link(selfUrl.withPage(1), OpdsConstants.TYPE_OPDS2_FEED, OpdsConstants.LINK_REL_FIRST))
                                add(Opds2Link(selfUrl.withPage(pageNum - 1), OpdsConstants.TYPE_OPDS2_FEED, OpdsConstants.LINK_REL_PREV))
                            }
                            if (hasNextPage ?: (pageNum < (lastPage ?: 0))) {
                                add(Opds2Link(selfUrl.withPage(pageNum + 1), OpdsConstants.TYPE_OPDS2_FEED, OpdsConstants.LINK_REL_NEXT))
                            }
                            if (lastPage != null && lastPage > 1) {
                                add(Opds2Link(selfUrl.withPage(lastPage), OpdsConstants.TYPE_OPDS2_FEED, OpdsConstants.LINK_REL_LAST))
                            }
                        }
                    },
                facets =
                    facets.groupBy { it.group }.map { (group, groupFacets) ->
                        Opds2Facet(
                            Opds2FeedMetadata(group),
                            groupFacets.map {
                                Opds2Link(
                                    it.href,
                                    OpdsConstants.TYPE_OPDS2_FEED,
                                    "self".takeIf { _ -> it.isActive },
                                    it.title,
                                    properties = it.count?.let { count -> Opds2LinkProperties(numberOfItems = count) },
                                )
                            },
                        )
                    },
                // A feed needs a collection with items even when it is empty
                navigation =
                    navigation.ifEmpty {
                        listOfNotNull(
                            navigationLink(startUrl, MR.strings.opds_linktitle_catalog_root.localized(locale))
                                .takeIf { publications.isEmpty() },
                        )
                    },
                publications = publications,
            )
        return Json.encodeToString(feed)
    }

    private fun progressionLink(chapterId: Int) =
        Opds2Link(
            progressionUrl(chapterId),
            OpdsConstants.TYPE_OPDS_PROGRESSION,
            OpdsConstants.LINK_REL_PROGRESSION,
            properties =
                Opds2LinkProperties(authenticate = Opds2Link("$BASE_URL/auth", OpdsConstants.TYPE_OPDS_AUTHENTICATION))
                    .takeIf { serverConfig.authMode.value != AuthMode.NONE },
        )

    private fun publicationMetadata(
        chapter: OpdsChapterListAcqEntry,
        manga: OpdsMangaDetails,
        locale: Locale,
    ) = Opds2PublicationMetadata(
        type = "http://schema.org/ComicIssue",
        identifier = "urn:suwayomi:chapter:${chapter.id}",
        title = OpdsEntryBuilder.resolveChapterTitle(chapter.name, chapter.chapterNumber, chapter.sourceOrder, manga.totalChapters, locale),
        author = manga.author?.takeIf { it.isNotBlank() },
        translator = chapter.scanlator?.takeIf { it.isNotBlank() },
        published = chapter.uploadDate.takeIf { it > 0 }?.let { OpdsDateUtil.formatEpochMillisForOpds(it) },
        numberOfPages = chapter.pageCount.takeIf { it > 0 },
        belongsTo =
            Opds2BelongsTo(
                Opds2Collection(
                    manga.title,
                    chapter.chapterNumber.takeIf { it > 0 },
                    listOf(
                        Opds2Link("$BASE_URL/series/${manga.id}/chapters?lang=${locale.toLanguageTag()}", OpdsConstants.TYPE_OPDS2_FEED),
                    ),
                ),
            ),
    )

    private fun publication(
        userId: Int,
        locale: Locale,
        chapter: OpdsChapterListAcqEntry,
        manga: OpdsMangaDetails,
    ): Opds2Publication {
        val chapterUrl = "$BASE_URL/chapters/${chapter.id}"
        return Opds2Publication(
            metadata = publicationMetadata(chapter, manga, locale),
            links =
                buildList {
                    add(Opds2Link(chapterUrl, OpdsConstants.TYPE_OPDS2_PUBLICATION, "self"))
                    add(Opds2Link("$chapterUrl/manifest", OpdsConstants.TYPE_DIVINA, OpdsConstants.LINK_REL_ACQUISITION))
                    if (chapter.downloaded) {
                        add(
                            Opds2Link(
                                OpdsEntryBuilder.cbzUrl(userId, chapter.id),
                                userConfig.opdsCbzMimetype.value(userId).mediaType,
                                OpdsConstants.LINK_REL_ACQUISITION,
                            ),
                        )
                    }
                    if (chapter.pageCount > 0) {
                        add(
                            Opds2Link(
                                OpdsEntryBuilder.pageUrl(manga.id, chapter.sourceOrder, "{pageNumber}") +
                                    "&updateProgress=${userConfig.opdsEnablePageReadProgress.value(userId)}",
                                OpdsConstants.TYPE_IMAGE_JPEG,
                                OpdsConstants.LINK_REL_PSE_STREAM,
                                templated = true,
                                properties = Opds2LinkProperties(numberOfItems = chapter.pageCount.toLong()),
                            ),
                        )
                    }
                    add(progressionLink(chapter.id))
                },
            images =
                listOfNotNull(
                    manga.thumbnailUrl?.let { Opds2Link(OpdsEntryBuilder.thumbnailUrl(manga.id), OpdsConstants.TYPE_IMAGE_JPEG) },
                ),
        )
    }

    fun getRootFeed(
        userId: Int,
        locale: Locale,
    ): String {
        val languageTag = locale.toLanguageTag()
        return buildFeed(
            userId,
            locale,
            MR.strings.opds_feeds_root.localized(locale),
            "$BASE_URL?lang=$languageTag",
            navigation =
                NavigationRepository.getRootNavigationItems(locale).map {
                    navigationLink("$BASE_URL/${it.id}?lang=$languageTag", it.title)
                        .copy(rel = if (it.id == "library-updates") OpdsConstants.LINK_REL_SORT_NEW else OpdsConstants.LINK_REL_SUBSECTION)
                },
        )
    }

    fun getLibraryFeed(
        userId: Int,
        locale: Locale,
        criteria: OpdsMangaFilter,
        pageNum: Int,
    ): String {
        val result = MangaRepository.getLibraryManga(userId, criteria, pageNum, criteria.sort, criteria.filter)
        val languageTag = locale.toLanguageTag()
        val queryParams =
            listOfNotNull(
                criteria.toCrossFilterQueryParameters().takeIf { it.isNotEmpty() },
                criteria.sort?.let { "sort=$it" },
                criteria.filter?.let { "filter=$it" },
                "lang=$languageTag",
            ).joinToString("&")
        return buildFeed(
            userId,
            locale,
            MR.strings.opds_feeds_all_series_in_library_title.localized(locale),
            "$BASE_URL/library/series?$queryParams",
            pageNum,
            result.totalCount,
            facets = OpdsFacetBuilder.getLibraryFacets(userId, BASE_URL, locale, criteria),
            navigation = result.mangaEntries.map { seriesLink(it, languageTag) },
        )
    }

    fun getSearchFeed(
        userId: Int,
        locale: Locale,
        query: String,
        pageNum: Int,
    ): String {
        val languageTag = locale.toLanguageTag()
        val (mangaEntries, total) = MangaRepository.findMangaByCriteria(userId, OpdsSearchCriteria(query), pageNum)
        return buildFeed(
            userId,
            locale,
            MR.strings.opds_feeds_search_results_title.localized(locale),
            "$BASE_URL/library/series?query=${query.encodeForOpdsURL()}&lang=$languageTag",
            pageNum,
            total,
            navigation = mangaEntries.map { seriesLink(it, languageTag) },
        )
    }

    /**
     * Returns a navigation feed of the library sources, categories, genres,
     * statuses or languages, each linking to the library filtered by it.
     */
    fun getLibraryNavigationFeed(
        userId: Int,
        locale: Locale,
        section: String,
        pageNum: Int,
    ): String {
        val languageTag = locale.toLanguageTag()

        fun filterLink(
            filter: String,
            title: String,
            count: Long?,
        ) = navigationLink("$BASE_URL/library/series?$filter&lang=$languageTag", title, count)

        val (navigation, total) =
            when (section) {
                "sources" -> {
                    NavigationRepository.getLibrarySources(userId, pageNum).let { (sources, total) ->
                        sources.map { filterLink("source_id=${it.id}", it.name, it.mangaCount) } to total
                    }
                }

                "categories" -> {
                    NavigationRepository.getCategories(userId, pageNum).let { (categories, total) ->
                        categories.map { filterLink("category_id=${it.id}", it.name, it.mangaCount) } to total
                    }
                }

                "genres" -> {
                    NavigationRepository.getGenres(userId, locale, pageNum).let { (genres, total) ->
                        genres.map { filterLink("genre=${it.title.encodeForOpdsURL()}", it.title, it.mangaCount) } to total
                    }
                }

                "statuses" -> {
                    NavigationRepository.getStatuses(userId, locale, pageNum).let { (statuses, total) ->
                        statuses.map { filterLink("status_id=${it.id}", it.title, it.mangaCount) } to total
                    }
                }

                else -> {
                    NavigationRepository.getContentLanguages(userId, locale, pageNum).let { (languages, total) ->
                        languages.map { filterLink("lang_code=${it.id}", it.title, it.mangaCount) } to total
                    }
                }
            }
        return buildFeed(
            userId,
            locale,
            NavigationRepository.librarySectionDetails
                .getValue(section)
                .second
                .localized(locale),
            "$BASE_URL/library/$section?lang=$languageTag",
            pageNum,
            total,
            navigation = navigation,
        )
    }

    fun getExploreSourcesFeed(
        userId: Int,
        locale: Locale,
        pageNum: Int,
        includeNsfw: Boolean,
    ): String {
        val languageTag = locale.toLanguageTag()
        val (sources, total) = NavigationRepository.getExploreSources(userId, pageNum, includeNsfw)
        return buildFeed(
            userId,
            locale,
            MR.strings.opds_feeds_sources_title.localized(locale),
            "$BASE_URL/explore?lang=$languageTag",
            pageNum,
            total,
            navigation = sources.map { navigationLink("$BASE_URL/explore/source/${it.id}?sort=popular&lang=$languageTag", it.name) },
        )
    }

    suspend fun getExploreSourceFeed(
        userId: Int,
        locale: Locale,
        sourceId: Long,
        pageNum: Int,
        sort: String,
    ): String {
        val languageTag = locale.toLanguageTag()
        val (mangaEntries, hasNextPage) = MangaRepository.getMangaBySource(userId, sourceId, pageNum, sort, true)
        val sourceName = NavigationRepository.getSourceDetails(sourceId)?.first ?: sourceId.toString()
        val titleRes =
            if (sort == "latest") {
                MR.strings.opds_feeds_source_specific_latest_title
            } else {
                MR.strings.opds_feeds_source_specific_popular_title
            }
        val feedUrl = "$BASE_URL/explore/source/$sourceId"
        return buildFeed(
            userId,
            locale,
            titleRes.localized(locale, sourceName),
            "$feedUrl?sort=$sort&lang=$languageTag",
            pageNum,
            hasNextPage = hasNextPage,
            facets = OpdsFacetBuilder.getSourceSortFacets(feedUrl, locale, sort),
            navigation = mangaEntries.map { seriesLink(it, languageTag) },
        )
    }

    /**
     * @return null if the series does not exist.
     */
    suspend fun getSeriesChaptersFeed(
        userId: Int,
        locale: Locale,
        mangaId: Int,
        pageNum: Int,
        sortParam: String?,
        filterParam: String?,
    ): String? {
        val feedUrl = "$BASE_URL/series/$mangaId/chapters"
        val page = OpdsFeedBuilder.getSeriesChapters(userId, feedUrl, locale, mangaId, pageNum, sortParam, filterParam) ?: return null
        // Like the OPDS 1.2 chapter lists, sources are only queried for the
        // page counts when the metadata feed is skipped
        if (userConfig.opdsSkipChapterMetadataFeed.value(userId)) OpdsChapterPrefetcher.prefetchPageCounts(page.chapters)
        return buildFeed(
            userId,
            locale,
            MR.strings.opds_feeds_manga_chapters.localized(locale, page.manga.title),
            "$feedUrl?sort=${page.sort}&filter=${page.filter}&lang=${locale.toLanguageTag()}",
            pageNum,
            page.totalCount,
            facets = page.facets,
            publications = page.chapters.map { publication(userId, locale, it, page.manga) },
        )
    }

    /**
     * Returns the history feed, or the library updates one if [updates].
     */
    fun getChaptersFeed(
        userId: Int,
        locale: Locale,
        pageNum: Int,
        updates: Boolean,
    ): String {
        val (items, total) =
            if (updates) ChapterRepository.getLibraryUpdates(userId, pageNum) else ChapterRepository.getHistory(userId, pageNum)
        if (userConfig.opdsSkipChapterMetadataFeed.value(userId)) OpdsChapterPrefetcher.prefetchPageCounts(items.map { it.chapter })
        val title = if (updates) MR.strings.opds_feeds_library_updates_title else MR.strings.opds_feeds_history_title
        return buildFeed(
            userId,
            locale,
            title.localized(locale),
            "$BASE_URL/${if (updates) "library-updates" else "history"}?lang=${locale.toLanguageTag()}",
            pageNum,
            total,
            publications = items.map { publication(userId, locale, it.chapter, it.manga) },
        )
    }

    /**
     * Returns a chapter publication, or its Divina manifest as served from
     * [manifestUrl], fetching its page list when unknown.
     * @return null if the chapter does not exist or has no pages.
     */
    suspend fun getPublication(
        userId: Int,
        locale: Locale,
        chapterId: Int,
        manifestUrl: String? = null,
    ): String? {
        val chapter = ChapterRepository.getChapterDetailsForMetadataFeed(userId, chapterId = chapterId) ?: return null
        val manga = MangaRepository.getMangaDetails(chapter.mangaId) ?: return null
        if (manifestUrl == null) return Json.encodeToString(publication(userId, locale, chapter, manga))
        if (chapter.pageCount <= 0) return null
        return Json.encodeToString(
            Opds2Publication(
                context = "https://readium.org/webpub-manifest/context.jsonld",
                metadata = publicationMetadata(chapter, manga, locale).copy(conformsTo = OpdsConstants.PROFILE_DIVINA),
                links = listOf(Opds2Link(manifestUrl, OpdsConstants.TYPE_DIVINA, "self"), progressionLink(chapterId)),
                // Divina requires the type of each page, which converting them
                // to JPEG makes known without fetching them
                readingOrder =
                    List(chapter.pageCount) {
                        Opds2Link(
                            OpdsEntryBuilder.pageUrl(manga.id, chapter.sourceOrder, "$it") + "&format=image/jpeg",
                            OpdsConstants.TYPE_IMAGE_JPEG,
                        )
                    },
            ),
        )
    }
}
