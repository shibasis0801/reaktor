package dev.shibasis.reaktor.ui.machinesignal

import kotlin.test.*
import dev.shibasis.reaktor.ui.code.*

class SignalMarkdownTest {
    @Test fun streamingUnclosedCodeFencePreservesItsContents() {
        val blocks = markdownBlocks(CodeDocument.of("## Result\n\n```kotlin\nval draft = \"**literal**\"\n"))
        assertEquals(listOf(MarkdownKind.Heading, MarkdownKind.Code), blocks.map { it.kind })
        assertTrue(blocks.last().text.contains("**literal**"))
    }
    @Test fun ordinaryMarkupKeepsWordsAndOnlyWebLinksBecomeLinks() {
        val text = inlineMarkdown("Use **drafts** in `Tabs.kt`; [docs](https://reaktor.build/docs) and [command](javascript:alert(1))", MachineSignalFonts(), MachineSignalColors.Board)
        assertEquals("Use drafts in Tabs.kt; docs and [command](javascript:alert(1))", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }
    @Test fun paragraphsListsAndQuotesRemainDistinct() {
        val blocks = markdownBlocks(CodeDocument.of("A paragraph\ncontinues here\n\n- First\n- Second\n\n> Evidence"))
        assertEquals(listOf(MarkdownKind.Paragraph, MarkdownKind.List, MarkdownKind.Quote), blocks.map { it.kind })
        assertEquals("• First\n• Second", blocks[1].text)
    }
}
