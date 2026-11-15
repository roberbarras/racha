package io.github.roberbarras.racha.domain.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceTextTest {
    @Test
    fun readsWindowsInternetShortcuts() {
        val content = "﻿[{000214A0-0000-0000-C000-000000000046}]\r\nProp3=19,11\r\n[InternetShortcut]\r\nIDList=\r\nURL=https://example.com/course?id=1\r\n"
        assertEquals("https://example.com/course?id=1", InternetShortcut.url(content))
    }

    @Test
    fun acceptsABareLinkAndRejectsUnsafeSchemes() {
        assertEquals("https://aws.amazon.com", InternetShortcut.url("https://aws.amazon.com\n"))
        assertNull(InternetShortcut.url("[InternetShortcut]\nURL=javascript:alert(1)"))
        assertNull(InternetShortcut.url("[InternetShortcut]\nURL=file:///etc/passwd"))
        assertNull(InternetShortcut.url("[Other]\nURL=https://example.com"))
        assertNull(InternetShortcut.url(""))
    }

    @Test
    fun onlyWebAndMailLinksAreSafe() {
        assertTrue(ExternalLinks.isSafe("https://example.com"))
        assertTrue(ExternalLinks.isSafe("mailto:me@example.com"))
        assertFalse(ExternalLinks.isSafe("javascript:alert(1)"))
        assertFalse(ExternalLinks.isSafe("data:text/html,x"))
        assertFalse(ExternalLinks.isSafe("https://exa mple.com"))
        assertFalse(ExternalLinks.isSafe("https:"))
    }

    @Test
    fun splitsTextIntoPlainAndLinks() {
        val segments = TextLinks.split("Docs: https://aws.amazon.com/iam. Also www.example.org, and (https://x.io/a_(b)) end")
        assertEquals(
            listOf(
                TextSegment.Plain("Docs: "),
                TextSegment.Link("https://aws.amazon.com/iam", "https://aws.amazon.com/iam"),
                TextSegment.Plain(". Also "),
                TextSegment.Link("www.example.org", "https://www.example.org"),
                TextSegment.Plain(", and ("),
                TextSegment.Link("https://x.io/a_(b)", "https://x.io/a_(b)"),
                TextSegment.Plain(") end"),
            ),
            segments,
        )
        assertEquals(listOf(TextSegment.Plain("no links here")), TextLinks.split("no links here"))
    }
}
