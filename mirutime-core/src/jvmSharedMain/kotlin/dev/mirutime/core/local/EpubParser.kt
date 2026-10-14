package dev.mirutime.core.local

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

// ── EPUB parser (port from NoveLA tooling/epub_parser) ──────────────────────
// Replacements vs the ethalon: java.util.zip over a FileSystem-read ByteArray
// (no temp file), javax DOM for small metadata files (kxml/Xml is
// Android-only), ksoup for chapter XHTML (Jsoup fallback path — ksoup is the
// already-installed multiplatform port), and no cover/images extraction
// (see LocalBook.kt ceiling).

private val CHAPTER_EXTENSIONS = setOf("xhtml", "xml", "html")

internal fun parseEpub(bytes: ByteArray): LocalBook {
    val entries = readZipEntries(bytes)

    val container = entries["META-INF/container.xml"]
        ?: throw IllegalStateException("META-INF/container.xml file missing")
    val opfFilePath = parseXmlDocument(container)
        ?.selectFirstTag("rootfile")
        ?.attr("full-path")
        ?.decodedURL
        ?: throw IllegalStateException("Invalid container.xml file")
    val rootPath = opfFilePath.substringBefore('/', "")

    val opf = entries[opfFilePath] ?: throw IllegalStateException(".opf file missing")
    val document = parseXmlDocument(opf) ?: throw IllegalStateException(".opf file failed to parse data")
    val metadata = document.selectFirstTag("metadata")
        ?: throw IllegalStateException(".opf file metadata section missing")
    val manifest = document.selectFirstTag("manifest")
        ?: throw IllegalStateException(".opf file manifest section missing")
    val spine = document.selectFirstTag("spine")
        ?: throw IllegalStateException(".opf file spine section missing")

    val title = metadata.selectFirstChildTag("dc:title")?.textContent ?: "Unknown Title"
    val author = metadata.selectFirstChildTag("dc:creator")?.textContent
    val description = metadata.selectFirstChildTag("dc:description")?.textContent

    val manifestItems = manifest.selectChildTag("item").associate {
        it.attr("id").orEmpty() to ManifestRef(
            absPath = it.attr("href").orEmpty().decodedURL.hrefAbsolutePath(rootPath),
            mediaType = it.attr("media-type").orEmpty(),
        )
    }

    // NCX lookup: media-type first, NoveLA's id="ncx" as the fallback.
    val ncxPath = manifestItems.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        ?.absPath ?: manifestItems["ncx"]?.absPath
        ?: throw IllegalStateException("ncx file missing")
    val ncx = entries[ncxPath] ?: throw IllegalStateException("ncx file missing")
    val navMap = parseXmlDocument(ncx)?.selectFirstTag("navMap")
        ?: throw IllegalStateException("Invalid NCX file: navMap not found")

    val tocEntries = navMap.selectTag("navPoint").mapNotNull { navPoint ->
        val navTitle = (navPoint.selectFirstTag("navLabel")
            ?.selectFirstTag("text")?.textContent ?: "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val src = navPoint.selectFirstTag("content")?.attr("src") ?: ""
        if (src.isEmpty()) null
        else TocEntry(navTitle, src.decodedURL.hrefAbsolutePath(rootPath))
    }

    // Spine order (sequential port of the NoveLA batched-parallel loop).
    val chapters = mutableListOf<LocalChapter>()
    var currentToc: TocEntry? = null
    var body = StringBuilder()

    for (itemRef in spine.selectChildTag("itemref")) {
        val item = manifestItems[itemRef.attr("idref").orEmpty()] ?: continue
        val extension = item.absPath.substringAfterLast('.')
        if (extension !in CHAPTER_EXTENSIONS) continue
        val spineUrl = item.absPath
        val text = xhtmlToText(entries[spineUrl] ?: ByteArray(0))
        val toc = tocEntries.firstOrNull {
            it.link.substringBefore('#').equals(spineUrl.substringBefore('#'), ignoreCase = true)
        }

        if (currentToc != null && toc != null && body.isNotEmpty()) {
            chapters.add(LocalChapter(currentToc.link, currentToc.title, body.toString()))
            body = StringBuilder()
        }
        if (toc != null) currentToc = toc
        if (text.isNotBlank()) body.append("\n\n").append(text)
    }
    if (currentToc != null && body.isNotEmpty()) {
        chapters.add(LocalChapter(currentToc.link, currentToc.title, body.toString()))
    }

    return LocalBook(title = title, author = author, description = description, chapters = chapters)
}

private data class ManifestRef(val absPath: String, val mediaType: String)
private data class TocEntry(val title: String, val link: String)

// Chapter XHTML → structured plain text. Port of NoveLA getNodeStructuredText
// (Jsoup path): paragraphs separated by blank lines, <br> → newline, images
// skipped (LocalBook ceiling), the file's own first heading dropped — the
// NCX navPoint title wins at merge time.
internal fun xhtmlToText(bytes: ByteArray): String {
    val doc = Ksoup.parse(bytes.decodeToString(), "")
    val body = doc.body() ?: return doc.text()
    body.selectFirst("h1, h2, h3, h4, h5, h6")?.remove()
    val out = StringBuilder()
    appendChildren(body, out)
    return out.toString()
}

private fun appendChildren(node: Node, out: StringBuilder) {
    for (child in node.childNodes()) {
        when (child.nodeName()) {
            "p" -> {
                val paragraph = StringBuilder().also { appendParagraph(child, it) }.toString().trim()
                if (paragraph.isNotEmpty()) out.append(paragraph).append("\n\n")
            }
            "br" -> out.append('\n')
            "hr" -> out.append("\n\n")
            "img", "image" -> {} // ceiling: embedded images unsupported in v0
            else -> if (child is TextNode) {
                val text = child.text()
                if (text.isNotBlank()) out.append(text).append("\n\n")
            } else {
                appendChildren(child, out)
            }
        }
    }
}

// Paragraph text keeps inline markup flat: nested tags contribute their text.
private fun appendParagraph(node: Node, out: StringBuilder) {
    for (child in node.childNodes()) {
        when (child.nodeName()) {
            "br" -> out.append('\n')
            "img", "image" -> {}
            else -> if (child is TextNode) out.append(child.text()) else appendParagraph(child, out)
        }
    }
}
