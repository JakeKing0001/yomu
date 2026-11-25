package yomu.chapters

import androidx.compose.runtime.compositionLocalOf

/** Lets the chapter list's "missing chapters" line start a search for them right away. */
data class MissingChaptersSearch(val searching: Boolean, val onSearch: () -> Unit)

val LocalMissingChaptersSearch = compositionLocalOf<MissingChaptersSearch?> { null }
