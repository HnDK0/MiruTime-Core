package dev.mirutime.core.local

import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.host.FakeFileSystem
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

// Fixtures are built in-memory into FakeFileSystem: the brief suggested static
// files in commonTest/resources, but resource loading is JVM-only while these
// tests should stay platform-free.
class LocalSourceTest {

    private val fs = FakeFileSystem()
    private val dir = "/library"
    private val source = LocalSource(fs, dir)

    private fun <T> Outcome<T>.ok(): T = (this as Outcome.Ok).value

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epubFixture(): ByteArray = zip(
        listOf(
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container><rootfiles>
                  <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles></container>
            """.trimIndent().toByteArray(),
            "OEBPS/content.opf" to """
                <?xml version="1.0"?>
                <!DOCTYPE package PUBLIC "+//ISBN 0-9673008-1-9//DTD OEB 1.2 Package//EN" "http://openebook.org/dtds/oeb-1.2/oebpkg12.dtd">
                <package>
                  <metadata>
                    <dc:title>Test Book</dc:title>
                    <dc:creator>A. Author</dc:creator>
                    <dc:description>Book description</dc:description>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/toc.ncx" to """
                <?xml version="1.0"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-11//EN" "http://www.daisy.org/z3986/2005/ncx-2005-11.dtd">
                <ncx><navMap>
                  <navPoint><navLabel><text>Chapter One</text></navLabel><content src="ch1.xhtml"/></navPoint>
                  <navPoint><navLabel><text>Chapter Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
                </navMap></ncx>
            """.trimIndent().toByteArray(),
            "OEBPS/ch1.xhtml" to """
                <html><body>
                  <p>First paragraph.</p>
                  <p>Second paragraph.</p>
                </body></html>
            """.trimIndent().toByteArray(),
            "OEBPS/ch2.xhtml" to """
                <html><body>
                  <h2>Inline Heading</h2>
                  <p>Chapter two text.</p>
                </body></html>
            """.trimIndent().toByteArray(),
        ),
    )

    private fun fb2Fixture(): ByteArray = """
        <?xml version="1.0"?>
        <!DOCTYPE FictionBook PUBLIC "-// FictionBook 2.0//EN" "http://www.gutenberg.org/fictionbook/fictionbook2.dtd">
        <FictionBook>
          <description><title-info>
            <book-title>FB2 Book</book-title>
            <author><first-name>Ivan</first-name><last-name>Petrov</last-name></author>
            <annotation>Anno text</annotation>
          </title-info></description>
          <body>
            <section>
              <title><p>Section One</p></title>
              <p>Text of section one.</p>
            </section>
            <section>
              <title><p>Section Two</p></title>
              <p>Text of section two.</p>
            </section>
          </body>
        </FictionBook>
    """.trimIndent().toByteArray()

    private fun cbzFixture(): ByteArray = zip(
        listOf(
            "page10.png" to byteArrayOf(1, 2, 3),
            "page2.jpg" to byteArrayOf(4, 5),
            "cover.png" to byteArrayOf(6),
            "ComicInfo.xml" to """
                <ComicInfo><Summary>Comic summary</Summary><Genre>Action</Genre></ComicInfo>
            """.trimIndent().toByteArray(),
        ),
    )

    @Test
    fun `epub catalog chapters and text`() {
        fs.write("$dir/book.epub", epubFixture())

        val catalog = runBlocking { source.catalog(0) }.ok()
        assertEquals(listOf("book"), catalog.items.map { it.title })
        assertEquals("$dir/book.epub", catalog.items.single().url)
        assertFalse(catalog.hasNext)

        val book = runBlocking { source.book("$dir/book.epub") }.ok()
        assertEquals("Test Book", book.title)
        assertEquals("Book description", book.description)

        val chapters = runBlocking { source.chapters("$dir/book.epub") }.ok()
        assertEquals(listOf("Chapter One", "Chapter Two"), chapters.map { it.title })

        val c1 = runBlocking { source.chapterContent("", chapters[0].url) }.ok()
        assertEquals(
            "First paragraph.\n\nSecond paragraph.",
            assertIs<ChapterContent.Text>(c1).text.trim(),
        )

        val c2 = runBlocking { source.chapterContent("", chapters[1].url) }.ok()
        assertEquals("Chapter two text.", assertIs<ChapterContent.Text>(c2).text.trim())
    }

    @Test
    fun `fb2 chapters and text`() {
        fs.write("$dir/fb2book.fb2", fb2Fixture())

        val chapters = runBlocking { source.chapters("$dir/fb2book.fb2") }.ok()
        assertEquals(listOf("Section One", "Section Two"), chapters.map { it.title })

        val c1 = runBlocking { source.chapterContent("", chapters[0].url) }.ok()
        assertEquals("Text of section one.", assertIs<ChapterContent.Text>(c1).text.trim())

        val book = runBlocking { source.book("$dir/fb2book.fb2") }.ok()
        assertEquals("FB2 Book", book.title)
        assertEquals("Anno text", book.description)
    }

    @Test
    fun `cbz pages in natural order`() {
        fs.write("$dir/comic.cbz", cbzFixture())

        val chapters = runBlocking { source.chapters("$dir/comic.cbz") }.ok()
        assertEquals(listOf("cover", "page2", "page10"), chapters.map { it.title })

        val page = runBlocking { source.chapterContent("", chapters[1].url) }.ok()
        assertEquals(
            ChapterContent.Pages(listOf("$dir/comic.cbz#page2.jpg")),
            page,
        )
    }

    @Test
    fun `search filters by file name`() {
        fs.write("$dir/book.epub", epubFixture())
        fs.write("$dir/comic.cbz", cbzFixture())

        val page = runBlocking { source.search(0, "com") }.ok()
        assertEquals(listOf("comic"), page.items.map { it.title })
    }

    @Test
    fun `missing file is a user error`() {
        val result = runBlocking { source.chapters("$dir/nope.epub") }
        assertIs<Outcome.Err>(result)
    }

    // ── Task 16 review fix: portable xml hardening ───────────────────────────

    @Test
    fun `fb2 external entity is not fetched`() {
        // Canonical DOCTYPE + an entity pointing at an external SYSTEM file
        // (SSRF probe). The entity must resolve to empty WITHOUT any network
        // access — the doc still parses and the surrounding text survives.
        fs.write(
            "$dir/entity.fb2",
            """
            <?xml version="1.0"?>
            <!DOCTYPE FictionBook [
              <!ENTITY ext SYSTEM "http://127.0.0.1:9/evil.dtd">
            ]>
            <FictionBook>
              <description><title-info><book-title>Entity Book</book-title></title-info></description>
              <body><section><p>Before &ext; after.</p></section></body>
            </FictionBook>
            """.trimIndent().toByteArray(),
        )

        val chapters = runBlocking { source.chapters("$dir/entity.fb2") }.ok()
        val text = assertIs<ChapterContent.Text>(
            runBlocking { source.chapterContent("", chapters.single().url) }.ok(),
        ).text
        assertContains(text, "Before")
        assertContains(text, "after.")
    }

    @Test
    fun `zip total budget rejects oversized archives`() {
        // 3 x 25MB of zeros: every entry fits the per-entry cap (32MB) but the
        // sum exceeds the 64MB total budget — rejected instead of an OOM.
        val entries = (1..3).map { "data$it.bin" to ByteArray(25 * 1024 * 1024) }
        assertFailsWith<IllegalStateException> { readZipEntries(zip(entries)) }
    }

    // ── Task 16 review fix: lightweight cbz ──────────────────────────────────

    @Test
    fun `cbz skips image bytes of oversized pages`() {
        // 65MB "image": over the per-entry cap (32MB). Listing must only take
        // the entry NAME — reading the bytes would reject this archive.
        fs.write(
            "$dir/huge.cbz",
            zip(
                listOf(
                    "big.png" to ByteArray(65 * 1024 * 1024),
                    "ComicInfo.xml" to "<ComicInfo><Title>Huge Comic</Title></ComicInfo>".toByteArray(),
                ),
            ),
        )

        val chapters = runBlocking { source.chapters("$dir/huge.cbz") }.ok()
        assertEquals(listOf("big"), chapters.map { it.title })
        assertEquals("Huge Comic", runBlocking { source.book("$dir/huge.cbz") }.ok().title)
    }

    @Test
    fun `corrupted cbz page is served without parsing`() {
        // Garbage bytes are not a zip: parsing would fail, but the cbz branch
        // of chapterContent must only check existence — manga swipes hit this
        // on every page.
        fs.write("$dir/bad.cbz", byteArrayOf(1, 2, 3, 4))

        val page = runBlocking { source.chapterContent("", "$dir/bad.cbz#cover.png") }.ok()
        assertEquals(ChapterContent.Pages(listOf("$dir/bad.cbz#cover.png")), page)
    }
}
