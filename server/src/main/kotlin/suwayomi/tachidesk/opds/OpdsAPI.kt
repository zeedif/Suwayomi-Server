package suwayomi.tachidesk.opds

import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.put
import suwayomi.tachidesk.opds.controller.OpdsV1Controller
import suwayomi.tachidesk.opds.controller.OpdsV2Controller

object OpdsAPI {
    fun defineEndpoints() {
        path("opds/v1.2") {
            // OPDS Catalog Root Feed (Navigation)
            get(OpdsV1Controller.rootFeed)

            // OPDS Search Description Feed
            get("search", OpdsV1Controller.searchFeed)

            // Reading History Acquisition Feed
            get("history", OpdsV1Controller.historyFeed)

            // Library Updates Acquisition Feed
            get("library-updates", OpdsV1Controller.libraryUpdatesFeed)

            // --- Remote Catalog Exploration ---
            // List of available online sources
            get("explore", OpdsV1Controller.exploreSourcesFeed)

            // Browse series from a specific online source
            path("explore/source/{sourceId}") {
                get(OpdsV1Controller.exploreSourceFeed)
            }

            // --- Library Navigation Feeds ---
            path("library") {
                // All Series in Library / Search Results Feed (Acquisition)
                get("series", OpdsV1Controller.seriesFeed)

                // Library Sources Navigation Feed
                get("sources", OpdsV1Controller.librarySourcesFeed)

                // Library Categories Navigation Feed
                get("categories", OpdsV1Controller.categoriesFeed)

                // Library Genres Navigation Feed
                get("genres", OpdsV1Controller.genresFeed)

                // Library Status Navigation Feed
                get("statuses", OpdsV1Controller.statusesFeed)

                // Library Content Languages Navigation Feed
                get("languages", OpdsV1Controller.languagesFeed)
            }

            // --- Library Series Filters ---
            // Source-Specific Series Acquisition Feed (Library)
            path("source/{sourceId}") {
                get(OpdsV1Controller.librarySourceFeed)
            }

            // Category-Specific Series Acquisition Feed (Library)
            path("category/{categoryId}") {
                get(OpdsV1Controller.categoryFeed)
            }

            // Genre-Specific Series Acquisition Feed (Library)
            path("genre/{genre}") {
                get(OpdsV1Controller.genreFeed)
            }

            // Status-Specific Series Acquisition Feed (Library)
            path("status/{statusId}") {
                get(OpdsV1Controller.statusMangaFeed)
            }

            // Language-Specific Series Acquisition Feed (Library)
            path("language/{langCode}") {
                get(OpdsV1Controller.languageFeed)
            }

            // --- Item Specific Feeds ---
            // Series Chapters Acquisition Feed
            path("series/{seriesId}/chapters") {
                get(OpdsV1Controller.seriesChaptersFeed)
            }
            // Chapter Metadata Acquisition Feed
            path("series/{seriesId}/chapter/{chapterIndex}/metadata") {
                get(OpdsV1Controller.chapterMetadataFeed)
            }
        }

        path("opds/v2.0") {
            get(OpdsV2Controller.rootFeed)
            get("auth", OpdsV2Controller.authentication)
            get("history", OpdsV2Controller.chaptersFeed(updates = false))
            get("library-updates", OpdsV2Controller.chaptersFeed(updates = true))
            get("explore", OpdsV2Controller.exploreSourcesFeed)
            get("explore/source/{sourceId}", OpdsV2Controller.exploreSourceFeed)
            path("library") {
                get("series", OpdsV2Controller.seriesFeed)
                listOf("sources", "categories", "genres", "statuses", "languages").forEach {
                    get(it, OpdsV2Controller.libraryNavigationFeed(it))
                }
            }
            get("series/{seriesId}/chapters", OpdsV2Controller.seriesChaptersFeed)
            path("chapters/{chapterId}") {
                get(OpdsV2Controller.publication)
                get("manifest", OpdsV2Controller.manifest)
                get("progression", OpdsV2Controller.progression)
                put("progression", OpdsV2Controller.updateProgression)
            }
            after(OpdsV2Controller::sendAuthenticationDocument)
        }
    }
}
