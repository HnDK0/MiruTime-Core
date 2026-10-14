package dev.mirutime.core.local

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

// Лимит глубины вложенности XML: реальные документы укладываются в десятки
// уровней, глубже — крафтовый файл.
private const val MAX_XML_DEPTH = 512

// Лёгкое дерево XML-элемента для маленьких метаданных-файлов EPUB/FB2
// (container.xml, content.opf, toc.ncx, ComicInfo.xml). Портировано из
// NoveLA HelperExtensions (kxml XmlPullParser → javax DOM: kxml/Xml —
// Android-only, javax.xml.parsers доступен и на JVM, и на Android).
internal class XmlElement(
    val name: String,
    val attrs: Map<String, String>,
    val text: String,
    val children: List<XmlElement>,
) {
    fun attr(name: String): String? = attrs[name]

    // Поиск по всему поддереву (аналог DOM getElementsByTagName().item(0)).
    fun selectFirstTag(tag: String): XmlElement? =
        if (name == tag) this else children.firstNotNullOfOrNull { it.selectFirstTag(tag) }

    // Все вхождения по всему поддереву, порядок обхода — документный (preorder).
    fun selectTag(tag: String): List<XmlElement> =
        (if (name == tag) listOf(this) else emptyList()) + children.flatMap { it.selectTag(tag) }

    fun selectFirstChildTag(tag: String): XmlElement? = children.firstOrNull { it.name == tag }

    fun selectChildTag(tag: String): List<XmlElement> = children.filter { it.name == tag }

    val textContent: String
        get() = buildString {
            append(text)
            children.forEach { append(it.textContent) }
        }
}

// Парсит маленький XML-документ в лёгкое дерево. Namespace-обработка
// выключена: qualified-имена ("dc:title", "l:href") приходят как есть.
// Канонические DOCTYPE (FictionBook/ncx/package) должны парситься — эталонный
// kxml игнорировал DOCDECL.
internal fun parseXmlDocument(bytes: ByteArray): XmlElement? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    // External DTD/entity loading (SSRF) is blocked portably via the
    // EntityResolver: Android's DocumentBuilderFactory only honors
    // namespaces/validation setFeature()s, so feature-based hardening
    // (disallow-doctype-decl, external-*-entities) would throw
    // ParserConfigurationException there — and disallow-doctype-decl also
    // rejected every real-world book.
    builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
    val dom = builder.parse(ByteArrayInputStream(bytes)).documentElement
        ?: return null
    return dom.toXmlElement(depth = 0)
}

private fun org.w3c.dom.Node.toXmlElement(depth: Int): XmlElement {
    // Глубина ограничена: крафтовый документ с сотнями тысяч вложенных тегов
    // переполнил бы стек (StackOverflowError — это Error, не Exception).
    if (depth > MAX_XML_DEPTH) throw IllegalStateException("XML nesting too deep")
    val text = StringBuilder()
    val children = mutableListOf<XmlElement>()
    val childNodes = childNodes
    for (i in 0 until childNodes.length) {
        val child = childNodes.item(i)
        when (child.nodeType) {
            org.w3c.dom.Node.ELEMENT_NODE -> children.add(child.toXmlElement(depth + 1))
            org.w3c.dom.Node.TEXT_NODE,
            org.w3c.dom.Node.CDATA_SECTION_NODE,
            -> text.append(child.nodeValue)
        }
    }
    val attrs = buildMap {
        val named = attributes ?: return@buildMap
        for (i in 0 until named.length) {
            val item = named.item(i)
            put(item.nodeName, item.nodeValue)
        }
    }
    return XmlElement(nodeName, attrs, text.toString(), children)
}

internal val String.decodedURL: String get() = URLDecoder.decode(this, "UTF-8")

// Безопасный путь записи внутри книги: относительный, без обхода каталогов,
// абсолютных префиксов и служебных символов (порт из NoveLA).
internal fun String.isSafeBookPath(): Boolean =
    isNotEmpty() &&
        !startsWith("/") &&
        !startsWith("\\") &&
        !contains("..") &&
        !contains(":") &&
        none { it.code < 0x20 }

// Абсолютный путь записи внутри EPUB относительно каталога OPF-файла с
// нормализацией сегментов ("."/".."). java.io.File/canonicalFile из NoveLA
// заменён на чистую логику: пути используются только для чтения из архива,
// zip-slip на диск невозможен, поэтому строгая isSafeBookPath здесь не нужна
// (там она ломала валидные href вида "../cover.jpg").
internal fun String.hrefAbsolutePath(rootPath: String): String {
    val combined = when {
        isEmpty() -> rootPath
        rootPath.isEmpty() -> this
        else -> "$rootPath/$this"
    }
    val parts = ArrayDeque<String>()
    for (segment in combined.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> {}
            segment == ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}
