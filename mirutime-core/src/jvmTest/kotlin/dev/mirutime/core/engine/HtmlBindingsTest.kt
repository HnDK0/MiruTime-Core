package dev.mirutime.core.engine

import dev.mirutime.core.engine.binding.HtmlBindings
import org.junit.Test

class HtmlBindingsTest {

    private fun eval(code: String): LuaCallResult {
        val session = createLuaSession("eval", code)
        HtmlBindings.install(session)
        return session.call("main", emptyList())
    }

    // -- bindings --

    @Test
    fun `html_parse returns text html title body table`() {
        // NoveLA LuaSourceLoader.kt:1025-1035: 1-arg → {text,html,title,body}.
        val r = eval(
            """
            function main()
              local doc = html_parse("<html><head><title>T</title></head><body><p>hi</p></body></html>")
              return type(doc) .. "|" .. doc.title .. "|" .. doc.text .. "|" .. type(doc.body)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("table|T|T hi|table")) { r.data.toString() }
    }

    @Test
    fun `html_select returns element tables with dot access`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<ul><li>one</li><li>two</li></ul>")
              local items = html_select(doc.body, "ul li")
              return #items == 2 and items[1].text .. "|" .. items[2].text or "bad:" .. #items
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("one|two")) { r.data.toString() }
    }

    @Test
    fun `html_select accepts raw html string as first argument`() {
        val r = eval(
            """
            function main()
              local items = html_select("<ul><li>one</li><li>two</li></ul>", "li")
              return #items .. ":" .. items[1].text .. items[2].text
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("2:onetwo")) { r.data.toString() }
    }

    @Test
    fun `element colon attr and nested select match corpus usage`() {
        val r = eval(
            """
            function main()
              local body = "<div class='book'><p class='meta'><a href='/b/1'>Go</a></p></div>"
              local card = html_select_first(body, ".book")
              local links = card:select("a")
              return links[1]:attr("href") .. "|" .. card:select(".meta a")[1].text .. "|" .. links[1].href
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("/b/1|Go|/b/1")) { r.data.toString() }
    }

    @Test
    fun `html_attr three argument shorthand selects then reads attribute`() {
        val r = eval(
            """
            function main()
              return html_attr("<div><img src='/cover.jpg'></div>", "img", "src")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("/cover.jpg")) { r.data.toString() }
    }

    @Test
    fun `html_remove strips selectors and returns cleaned html`() {
        val r = eval(
            """
            function main()
              return html_remove("<div><script>x()</script><p>keep</p></div>", "script")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        val html = (r.data as dev.mirutime.core.engine.LuaData.LuaStr).value
        check(html.contains("<p>keep</p>") && !html.contains("<script>")) { html }
    }

    @Test
    fun `html_text separates paragraphs with newlines like NoveLA TextExtractor`() {
        // Line-based corpus cleanup (content.standardTransforms) strips
        // watermark/credit/heading lines with \\n-anchored regexes — it needs
        // the paragraph separators Ksoup.text() would drop.
        val r = eval(
            """
            function main()
              local html = "<div class='c'><p>First paragraph.</p><p>Second paragraph.</p>tail<br>after br</div>"
              local el = html_select_first(html, ".c")
              local t = html_text(el)
              return t == "First paragraph.\n\nSecond paragraph.\n\ntail\nafter br"
                  and "ok" or ("got:" .. t:gsub("\n", "~"))
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("ok")) { r.data.toString() }
    }

    @Test
    fun `html_select accepts a parse result table via its stored html`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<ul><li>one</li><li>two</li></ul>")
              local items = html_select(doc, "ul li")
              return #items == 2 and items[1].text .. "|" .. items[2].text or "bad:" .. #items
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("one|two")) { r.data.toString() }
    }

    @Test
    fun `element remove method detaches node`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<div><p id='a'>A</p><p id='b'>B</p></div>")
              local a = html_select_first(doc.body, "#a")
              a:remove()
              return html_select_first(doc.body, "#a") == nil and "gone" or "still"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("gone")) { r.data.toString() }
    }

    @Test
    fun `html_select_first returns element or nil`() {
        val r = eval(
            """
            function main()
              local doc = html_parse('<p id="x">hello</p>')
              local el = html_select_first(doc.body, "#x")
              if el == nil then return "nil" end
              return html_text(el)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("hello")) { r.data.toString() }
        val r2 = eval(
            """
            function main()
              local doc = html_parse("<p>only</p>")
              local el = html_select_first(doc.body, ".missing")
              return el == nil and "is-nil" or "not-nil"
            end
            """.trimIndent()
        )
        check(r2.error == null) { r2.error!! }
        check(r2.data == dev.mirutime.core.engine.LuaData.LuaStr("is-nil")) { r2.data.toString() }
    }

    @Test
    fun `html_attr and html_outerHtml`() {
        val r = eval(
            """
            function main()
              local doc = html_parse('<a href="https://example.com/x" title="t">link</a>')
              local a = html_select_first(doc.body, "a")
              return html_attr(a, "href") .. "#" .. html_attr(a, "missing") .. "#" .. html_outerHtml(a)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        val expected = "https://example.com/x##<a href=\"https://example.com/x\" title=\"t\">link</a>"
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr(expected)) { r.data.toString() }
    }

    @Test
    fun `html_remove detaches element from tree`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<div><p id='a'>A</p><p id='b'>B</p></div>")
              local a = html_select_first(doc.body, "#a")
              html_remove(a)
              local b = html_select_first(doc.body, "#b")
              local gone = html_select_first(doc.body, "#a")
              return (gone == nil and "gone" or "still") .. "|" .. html_text(b)
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("gone|B")) { r.data.toString() }
    }

    // -- Ksoup parity CI-gate (4 scenarios from design spec) --

    @Test
    fun `parity no base uri falls back to raw href like NoveLA`() {
        // NoveLA Jsoup.parse(html) knows no base URI, so abs:href is empty and
        // the raw attribute wins. A legacy 2-arg call still works (extra args
        // are ignored) — the factory pipeline relies on it.
        val r = eval(
            """
            function main()
              local doc = html_parse('<a href="/chapter/1">go</a>', "https://example.com/books/")
              local a = html_select_first(doc.body, "a")
              return html_absUrl(a, "href")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("/chapter/1")) { r.data.toString() }
    }

    @Test
    fun `parity contains selector finds link by text`() {
        val r = eval(
            """
            function main()
              local doc = html_parse('<div><a href="/a">Prev</a><a href="/b">Next</a></div>')
              local a = html_select_first(doc.body, "a:contains(Next)")
              return html_attr(a, "href")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("/b")) { r.data.toString() }
    }

    @Test
    fun `parity text normalizes whitespace and newlines`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<p>hello\n      world</p>")
              return html_text(html_select_first(doc.body, "p"))
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == dev.mirutime.core.engine.LuaData.LuaStr("hello world")) { r.data.toString() }
    }

    @Test
    fun `parity outerHtml escapes markup`() {
        val r = eval(
            """
            function main()
              local doc = html_parse("<p>1 &lt; 2 &amp; 3</p>")
              return html_outerHtml(html_select_first(doc.body, "p"))
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        val html = (r.data as dev.mirutime.core.engine.LuaData.LuaStr).value
        check(html.contains("&lt;") && html.contains("&amp;") && !html.contains("1 < 2")) { html }
    }
}
