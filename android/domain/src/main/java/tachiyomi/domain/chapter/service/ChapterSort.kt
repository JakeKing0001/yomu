package tachiyomi.domain.chapter.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import yomu.chapters.BorrowedChapter

fun getChapterSort(
    manga: Manga,
    sortDescending: Boolean = manga.sortDescending(),
): (
    Chapter,
    Chapter,
) -> Int {
    return when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> {
            val newestFirst = { c1: Chapter, c2: Chapter ->
                if (BorrowedChapter.isBorrowed(c1.url) || BorrowedChapter.isBorrowed(c2.url)) {
                    c2.chapterNumber.compareTo(c1.chapterNumber)
                } else {
                    c1.sourceOrder.compareTo(c2.sourceOrder)
                }
            }
            if (sortDescending) newestFirst else { c1, c2 -> newestFirst(c2, c1) }
        }
        Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
            true -> { c1, c2 -> c2.chapterNumber.compareTo(c1.chapterNumber) }
            false -> { c1, c2 -> c1.chapterNumber.compareTo(c2.chapterNumber) }
        }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { c1, c2 -> compareValues(c2.dateUpload, c1.dateUpload) }
            false -> { c1, c2 -> compareValues(c1.dateUpload, c2.dateUpload) }
        }
        Manga.CHAPTER_SORTING_ALPHABET -> when (sortDescending) {
            true -> { c1, c2 -> c2.name.compareToWithCollator(c1.name) }
            false -> { c1, c2 -> c1.name.compareToWithCollator(c2.name) }
        }
        else -> throw NotImplementedError("Invalid chapter sorting method: ${manga.sorting}")
    }
}
