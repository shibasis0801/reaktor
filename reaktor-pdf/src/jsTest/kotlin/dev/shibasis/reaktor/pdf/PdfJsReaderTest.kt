package dev.shibasis.reaktor.pdf

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PdfJsReaderTest {
    private val reader = PdfJsReader()

    @Test fun readsEachPageInOrder() = runTest { PdfReaderChecks.readsEachPageInOrder(reader) }
    @Test fun keepsLinesApart() = runTest { PdfReaderChecks.keepsLinesApart(reader) }
    @Test fun whatIsNotAPdfIsNotRead() = runTest { PdfReaderChecks.whatIsNotAPdfIsNotRead(reader) }
    @Test fun aFileThatOnlyStartsLikeOneIsNotRead() = runTest { PdfReaderChecks.aFileThatOnlyStartsLikeOneIsNotRead(reader) }
    @Test fun aFileCutShortDoesNotThrow() = runTest { PdfReaderChecks.aFileCutShortDoesNotThrow(reader) }
    @Test fun aPdfWithNoWordsIsBlankAndNotMissing() = runTest { PdfReaderChecks.aPdfWithNoWordsIsBlankAndNotMissing(reader) }
}
