package suwayomi.tachidesk.opds.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Empty collections and unknown values are left out, as the OPDS 2.0 schemas
// require collections to have items and metadata not to be blank

@Serializable
data class Opds2Link(
    val href: String,
    val type: String? = null,
    val rel: String? = null,
    val title: String? = null,
    val templated: Boolean? = null,
    val properties: Opds2LinkProperties? = null,
)

@Serializable
data class Opds2LinkProperties(
    val numberOfItems: Long? = null,
    val authenticate: Opds2Link? = null,
)

@Serializable
data class Opds2FeedMetadata(
    val title: String,
    val numberOfItems: Long? = null,
    val itemsPerPage: Int? = null,
    val currentPage: Int? = null,
)

@Serializable
data class Opds2Facet(
    val metadata: Opds2FeedMetadata,
    val links: List<Opds2Link>,
)

@Serializable
data class Opds2Feed(
    val metadata: Opds2FeedMetadata,
    val links: List<Opds2Link>,
    val facets: List<Opds2Facet> = emptyList(),
    val navigation: List<Opds2Link> = emptyList(),
    val publications: List<Opds2Publication> = emptyList(),
)

/** A publication, or its Readium manifest when it has a reading order. */
@Serializable
data class Opds2Publication(
    @SerialName("@context")
    val context: String? = null,
    val metadata: Opds2PublicationMetadata,
    val links: List<Opds2Link>,
    val images: List<Opds2Link> = emptyList(),
    val readingOrder: List<Opds2Link> = emptyList(),
)

@Serializable
data class Opds2PublicationMetadata(
    @SerialName("@type")
    val type: String,
    val conformsTo: String? = null,
    val identifier: String,
    val title: String,
    val author: String? = null,
    val translator: String? = null,
    val published: String? = null,
    val numberOfPages: Int? = null,
    val belongsTo: Opds2BelongsTo,
)

@Serializable
data class Opds2BelongsTo(
    val series: Opds2Collection,
)

@Serializable
data class Opds2Collection(
    val name: String,
    val position: Float? = null,
    val links: List<Opds2Link>,
)

@Serializable
data class OpdsProgressionDocument(
    val title: String? = null,
    val modified: String,
    val device: OpdsProgressionDevice,
    val progression: Double,
    val references: List<String> = emptyList(),
)

@Serializable
data class OpdsProgressionDevice(
    val id: String,
    val name: String,
)

@Serializable
data class OpdsAuthenticationDocument(
    val id: String,
    val title: String,
    val authentication: List<OpdsAuthenticationFlow>,
)

@Serializable
data class OpdsAuthenticationFlow(
    val type: String,
)

/** RFC 7807 Problem Details. */
@Serializable
data class OpdsProblem(
    val type: String,
    val title: String,
)
