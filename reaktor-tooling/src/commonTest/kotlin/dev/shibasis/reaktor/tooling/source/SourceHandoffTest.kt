package dev.shibasis.reaktor.tooling.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceHandoffTest {
    private val location = SourceLocation(
        absolutePath = "/Users/ovd/dev/bestbuds/modules/app/src/CaptureView.kt",
        line = 41,
        column = 9,
    )

    @Test
    fun vsCodeUsesTheFileSchemeWithLineAndColumn() {
        assertEquals(
            "vscode://file/Users/ovd/dev/bestbuds/modules/app/src/CaptureView.kt:41:9",
            SourceHandoff.url(location, SourceEditor.VsCode),
        )
    }

    @Test
    fun theVsCodeForksShareTheFormAndDifferOnlyByScheme() {
        assertTrue(SourceHandoff.url(location, SourceEditor.Cursor).startsWith("cursor://file/"))
        assertTrue(
            SourceHandoff.url(location, SourceEditor.VsCodeInsiders).startsWith("vscode-insiders://file/")
        )
    }

    @Test
    fun jetBrainsWithoutAProjectUsesTheOpenForm() {
        val url = SourceHandoff.url(location, SourceEditor.JetBrains)
        assertTrue(url.startsWith("idea://open?file="), url)
        assertTrue(url.endsWith("&line=41&column=9"), url)
    }

    @Test
    fun jetBrainsWithAProjectUsesTheNavigateFormSoAnOpenWindowIsReused() {
        val url = SourceHandoff.url(location.copy(projectName = "bestbuds"), SourceEditor.JetBrains)
        assertTrue(url.startsWith("jetbrains://idea/navigate/reference?project=bestbuds&path="), url)
        assertTrue(url.contains("&line=41"), url)
    }

    @Test
    fun aPathWithSpacesAndUnicodeIsPercentEncoded() {
        val spaced = SourceLocation("/Users/ovd/My Projects/Café/Main.kt", line = 3)

        val jetbrains = SourceHandoff.url(spaced, SourceEditor.JetBrains)
        assertTrue(jetbrains.contains("%2FUsers%2Fovd%2FMy%20Projects"), jetbrains)
        assertTrue(jetbrains.contains("Caf%C3%A9"), jetbrains)

        val vscode = SourceHandoff.url(spaced, SourceEditor.VsCode)
        assertTrue(vscode.contains("/Users/ovd/My%20Projects/Caf%C3%A9/Main.kt"), vscode)
    }

    @Test
    fun aLocationWithoutALineOmitsThePositionEntirely() {
        val fileOnly = SourceLocation("/tmp/A.kt")
        assertEquals("vscode://file/tmp/A.kt", SourceHandoff.url(fileOnly, SourceEditor.VsCode))
        assertEquals("idea://open?file=%2Ftmp%2FA.kt", SourceHandoff.url(fileOnly, SourceEditor.JetBrains))
    }

    @Test
    fun aColumnWithoutALineIsNotEmittedBecauseEditorsParsePositionally() {
        val columnOnly = SourceLocation("/tmp/A.kt", column = 5)
        assertEquals("vscode://file/tmp/A.kt", SourceHandoff.url(columnOnly, SourceEditor.VsCode))
    }

    @Test
    fun invalidLocationsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { SourceLocation("  ") }
        assertFailsWith<IllegalArgumentException> { SourceLocation("/tmp/A.kt", line = 0) }
    }

    @Test
    fun theRenderedFormMatchesWhatThePanesAlreadyPrint() {
        assertEquals("/Users/ovd/dev/bestbuds/modules/app/src/CaptureView.kt:41", location.toString())
    }
}
