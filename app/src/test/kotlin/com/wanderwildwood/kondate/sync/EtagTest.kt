package com.wanderwildwood.kondate.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An etag is compared in one form. A server or proxy that compresses a file on its way out
 * sends the file's etag marked (weak, or with "-gzip" inside the quotes), while a listing gives
 * it plain; the two are the same file.
 */
class EtagTest {

    @Test fun everyFormOfOneEtagIsTheSame() {
        val plain = "\"64f1c0a2b3\""
        for (form in listOf("\"64f1c0a2b3\"", "64f1c0a2b3", "W/\"64f1c0a2b3\"", "\"64f1c0a2b3-gzip\"", "W/\"64f1c0a2b3-gzip\"", "  \"64f1c0a2b3\" ", "\"64f1c0a2b3-br\"")) {
            assertEquals(form, plain, canonicalEtag(form))
        }
    }

    @Test fun differentEtagsStayDifferent() {
        assertEquals("\"a\"", canonicalEtag("a"))
        assertEquals("\"b\"", canonicalEtag("W/\"b\""))
        assertEquals("\"gzip\"", canonicalEtag("\"gzip\""))
    }

    @Test fun aLinkKeptByAnEarlierVersionIsReadInTheOneForm() {
        val kept = JSONObject()
            .put("id", "r1").put("fileId", "42").put("folder", "Lemon Barley Soup").put("folderEtag", "\"f1\"")
            .put("file", "recipe.json").put("fileEtag", "W/\"64f1c0a2b3-gzip\"").put("modified", "2026-11-01T10:00:00-04:00").put("raw", "{}")
        val link = Link.fromJson(kept)
        assertEquals("\"64f1c0a2b3\"", link.remote.fileEtag)
        assertEquals("\"f1\"", link.remote.folderEtag)
        // A folder with no etag on record stays that way.
        assertEquals("", Link.fromJson(kept.put("folderEtag", "")).remote.folderEtag)
    }
}
