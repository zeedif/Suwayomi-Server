package suwayomi.tachidesk.opds.dto

data class OpdsSeriesChapters(
    val manga: OpdsMangaDetails,
    val chapters: List<OpdsChapterListAcqEntry>,
    val totalCount: Long,
    val sort: String,
    val filter: String,
    val facets: List<OpdsFacet>,
)
