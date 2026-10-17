package dev.shibasis.reaktor.ui.machinesignal

import kotlin.test.*

class SignalMarkdownTest {
    @Test fun streamingUnclosedCodeFencePreservesItsContents() {
        val blocks = markdownBlocks("## Result\n\n```kotlin\nval draft = \"**literal**\"\n")
        assertEquals(listOf("heading", "code"), blocks.map { it.kind })
        assertTrue(blocks.last().text.contains("**literal**"))
    }
    @Test fun ordinaryMarkupKeepsWordsAndOnlyWebLinksBecomeLinks() {
        val text = inlineMarkdown("Use **drafts** in `Tabs.kt`; [docs](https://reaktor.build/docs) and [command](javascript:alert(1))", MachineSignalFonts())
        assertEquals("Use drafts in Tabs.kt; docs and [command](javascript:alert(1))", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }
    @Test fun paragraphsListsAndQuotesRemainDistinct() {
        val blocks = markdownBlocks("A paragraph\ncontinues here\n\n- First\n- Second\n\n> Evidence")
        assertEquals(listOf("paragraph", "paragraph", "quote"), blocks.map { it.kind })
        assertEquals("• First\n• Second", blocks[1].text)
    }
}
