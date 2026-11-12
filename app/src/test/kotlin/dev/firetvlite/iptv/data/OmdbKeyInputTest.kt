package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.film.normalizeOmdbKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmdbKeyInputTest {
    @Test fun plainKeysAreTrimmedAndBlankInputStillClearsTheKey() {
        assertEquals("abc12345", normalizeOmdbKey(" \nabc12345\t "))
        assertEquals("", normalizeOmdbKey(" \n\t "))
    }

    @Test fun sampleUrlsAndEmailSnippetsYieldOnlyTheApiKey() {
        assertEquals("abc12345", normalizeOmdbKey("https://www.omdbapi.com/?i=tt3896198&apikey=abc12345"))
        assertEquals("abc12345", normalizeOmdbKey("Example: <http://www.omdbapi.com/?APIKEY=abc12345&t=Movie>"))
        assertEquals("abc12345", normalizeOmdbKey("apikey = abc12345\nNext line"))
        assertEquals("abc12345", normalizeOmdbKey("apikey=abc12345#fragment"))
        assertEquals("", normalizeOmdbKey("https://www.omdbapi.com/?apikey="))
    }

    @Test fun activationLinksAreRejectedEvenWhenASampleUrlIsAlsoPasted() {
        assertNull(normalizeOmdbKey("http://www.omdbapi.com/apikey.aspx?VERIFYKEY=activation-token"))
        assertNull(normalizeOmdbKey(" VerifyKey = activation-token "))
        assertNull(normalizeOmdbKey("VERIFYKEY=activation-token\napikey=abc12345"))
    }
}
