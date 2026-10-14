package dev.mirutime.core.local

// ── FB2 parser (port from NoveLA tooling/epub_parser Fb2Parser) ─────────────
// Replacements vs the ethalon: javax DOM single pass instead of two streaming
// XmlPullParser passes (ceiling: whole-tree DOM; the NoveLA streaming was a
// 256MB-heap Android constraint — revisit if huge FB2 imports OOM), no
// BitmapFactory/base64 image decoding (LocalBook ceiling; inline "[id]"
// placeholders only), zip-wrapped .fb2.zip supported via unwrapSingleEntryZip.

internal fun parseFb2(raw: ByteArray): LocalBook {
    val bytes = unwrapSingleEntryZip(raw)
    val doc = parseXmlDocument(bytes) ?: throw IllegalStateException("FB2 parse failed")

    val title = doc.selectFirstTag("book-title")?.textContent?.trim()
        .orEmpty().ifEmpty { "Unknown Title" }
    val authorEl = doc.selectFirstTag("author")
    val author = authorEl?.let {
        listOfNotNull(
            it.selectFirstChildTag("first-name")?.textContent?.trim(),
            it.selectFirstChildTag("last-name")?.textContent?.trim(),
        ).joinToString(" ").ifEmpty { null }
    }
    val description = doc.selectFirstTag("annotation")?.textContent?.trim()

    val chapters = doc.selectTag("section").mapIndexedNotNull { index, section ->
        val body = sectionBodyText(section)
        if (body.isEmpty()) null else {
            val sectionTitle = section.selectFirstChildTag("title")
                ?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            LocalChapter(
                url = "fb2_$index",
                title = sectionTitle.ifEmpty { "Chapter ${index + 1}" },
                body = body,
            )
        }
    }

    return LocalBook(title = title, author = author, description = description, chapters = chapters)
}

// Прямой текст structural-тегов — whitespace-мусор между элементами; вложенные
// <section> уходят в собственные главы (NoveLA stack semantics: детский текст
// не копится в родителе).
private fun sectionBodyText(section: XmlElement): String {
    val sb = StringBuilder()
    for (child in section.children) {
        when (child.name) {
            "title", "section" -> {}
            "empty-line", "br" -> sb.append('\n')
            "p", "subtitle", "cite", "epigraph", "table" ->
                sb.append(child.textContent.trim()).append("\n\n")
            "image" -> {
                val id = (child.attr("l:href") ?: child.attr("href") ?: "").removePrefix("#")
                sb.append('[').append(id).append(']')
            }
            else -> {
                val text = child.textContent.trim()
                if (text.isNotEmpty()) sb.append(text).append("\n\n")
            }
        }
    }
    return sb.toString().trim()
}
