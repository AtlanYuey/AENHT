package com.atlanyuey.aenht.core

import com.atlanyuey.aenht.shared.nh.ImageInfo as SharedImageInfo

// The nhentai models live in the shared module; these names keep the
// Android screens unchanged.
typealias NHHost = com.atlanyuey.aenht.shared.nh.NHHost
typealias GalleryLanguage = com.atlanyuey.aenht.shared.nh.GalleryLanguage
typealias GalleryItem = com.atlanyuey.aenht.shared.nh.GalleryItem
typealias PageResponse<T> = com.atlanyuey.aenht.shared.nh.PageResponse<T>
typealias ImageInfo = com.atlanyuey.aenht.shared.nh.ImageInfo
typealias GalleryPage = com.atlanyuey.aenht.shared.nh.GalleryPage
typealias GalleryTag = com.atlanyuey.aenht.shared.nh.GalleryTag
typealias TagGroup = com.atlanyuey.aenht.shared.nh.TagGroup
typealias GalleryDetail = com.atlanyuey.aenht.shared.nh.GalleryDetail
typealias CommentPoster = com.atlanyuey.aenht.shared.nh.CommentPoster
typealias GalleryComment = com.atlanyuey.aenht.shared.nh.GalleryComment
typealias CurrentUser = com.atlanyuey.aenht.shared.nh.CurrentUser
typealias FavoriteResult = com.atlanyuey.aenht.shared.nh.FavoriteResult
typealias TagSuggestion = com.atlanyuey.aenht.shared.nh.TagSuggestion

/**
 * What a download knows about its gallery, for its page without the
 * network when the gallery's JSON wasn't saved with it.
 */
fun galleryDetailFrom(record: DownloadRecord): GalleryDetail {
    val image = record.thumbnail?.let { SharedImageInfo(it, record.thumbWidth, record.thumbHeight) }
    return GalleryDetail(
        id = record.id, mediaId = record.mediaId, englishTitle = record.title,
        japaneseTitle = null, prettyTitle = null, cover = image, thumbnail = image,
        uploadDate = null, tags = emptyList(), numPages = record.pages.size, numFavorites = 0,
        pages = record.pages.map { GalleryPage(it.number, it.remote, it.width, it.height, null) },
        related = emptyList(), isFavorited = null,
    )
}
