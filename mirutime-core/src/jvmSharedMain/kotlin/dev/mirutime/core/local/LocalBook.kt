package dev.mirutime.core.local

// Local book model shared by the epub/fb2/cbz parsers.
// ceiling: covers and images are not extracted (v0 text/pages reader only) —
// the NoveLA port drops BitmapFactory/BookTextMapper paths until the reader
// needs embedded artwork.
data class LocalBook(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val chapters: List<LocalChapter>,
)

// `url` is the chapter key consumed by LocalSource (relative path inside the
// archive for epub, "fb2_N" for fb2, entry name for cbz).
data class LocalChapter(
    val url: String,
    val title: String,
    val body: String,
)
