// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OxigenStringsTest {
    /**
     * The previous version of this test compared `keysFor(language)` against `keysFor("en")` for
     * every supported language. `keysFor` returns the same map keys for any supported language,
     * so the assertion could never fail and gave false confidence that translations were
     * complete. Per-locale resource completeness is enforced by Android Lint's MissingTranslation
     * check and by `scripts/verify-project-policy.py`; what remains testable here are the
     * invariants of the facade itself.
     */
    @Test
    fun supportedLanguagesHaveASingleDefinition() {
        assertEquals(PreferencesManager.SUPPORTED_APP_LANGUAGES, OxigenStrings.supportedLanguages)
        assertTrue("en" in OxigenStrings.supportedLanguages)
    }

    @Test
    fun supportedLanguagesExposeKeysAndUnsupportedOnesDoNot() {
        val english = OxigenStrings.keysFor("en")
        assertFalse(english.isEmpty())
        OxigenStrings.supportedLanguages.forEach { language ->
            assertEquals(language, english, OxigenStrings.keysFor(language))
        }
        listOf("zz", "", "EN", "en-US").forEach { unsupported ->
            assertTrue(unsupported, OxigenStrings.keysFor(unsupported).isEmpty())
        }
    }

    /**
     * `get` returns the requested key verbatim when no Android context has been installed. A key
     * that is absent from the lookup table therefore reaches the screen as a raw identifier
     * instead of readable text, which is the failure this guards against.
     */
    @Test
    fun unknownKeyIsReturnedVerbatimRatherThanCrashing() {
        assertEquals("not_a_real_key", OxigenStrings.get("en", "not_a_real_key"))
    }

    /**
     * A key that is requested in the UI but absent from the lookup table is shown to the user as
     * a raw identifier (the auto-sync buttons once read "auto_sync_btn"). Neither Lint nor the
     * resource compiler can see that, because the key is a plain string, so scan the sources.
     */
    @Test
    fun everyKeyRequestedByTheSourcesExistsInTheLookupTable() {
        val sourceRoot = sequenceOf(File("src"), File("app/src")).firstOrNull(File::isDirectory)
        assertTrue("Source tree not found from ${File("").absolutePath}", sourceRoot != null)
        val request = Regex("""OxigenStrings\s*\.\s*get\(\s*[^,()]+,\s*"([a-z0-9_]+)"\s*[,)]""")
        val known = OxigenStrings.keysFor("en")
        val missing = sourceRoot!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "${File.separator}test${File.separator}" !in it.path }
            .flatMap { file -> request.findAll(file.readText()).map { it.groupValues[1] to file.name } }
            .filter { (key, _) -> key !in known }
            .map { (key, file) -> "$key ($file)" }
            .toSortedSet()
        assertTrue("Keys used in the UI but missing from OxigenStrings: $missing", missing.isEmpty())
    }

    @Test
    fun everyLookupKeyIsNonBlankAndUnique() {
        val keys = OxigenStrings.keysFor("en")
        assertTrue(keys.none { it.isBlank() })
        assertTrue(keys.all { key -> key.all { it.isLowerCase() || it.isDigit() || it == '_' } })
    }
}
