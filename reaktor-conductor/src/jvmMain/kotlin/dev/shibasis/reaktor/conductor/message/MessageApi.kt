package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service

open class MessageApi : Service("reaktor.message") {
    open val send by call<SendMessage, Message>(effect = Effect.LocalArtifactWrite)
    open val inbox by call<InboxQuery, List<ReceivedMessage>>(effect = Effect.ReadOnly)
    open val page by call<InboxQuery, InboxPage>(effect = Effect.ReadOnly)
    open val read by call<MessageRef, ReceivedMessage>(effect = Effect.ReadOnly)
    open val ack by call<MessageRef, MessageReceipt>(effect = Effect.LocalArtifactWrite)
    open val thread by call<MessageRef, List<Message>>(effect = Effect.ReadOnly)
}
