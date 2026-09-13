package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.attach.AttachmentKey
import dev.shibasis.reaktor.portgraph.attach.Attachments
import dev.shibasis.reaktor.portgraph.attach.attach
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.attach.detach
import dev.shibasis.reaktor.portgraph.attach.update
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTest {
    private val Policy = AttachmentKey<String>("test.policy")
    private val Counter = AttachmentKey<Int>("test.counter")

    @Test
    fun anUntouchedEntityAllocatesNothingAndReportsNoAttachments() {
        val attachments = Attachments()
        assertFalse(attachments.hasAttachments)
        assertNull(attachments.attachment(Policy))
        assertEquals(emptySet(), attachments.attachmentKeys())
    }

    @Test
    fun attachedValuesAreReadBackByTypedKey() {
        val attachments = Attachments()
        attachments.attach(Policy, "standard")
        attachments.attach(Counter, 7)

        assertTrue(attachments.hasAttachments)
        assertEquals("standard", attachments.attachment(Policy))
        assertEquals(7, attachments.attachment(Counter))
        assertEquals(setOf("test.policy", "test.counter"), attachments.attachmentKeys())
    }

    @Test
    fun detachReturnsThePreviousValueAndRemovesTheKey() {
        val attachments = Attachments()
        attachments.attach(Policy, "detailed")

        assertEquals("detailed", attachments.detach(Policy))
        assertNull(attachments.attachment(Policy))
        assertNull(attachments.detach(Policy))
    }

    @Test
    fun updateComposesOverTheCurrentValueAndRemovesOnNull() {
        val attachments = Attachments()

        assertEquals(1, attachments.update(Counter) { (it ?: 0) + 1 })
        assertEquals(2, attachments.update(Counter) { (it ?: 0) + 1 })
        assertNull(attachments.update(Counter) { null })
        assertNull(attachments.attachment(Counter))
        assertFalse(attachments.attachmentKeys().contains("test.counter"))
    }

    @Test
    fun everyGraphEntityCarriesItsOwnAttachments() {
        val consumerOwner = PortCapabilityImpl()
        val providerOwner = PortCapabilityImpl()
        val consumer = consumerOwner.registerConsumer<String>("message")
        val provider = providerOwner.registerProvider("message", "hello")

        consumer.attach(Policy, "consumer")
        provider.attach(Policy, "provider")

        assertEquals("consumer", consumer.attachment(Policy))
        assertEquals("provider", provider.attachment(Policy))
        assertFalse(Attachments().hasAttachments)
    }
}
