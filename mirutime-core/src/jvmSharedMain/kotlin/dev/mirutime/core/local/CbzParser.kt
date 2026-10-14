package dev.mirutime.core.local

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// ── CBZ parser (port from NoveLA tooling/epub_parser CbzParser) ─────────────
// Replacements vs the ethalon: entries are LISTED via ZipInputStream (no
// ZipFile/temp file) and image bytes are never materialized (only names — a
// 300MB comic would otherwise sit fully expanded in the heap), ComicInfo.xml
// parsed via javax DOM (XmlPullParser is Android-only), cover image bytes not
// extracted (LocalBook ceiling — the sorted entry list already puts
// cover.png first).

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

internal data class CbzListing(
    val imageNames: List<String>,
    val comicInfo: ByteArray?,
)

// Список entry без байтов картинок: readZipEntries материализовал бы все
// страницы целиком (OMM на комиксах в сотни МБ), используются только имена.
internal fun readCbzListing(bytes: ByteArray): CbzListing {
    var comicInfo: ByteArray? = null
    val imageNames = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                when {
                    entry.name == "ComicInfo.xml" -> comicInfo = readEntryLimited(zip)
                    isImageEntry(entry.name) -> imageNames += entry.name
                    // Прочие entry пропускаются целиком: имя — единственное,
                    // что CBZ-парсеру от них нужно.
                }
            }
            entry = zip.nextEntry
        }
    }
    return CbzListing(imageNames, comicInfo)
}

internal fun parseCbz(bytes: ByteArray): LocalBook {
    val listing = readCbzListing(bytes)
    val imageNames = listing.imageNames.sortedBy { naturalSortKey(it) }

    val comicInfo = listing.comicInfo?.let { raw ->
        val doc = parseXmlDocument(raw)
        ComicInfo(
            title = doc?.selectFirstTag("Title")?.textContent?.trim(),
            summary = doc?.selectFirstTag("Summary")?.textContent?.trim(),
        )
    }

    // Каждая картинка — отдельная страница-глава; body несёт имя записи.
    val chapters = imageNames.map { name ->
        LocalChapter(
            url = name,
            title = name.substringAfterLast('/').substringBeforeLast('.'),
            body = name,
        )
    }

    return LocalBook(
        title = comicInfo?.title ?: "Unknown Title",
        description = comicInfo?.summary,
        chapters = chapters,
    )
}

private data class ComicInfo(val title: String?, val summary: String?)

private fun isImageEntry(name: String): Boolean {
    if (name.endsWith("/")) return false
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in IMAGE_EXTENSIONS
}

// Natural order: page2 before page10 (digit runs zero-padded to a fixed width).
private fun naturalSortKey(name: String): String =
    name.replace(Regex("\\d+")) { it.value.padStart(12, '0') }
