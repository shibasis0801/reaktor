// Generated from bestbuds/targets/reaktorWeb/docusaurus/docs/reaktor-examples-*.md by build-theory.mjs.
package dev.shibasis.reaktor.portgraph
import dev.shibasis.reaktor.portgraph.definition.*
import kotlinx.serialization.json.Json
import kotlin.test.*
class RepresentativeDefinitionsTest {
    @Test fun allThirtyDesignsCompileValidateAndRoundTrip() {
        val definitions = listOf(
            graphDefinition("mobile-community", "57940c5ac864e359093ec40028152376c918b29435b97abe63e86e70495f3139") {
                region("device", "Mobile device")
                region("edge", "Edge services")
                node("route", "App routes", "N12", "device", ports = listOf(
                    PortDefinition("destination", "P11", "ConversationRouteV1", PortPolarity.Offer)
                ))
                node("screen", "Conversation Surface", "N11", "device", ports = listOf(
                    PortDefinition("route", "P11", "ConversationRouteV1", PortPolarity.Require),
                    PortDefinition("intent", "P04", "MessageIntentV1", PortPolarity.Offer)
                ))
                node("controller", "Conversation logic", "N02", "device", ports = listOf(
                    PortDefinition("input", "P04", "MessageIntentV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "ScopedGrantV1", PortPolarity.Require),
                    PortDefinition("send", "P02", "SendMessageV1", PortPolarity.Require),
                    PortDefinition("span", "P24", "TraceEnvelopeV1", PortPolarity.Offer),
                    PortDefinition("effect", "P24", "EffectRefV1", PortPolarity.Neutral)
                ))
                node("auth", "Session boundary", "N33", "edge", ports = listOf(
                    PortDefinition("grant", "P20", "ScopedGrantV1", PortPolarity.Offer),
                    PortDefinition("principal", "P24", "PrincipalV1", PortPolarity.Neutral)
                ))
                node("chat", "Messaging service", "N19", "edge", ports = listOf(
                    PortDefinition("send", "P02", "SendMessageV1", PortPolarity.Offer),
                    PortDefinition("storage", "P02", "MessageLedgerV1", PortPolarity.Require)
                ))
                node("log", "Message authority", "N21", "edge", ports = listOf(
                    PortDefinition("append", "P02", "MessageLedgerV1", PortPolarity.Offer),
                    PortDefinition("resource", "P24", "ConversationRefV1", PortPolarity.Neutral)
                ))
                node("trace", "Trace exporter", "N38", "edge", ports = listOf(
                    PortDefinition("span", "P24", "TraceEnvelopeV1", PortPolarity.Require)
                ))
                relation("nav", "H36",
                    Incidence("route", "destination", "provider", 0),
                    Incidence("screen", "route", "consumer", 0)
                )
                relation("intent", "H02",
                    Incidence("screen", "intent", "provider", 0),
                    Incidence("controller", "input", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("auth", "grant", "provider", 0),
                    Incidence("controller", "authority", "consumer", 0)
                )
                relation("service", "H05",
                    Incidence("chat", "send", "provider", 0),
                    Incidence("controller", "send", "consumer", 0)
                )
                relation("persist", "H05",
                    Incidence("log", "append", "provider", 0),
                    Incidence("chat", "storage", "consumer", 0)
                )
                relation("observe", "H04",
                    Incidence("controller", "span", "provider", 0),
                    Incidence("trace", "span", "consumer", 0)
                )
                relation("scope", "H23",
                    Incidence("auth", "principal", "principal", 0),
                    Incidence("controller", "effect", "effect", 1),
                    Incidence("log", "resource", "resource", 2)
                )
            },
            graphDefinition("mobile-offline", "49761185d049a8802597968e03c4942f3e314819d581eea6dc6068cdab35abbd") {
                region("device", "Mobile device")
                region("edge", "Edge services")
                node("view", "Notebook Surface", "N11", "device", ports = listOf(
                    PortDefinition("edit", "P04", "EditIntentV1", PortPolarity.Offer)
                ))
                node("edit", "Local edit logic", "N02", "device", ports = listOf(
                    PortDefinition("input", "P04", "EditIntentV1", PortPolarity.Require),
                    PortDefinition("store", "P02", "LocalMutationV1", PortPolarity.Require)
                ))
                node("store", "Local durable store", "N21", "device", ports = listOf(
                    PortDefinition("commit", "P02", "LocalMutationV1", PortPolarity.Offer),
                    PortDefinition("pending", "P01", "PendingBatchV1", PortPolarity.Offer),
                    PortDefinition("revision", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                node("sync", "Sync agent", "N25", "device", ports = listOf(
                    PortDefinition("pending", "P01", "PendingBatchV1", PortPolarity.Require),
                    PortDefinition("remote", "P02", "SyncBatchV1", PortPolarity.Require),
                    PortDefinition("reconciler", "P24", "ImplementationRefV1", PortPolarity.Neutral)
                ))
                node("api", "Notebook service", "N19", "edge", ports = listOf(
                    PortDefinition("reconcile", "P02", "SyncBatchV1", PortPolarity.Offer),
                    PortDefinition("storage", "P02", "ConditionalWriteV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "WorkspaceGrantV1", PortPolarity.Require)
                ))
                node("authority", "Notebook authority", "N21", "edge", ports = listOf(
                    PortDefinition("conditional", "P02", "ConditionalWriteV1", PortPolarity.Offer),
                    PortDefinition("revision", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Workspace policy", "N33", "edge", ports = listOf(
                    PortDefinition("grant", "P20", "WorkspaceGrantV1", PortPolarity.Offer)
                ))
                relation("input", "H02",
                    Incidence("view", "edit", "provider", 0),
                    Incidence("edit", "input", "consumer", 0)
                )
                relation("local", "H05",
                    Incidence("store", "commit", "provider", 0),
                    Incidence("edit", "store", "consumer", 0)
                )
                relation("pending", "H02",
                    Incidence("store", "pending", "provider", 0),
                    Incidence("sync", "pending", "consumer", 0)
                )
                relation("remote", "H05",
                    Incidence("api", "reconcile", "provider", 0),
                    Incidence("sync", "remote", "consumer", 0)
                )
                relation("write", "H05",
                    Incidence("authority", "conditional", "provider", 0),
                    Incidence("api", "storage", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("api", "authority", "consumer", 0)
                )
                relation("lineage", "H27",
                    Incidence("store", "revision", "localRevision", 0),
                    Incidence("authority", "revision", "remoteRevision", 1),
                    Incidence("sync", "reconciler", "reconciler", 2)
                )
            },
            graphDefinition("mobile-camera", "90903c09d808b0d844575eca374673893ee42048ed9bcbf4517d71788f8cae26") {
                region("device", "Mobile device")
                region("edge", "Edge services")
                node("surface", "Capture Surface", "N11", "device", ports = listOf(
                    PortDefinition("preview", "P08", "PreviewStateV1", PortPolarity.Require),
                    PortDefinition("result", "P01", "DetectionV1", PortPolarity.Require),
                    PortDefinition("upload", "P02", "MediaUploadV1", PortPolarity.Require)
                ))
                node("permission", "Camera permission", "N33", "device", ports = listOf(
                    PortDefinition("grant", "P20", "DeviceGrantV1", PortPolarity.Offer)
                ))
                node("camera", "Camera adapter", "N15", "device", ports = listOf(
                    PortDefinition("permission", "P20", "DeviceGrantV1", PortPolarity.Require),
                    PortDefinition("preview", "P08", "PreviewStateV1", PortPolarity.Offer),
                    PortDefinition("frame", "P16", "OwnedFrameV1", PortPolarity.Offer),
                    PortDefinition("release", "P24", "ReleaseRefV1", PortPolarity.Neutral)
                ))
                node("pool", "Owned frame pool", "N26", "device", ports = listOf(
                    PortDefinition("frame", "P16", "OwnedFrameV1", PortPolarity.Require),
                    PortDefinition("borrow", "P17", "FrameLeaseV1", PortPolarity.Offer),
                    PortDefinition("owner", "P24", "ResourceOwnerV1", PortPolarity.Neutral)
                ))
                node("native", "Native inference kernel", "N16", "device", ports = listOf(
                    PortDefinition("input", "P17", "FrameLeaseV1", PortPolarity.Require),
                    PortDefinition("result", "P01", "DetectionV1", PortPolarity.Offer),
                    PortDefinition("borrower", "P24", "BorrowScopeV1", PortPolarity.Neutral)
                ))
                node("upload", "Upload service", "N19", "edge", ports = listOf(
                    PortDefinition("put", "P02", "MediaUploadV1", PortPolarity.Offer),
                    PortDefinition("store", "P02", "ObjectPutV1", PortPolarity.Require)
                ))
                node("blob", "Object store", "N26", "edge", ports = listOf(
                    PortDefinition("put", "P02", "ObjectPutV1", PortPolarity.Offer)
                ))
                relation("permission", "H23",
                    Incidence("permission", "grant", "provider", 0),
                    Incidence("camera", "permission", "consumer", 0)
                )
                relation("preview", "H02",
                    Incidence("camera", "preview", "provider", 0),
                    Incidence("surface", "preview", "consumer", 0)
                )
                relation("frame", "H02",
                    Incidence("camera", "frame", "provider", 0),
                    Incidence("pool", "frame", "consumer", 0)
                )
                relation("borrow", "H01",
                    Incidence("pool", "borrow", "provider", 0),
                    Incidence("native", "input", "consumer", 0)
                )
                relation("result", "H02",
                    Incidence("native", "result", "provider", 0),
                    Incidence("surface", "result", "consumer", 0)
                )
                relation("upload", "H05",
                    Incidence("upload", "put", "provider", 0),
                    Incidence("surface", "upload", "consumer", 0)
                )
                relation("storage", "H05",
                    Incidence("blob", "put", "provider", 0),
                    Incidence("upload", "store", "consumer", 0)
                )
                relation("lifetime", "H19",
                    Incidence("pool", "owner", "owner", 0),
                    Incidence("native", "borrower", "borrower", 1),
                    Incidence("camera", "release", "release", 2)
                )
            },
            graphDefinition("mobile-purchase", "5d30a93454eb79a62928dba66a0ff1fcc78f79f081b07b972a19bee696c90b66") {
                region("device", "Mobile device")
                region("edge", "Edge services")
                node("route", "Purchase route", "N12", "device", ports = listOf(
                    PortDefinition("order", "P11", "OrderRouteV1", PortPolarity.Offer)
                ))
                node("checkout", "Checkout logic", "N02", "device", ports = listOf(
                    PortDefinition("order", "P11", "OrderRouteV1", PortPolarity.Require),
                    PortDefinition("grant", "P20", "PurchaseGrantV1", PortPolarity.Require),
                    PortDefinition("pay", "P02", "ChargeV1", PortPolarity.Require),
                    PortDefinition("commit", "P02", "PurchaseCommitV1", PortPolarity.Require),
                    PortDefinition("intent", "P24", "PurchaseIntentV1", PortPolarity.Neutral)
                ))
                node("session", "Principal boundary", "N33", "edge", ports = listOf(
                    PortDefinition("grant", "P20", "PurchaseGrantV1", PortPolarity.Offer)
                ))
                node("payment", "Payment adapter", "N30", "edge", ports = listOf(
                    PortDefinition("charge", "P02", "ChargeV1", PortPolarity.Offer),
                    PortDefinition("effect", "P24", "ExternalEffectRefV1", PortPolarity.Neutral)
                ))
                node("ledger", "Purchase ledger", "N21", "edge", ports = listOf(
                    PortDefinition("commit", "P02", "PurchaseCommitV1", PortPolarity.Offer),
                    PortDefinition("receipt", "P03", "PurchaseReceiptV1", PortPolarity.Offer),
                    PortDefinition("authority", "P24", "TransactionAuthorityV1", PortPolarity.Neutral)
                ))
                node("events", "Receipt outbox", "N24", "edge", ports = listOf(
                    PortDefinition("receipt", "P03", "PurchaseReceiptV1", PortPolarity.Require)
                ))
                relation("entry", "H36",
                    Incidence("route", "order", "provider", 0),
                    Incidence("checkout", "order", "consumer", 0)
                )
                relation("auth", "H23",
                    Incidence("session", "grant", "provider", 0),
                    Incidence("checkout", "grant", "consumer", 0)
                )
                relation("pay", "H05",
                    Incidence("payment", "charge", "provider", 0),
                    Incidence("checkout", "pay", "consumer", 0)
                )
                relation("commit", "H05",
                    Incidence("ledger", "commit", "provider", 0),
                    Incidence("checkout", "commit", "consumer", 0)
                )
                relation("outbox", "H02",
                    Incidence("ledger", "receipt", "provider", 0),
                    Incidence("events", "receipt", "consumer", 0)
                )
                relation("purchase", "H16",
                    Incidence("ledger", "authority", "authority", 0),
                    Incidence("checkout", "intent", "intent", 1),
                    Incidence("payment", "effect", "externalEffect", 2)
                )
            },
            graphDefinition("mobile-feature-update", "dfd0f654fb38c3b54d0b8241a5bfe11e1505a653c827882767c264dc12b251c5") {
                region("device", "Mobile device")
                region("edge", "Edge services")
                node("shell", "Stable app shell", "N27", "device", ports = listOf(
                    PortDefinition("routes", "P19", "RouteExportsV1", PortPolarity.Require),
                    PortDefinition("feature", "P02", "FeatureContractV1", PortPolarity.Require)
                ))
                node("routes", "Feature route exports", "N12", "device", ports = listOf(
                    PortDefinition("export", "P19", "RouteExportsV1", PortPolarity.Offer)
                ))
                node("old", "Active feature epoch", "N10", "device", ports = listOf(
                    PortDefinition("feature", "P02", "FeatureContractV1", PortPolarity.Offer),
                    PortDefinition("epoch", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("loader", "JS host / FFI adapter", "N15", "device", ports = listOf(
                    PortDefinition("bundle", "P23", "BundleRefV1", PortPolarity.Require),
                    PortDefinition("admission", "P20", "AdmissionV1", PortPolarity.Require),
                    PortDefinition("publisher", "P24", "HostRefV1", PortPolarity.Neutral)
                ))
                node("build", "Feature packager", "N34", "edge", ports = listOf(
                    PortDefinition("bundle", "P23", "BundleRefV1", PortPolarity.Offer)
                ))
                node("artifact", "Signed bundle", "N35", "edge", ports = listOf(
                    PortDefinition("input", "P23", "BundleRefV1", PortPolarity.Require),
                    PortDefinition("bundle", "P23", "BundleRefV1", PortPolarity.Offer),
                    PortDefinition("revision", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                node("gate", "Host admission gate", "N33", "device", ports = listOf(
                    PortDefinition("decision", "P20", "AdmissionV1", PortPolarity.Offer)
                ))
                relation("routes", "H30",
                    Incidence("routes", "export", "provider", 0),
                    Incidence("shell", "routes", "consumer", 0)
                )
                relation("calls", "H05",
                    Incidence("old", "feature", "provider", 0),
                    Incidence("shell", "feature", "consumer", 0)
                )
                relation("bundle", "H26",
                    Incidence("build", "bundle", "provider", 0),
                    Incidence("artifact", "input", "consumer", 0)
                )
                relation("fetch", "H26",
                    Incidence("artifact", "bundle", "provider", 0),
                    Incidence("loader", "bundle", "consumer", 0)
                )
                relation("admit", "H23",
                    Incidence("gate", "decision", "provider", 0),
                    Incidence("loader", "admission", "consumer", 0)
                )
                relation("cohort", "H29",
                    Incidence("old", "epoch", "oldActivation", 0),
                    Incidence("artifact", "revision", "candidate", 1),
                    Incidence("loader", "publisher", "publisher", 2)
                )
            },
            graphDefinition("web-content", "fd73fca88045ec746eb62d7e7fc93f25ca2aa0e690cc658cc2b300ec679e6cf5") {
                region("browser", "Browser")
                region("edge", "Edge services")
                region("data", "Data authorities")
                node("route", "URL routes", "N12", "browser", ports = listOf(
                    PortDefinition("page", "P11", "PageRouteV1", PortPolarity.Offer)
                ))
                node("reader", "Page renderer", "N11", "browser", ports = listOf(
                    PortDefinition("route", "P11", "PageRouteV1", PortPolarity.Require),
                    PortDefinition("fetch", "P02", "PageReadV1", PortPolarity.Require)
                ))
                node("edge", "Edge page service", "N19", "edge", ports = listOf(
                    PortDefinition("read", "P02", "PageReadV1", PortPolarity.Offer),
                    PortDefinition("cache", "P02", "VersionedPageV1", PortPolarity.Require)
                ))
                node("cache", "Revision cache", "N25", "edge", ports = listOf(
                    PortDefinition("lookup", "P02", "VersionedPageV1", PortPolarity.Offer),
                    PortDefinition("artifact", "P23", "PageArtifactV1", PortPolarity.Require),
                    PortDefinition("served", "P24", "PublicationRefV1", PortPolarity.Neutral)
                ))
                node("cms", "Content authority", "N21", "data", ports = listOf(
                    PortDefinition("content", "P01", "ContentRevisionV1", PortPolarity.Offer),
                    PortDefinition("source", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                node("build", "Page compiler", "N34", "data", ports = listOf(
                    PortDefinition("source", "P01", "ContentRevisionV1", PortPolarity.Require),
                    PortDefinition("page", "P23", "PageArtifactV1", PortPolarity.Offer),
                    PortDefinition("artifact", "P24", "ArtifactRefV1", PortPolarity.Neutral)
                ))
                relation("route", "H36",
                    Incidence("route", "page", "provider", 0),
                    Incidence("reader", "route", "consumer", 0)
                )
                relation("fetch", "H05",
                    Incidence("edge", "read", "provider", 0),
                    Incidence("reader", "fetch", "consumer", 0)
                )
                relation("cache", "H05",
                    Incidence("cache", "lookup", "provider", 0),
                    Incidence("edge", "cache", "consumer", 0)
                )
                relation("source", "H02",
                    Incidence("cms", "content", "provider", 0),
                    Incidence("build", "source", "consumer", 0)
                )
                relation("publish", "H26",
                    Incidence("build", "page", "provider", 0),
                    Incidence("cache", "artifact", "consumer", 0)
                )
                relation("provenance", "H27",
                    Incidence("cms", "source", "source", 0),
                    Incidence("build", "artifact", "artifact", 1),
                    Incidence("cache", "served", "served", 2)
                )
            },
            graphDefinition("web-commerce", "b8f8390ef70aa6f93223c465061ddbb86b45c2508669fbe8f280f44aeee945ca") {
                region("browser", "Browser")
                region("edge", "Edge services")
                region("data", "Data authorities")
                node("view", "Checkout Surface", "N11", "browser", ports = listOf(
                    PortDefinition("order", "P04", "OrderIntentV1", PortPolarity.Offer)
                ))
                node("order", "Order saga", "N06", "edge", ports = listOf(
                    PortDefinition("intent", "P04", "OrderIntentV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "CheckoutGrantV1", PortPolarity.Require),
                    PortDefinition("reserve", "P02", "ReservationV1", PortPolarity.Require),
                    PortDefinition("payment", "P02", "ChargeV1", PortPolarity.Require),
                    PortDefinition("dispatch", "P03", "DispatchV1", PortPolarity.Offer),
                    PortDefinition("compensation", "P24", "SagaRefV1", PortPolarity.Neutral)
                ))
                node("auth", "Tenant / principal gate", "N33", "edge", ports = listOf(
                    PortDefinition("grant", "P20", "CheckoutGrantV1", PortPolarity.Offer)
                ))
                node("stock", "Inventory authority", "N21", "data", ports = listOf(
                    PortDefinition("reserve", "P02", "ReservationV1", PortPolarity.Offer),
                    PortDefinition("reservation", "P24", "ReservationRefV1", PortPolarity.Neutral)
                ))
                node("payment", "Payment provider", "N30", "data", ports = listOf(
                    PortDefinition("charge", "P02", "ChargeV1", PortPolarity.Offer),
                    PortDefinition("chargeRef", "P24", "ChargeRefV1", PortPolarity.Neutral)
                ))
                node("fulfil", "Fulfilment queue", "N24", "data", ports = listOf(
                    PortDefinition("command", "P03", "DispatchV1", PortPolarity.Require)
                ))
                relation("intent", "H02",
                    Incidence("view", "order", "provider", 0),
                    Incidence("order", "intent", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("auth", "grant", "provider", 0),
                    Incidence("order", "authority", "consumer", 0)
                )
                relation("stock", "H05",
                    Incidence("stock", "reserve", "provider", 0),
                    Incidence("order", "reserve", "consumer", 0)
                )
                relation("payment", "H05",
                    Incidence("payment", "charge", "provider", 0),
                    Incidence("order", "payment", "consumer", 0)
                )
                relation("dispatch", "H02",
                    Incidence("order", "dispatch", "provider", 0),
                    Incidence("fulfil", "command", "consumer", 0)
                )
                relation("saga", "H17",
                    Incidence("stock", "reservation", "reservation", 0),
                    Incidence("payment", "chargeRef", "charge", 1),
                    Incidence("order", "compensation", "coordinator", 2)
                )
            },
            graphDefinition("web-collaboration", "5c6fa3ef17b10ec413e1e7580cdf88574aee3e4f70888ee887e8c3e6b1c1f0f9") {
                region("browser", "Browser")
                region("edge", "Edge services")
                region("data", "Data authorities")
                node("editor", "Document Surface", "N11", "browser", ports = listOf(
                    PortDefinition("delta", "P04", "CRDTDeltaV1", PortPolarity.Offer)
                ))
                node("replica", "Local CRDT replica", "N25", "browser", ports = listOf(
                    PortDefinition("delta", "P04", "CRDTDeltaV1", PortPolarity.Require),
                    PortDefinition("session", "P07", "DocumentSessionV1", PortPolarity.Require),
                    PortDefinition("state", "P24", "ReplicaRefV1", PortPolarity.Neutral)
                ))
                node("room", "Room actor", "N05", "edge", ports = listOf(
                    PortDefinition("session", "P07", "DocumentSessionV1", PortPolarity.Offer),
                    PortDefinition("log", "P02", "DocumentAppendV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "DocumentGrantV1", PortPolarity.Require),
                    PortDefinition("presence", "P04", "PresenceV1", PortPolarity.Offer),
                    PortDefinition("epoch", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Workspace policy", "N33", "edge", ports = listOf(
                    PortDefinition("grant", "P20", "DocumentGrantV1", PortPolarity.Offer)
                ))
                node("log", "Document update log", "N24", "data", ports = listOf(
                    PortDefinition("append", "P02", "DocumentAppendV1", PortPolarity.Offer),
                    PortDefinition("position", "P24", "LogPositionV1", PortPolarity.Neutral)
                ))
                node("presence", "Presence TTL view", "N22", "edge", ports = listOf(
                    PortDefinition("events", "P04", "PresenceV1", PortPolarity.Require)
                ))
                relation("edit", "H02",
                    Incidence("editor", "delta", "provider", 0),
                    Incidence("replica", "delta", "consumer", 0)
                )
                relation("session", "H06",
                    Incidence("room", "session", "provider", 0),
                    Incidence("replica", "session", "consumer", 0)
                )
                relation("persist", "H05",
                    Incidence("log", "append", "provider", 0),
                    Incidence("room", "log", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("room", "authority", "consumer", 0)
                )
                relation("presence", "H02",
                    Incidence("room", "presence", "provider", 0),
                    Incidence("presence", "events", "consumer", 0)
                )
                relation("replicas", "H14",
                    Incidence("replica", "state", "local", 0),
                    Incidence("log", "position", "durable", 1),
                    Incidence("room", "epoch", "coordinator", 2)
                )
            },
            graphDefinition("web-manna-docs", "d8473f6e841ee283895170c8af56c0af28ae8781077ec50ec1da959a23a840fe") {
                region("browser", "Browser")
                region("edge", "Edge services")
                region("data", "Data authorities")
                node("reader", "Native documentation reader", "N11", "browser", ports = listOf(
                    PortDefinition("request", "P02", "DocumentationReadV1", PortPolarity.Require),
                    PortDefinition("answer", "P01", "CitedAnswerV1", PortPolarity.Require),
                    PortDefinition("questionRef", "P24", "QuestionRefV1", PortPolarity.Neutral)
                ))
                node("api", "Documentation service", "N19", "edge", ports = listOf(
                    PortDefinition("read", "P02", "DocumentationReadV1", PortPolarity.Offer),
                    PortDefinition("corpus", "P02", "CorpusReadV1", PortPolarity.Require),
                    PortDefinition("sections", "P01", "SectionPackV1", PortPolarity.Offer),
                    PortDefinition("export", "P03", "ExportIntentV1", PortPolarity.Offer)
                ))
                node("corpus", "Revisioned docs corpus", "N26", "data", ports = listOf(
                    PortDefinition("fetch", "P02", "CorpusReadV1", PortPolarity.Offer)
                ))
                node("retrieval", "Bounded section retrieval", "N22", "edge", ports = listOf(
                    PortDefinition("documents", "P01", "SectionPackV1", PortPolarity.Require),
                    PortDefinition("context", "P01", "CitedContextV1", PortPolarity.Offer),
                    PortDefinition("sourceRefs", "P24", "SourceRefSetV1", PortPolarity.Neutral)
                ))
                node("model", "Answer model adapter", "N18", "edge", ports = listOf(
                    PortDefinition("context", "P01", "CitedContextV1", PortPolarity.Require),
                    PortDefinition("answer", "P01", "CitedAnswerV1", PortPolarity.Offer),
                    PortDefinition("answerRef", "P24", "AnswerRefV1", PortPolarity.Neutral)
                ))
                node("job", "PDF export workflow", "N06", "edge", ports = listOf(
                    PortDefinition("start", "P03", "ExportIntentV1", PortPolarity.Require),
                    PortDefinition("output", "P02", "EditionPutV1", PortPolarity.Require)
                ))
                node("pdf", "Reading edition store", "N26", "data", ports = listOf(
                    PortDefinition("put", "P02", "EditionPutV1", PortPolarity.Offer)
                ))
                relation("request", "H05",
                    Incidence("api", "read", "provider", 0),
                    Incidence("reader", "request", "consumer", 0)
                )
                relation("source", "H05",
                    Incidence("corpus", "fetch", "provider", 0),
                    Incidence("api", "corpus", "consumer", 0)
                )
                relation("sections", "H02",
                    Incidence("api", "sections", "provider", 0),
                    Incidence("retrieval", "documents", "consumer", 0)
                )
                relation("context", "H02",
                    Incidence("retrieval", "context", "provider", 0),
                    Incidence("model", "context", "consumer", 0)
                )
                relation("answer", "H02",
                    Incidence("model", "answer", "provider", 0),
                    Incidence("reader", "answer", "consumer", 0)
                )
                relation("export", "H03",
                    Incidence("api", "export", "provider", 0),
                    Incidence("job", "start", "consumer", 0)
                )
                relation("store", "H05",
                    Incidence("pdf", "put", "provider", 0),
                    Incidence("job", "output", "consumer", 0)
                )
                relation("evidence", "H28",
                    Incidence("reader", "questionRef", "question", 0),
                    Incidence("retrieval", "sourceRefs", "sources", 1),
                    Incidence("model", "answerRef", "answer", 2)
                )
            },
            graphDefinition("web-multitenant", "d818d03b64431dcd22809056e58e4e456ba90b6e7b2afdcbddce938cf6d7f0ef") {
                region("browser", "Browser")
                region("edge", "Edge services")
                region("data", "Data authorities")
                node("route", "Workspace route", "N12", "browser", ports = listOf(
                    PortDefinition("workspace", "P11", "WorkspaceRouteV1", PortPolarity.Offer)
                ))
                node("surface", "Dashboard Surface", "N11", "browser", ports = listOf(
                    PortDefinition("route", "P11", "WorkspaceRouteV1", PortPolarity.Require),
                    PortDefinition("read", "P02", "DashboardReadV1", PortPolarity.Require)
                ))
                node("verify", "Principal verifier", "N33", "edge", ports = listOf(
                    PortDefinition("context", "P20", "VerifiedContextV1", PortPolarity.Offer),
                    PortDefinition("principalRef", "P24", "PrincipalRefV1", PortPolarity.Neutral)
                ))
                node("service", "Dashboard service", "N19", "edge", ports = listOf(
                    PortDefinition("principal", "P20", "VerifiedContextV1", PortPolarity.Require),
                    PortDefinition("dashboard", "P02", "DashboardReadV1", PortPolarity.Offer),
                    PortDefinition("policy", "P20", "DataGrantV1", PortPolarity.Require),
                    PortDefinition("storage", "P02", "ScopedQueryV1", PortPolarity.Require)
                ))
                node("policy", "Row / resource policy", "N33", "data", ports = listOf(
                    PortDefinition("grant", "P20", "DataGrantV1", PortPolarity.Offer),
                    PortDefinition("policyRef", "P24", "PolicyRefV1", PortPolarity.Neutral)
                ))
                node("db", "Workspace authority", "N21", "data", ports = listOf(
                    PortDefinition("query", "P02", "ScopedQueryV1", PortPolarity.Offer),
                    PortDefinition("authorityRef", "P24", "AuthorityRefV1", PortPolarity.Neutral)
                ))
                relation("route", "H36",
                    Incidence("route", "workspace", "provider", 0),
                    Incidence("surface", "route", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("verify", "context", "provider", 0),
                    Incidence("service", "principal", "consumer", 0)
                )
                relation("read", "H05",
                    Incidence("service", "dashboard", "provider", 0),
                    Incidence("surface", "read", "consumer", 0)
                )
                relation("policy", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("service", "policy", "consumer", 0)
                )
                relation("query", "H05",
                    Incidence("db", "query", "provider", 0),
                    Incidence("service", "storage", "consumer", 0)
                )
                relation("scope", "H34",
                    Incidence("verify", "principalRef", "principal", 0),
                    Incidence("policy", "policyRef", "policy", 1),
                    Incidence("db", "authorityRef", "authority", 2)
                )
            },
            graphDefinition("distributed-events", "52615423ff5461a87601f2e0eeb53dc8e6fa5cdb5a76afc3c31dbab2ce28f97e") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("ingest", "Admission service", "N19", "control", ports = listOf(
                    PortDefinition("command", "P03", "WorkCommandV1", PortPolarity.Offer),
                    PortDefinition("operation", "P24", "OperationRefV1", PortPolarity.Neutral)
                ))
                node("queue", "Durable queue", "N24", "data", ports = listOf(
                    PortDefinition("command", "P03", "WorkCommandV1", PortPolarity.Require),
                    PortDefinition("delivery", "P04", "DeliveryV1", PortPolarity.Offer),
                    PortDefinition("deliveryRef", "P24", "DeliveryRefV1", PortPolarity.Neutral)
                ))
                node("worker", "Processing actor", "N05", "data", ports = listOf(
                    PortDefinition("delivery", "P04", "DeliveryV1", PortPolarity.Require),
                    PortDefinition("effect", "P02", "EffectCommitV1", PortPolarity.Require),
                    PortDefinition("span", "P24", "TraceV1", PortPolarity.Offer)
                ))
                node("ledger", "Effect ledger", "N21", "data", ports = listOf(
                    PortDefinition("commit", "P02", "EffectCommitV1", PortPolarity.Offer),
                    PortDefinition("events", "P04", "CommittedEventV1", PortPolarity.Offer),
                    PortDefinition("receipt", "P24", "ReceiptRefV1", PortPolarity.Neutral)
                ))
                node("view", "Materialized view", "N22", "data", ports = listOf(
                    PortDefinition("events", "P04", "CommittedEventV1", PortPolarity.Require)
                ))
                node("trace", "Telemetry collector", "N38", "control", ports = listOf(
                    PortDefinition("span", "P24", "TraceV1", PortPolarity.Require)
                ))
                relation("admit", "H02",
                    Incidence("ingest", "command", "provider", 0),
                    Incidence("queue", "command", "consumer", 0)
                )
                relation("delivery", "H02",
                    Incidence("queue", "delivery", "provider", 0),
                    Incidence("worker", "delivery", "consumer", 0)
                )
                relation("commit", "H05",
                    Incidence("ledger", "commit", "provider", 0),
                    Incidence("worker", "effect", "consumer", 0)
                )
                relation("project", "H02",
                    Incidence("ledger", "events", "provider", 0),
                    Incidence("view", "events", "consumer", 0)
                )
                relation("trace", "H04",
                    Incidence("worker", "span", "provider", 0),
                    Incidence("trace", "span", "consumer", 0)
                )
                relation("lineage", "H27",
                    Incidence("ingest", "operation", "intent", 0),
                    Incidence("queue", "deliveryRef", "delivery", 1),
                    Incidence("ledger", "receipt", "effect", 2)
                )
            },
            graphDefinition("distributed-actors", "b2f3314335b1265d88480ce29ed2a486622b73270072b98f761b71365ebeaf89") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("registry", "Placement registry", "N21", "control", ports = listOf(
                    PortDefinition("map", "P01", "PlacementMapV1", PortPolarity.Offer)
                ))
                node("router", "Key router", "N22", "control", ports = listOf(
                    PortDefinition("placement", "P01", "PlacementMapV1", PortPolarity.Require),
                    PortDefinition("a", "P02", "ActorCommandV1", PortPolarity.Require),
                    PortDefinition("b", "P02", "ActorCommandV1", PortPolarity.Require),
                    PortDefinition("keyspace", "P24", "KeyspaceRefV1", PortPolarity.Neutral)
                ))
                node("supervisor", "Actor supervisor", "N05", "data", ports = listOf(
                    PortDefinition("supervisor", "P24", "SupervisorRefV1", PortPolarity.Neutral)
                ))
                node("a", "Actor shard A", "N05", "data", ports = listOf(
                    PortDefinition("mailbox", "P02", "ActorCommandV1", PortPolarity.Offer),
                    PortDefinition("log", "P02", "ActorAppendV1", PortPolarity.Require),
                    PortDefinition("range", "P24", "RangeRefV1", PortPolarity.Neutral),
                    PortDefinition("activation", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("b", "Actor shard B", "N05", "data", ports = listOf(
                    PortDefinition("mailbox", "P02", "ActorCommandV1", PortPolarity.Offer),
                    PortDefinition("log", "P02", "ActorAppendV1", PortPolarity.Require),
                    PortDefinition("range", "P24", "RangeRefV1", PortPolarity.Neutral),
                    PortDefinition("activation", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("log", "State log", "N24", "data", ports = listOf(
                    PortDefinition("appendA", "P02", "ActorAppendV1", PortPolarity.Offer),
                    PortDefinition("appendB", "P02", "ActorAppendV1", PortPolarity.Offer)
                ))
                relation("placement", "H02",
                    Incidence("registry", "map", "provider", 0),
                    Incidence("router", "placement", "consumer", 0)
                )
                relation("a", "H05",
                    Incidence("a", "mailbox", "provider", 0),
                    Incidence("router", "a", "consumer", 0)
                )
                relation("b", "H05",
                    Incidence("b", "mailbox", "provider", 0),
                    Incidence("router", "b", "consumer", 0)
                )
                relation("logA", "H05",
                    Incidence("log", "appendA", "provider", 0),
                    Incidence("a", "log", "consumer", 0)
                )
                relation("logB", "H05",
                    Incidence("log", "appendB", "provider", 0),
                    Incidence("b", "log", "consumer", 0)
                )
                relation("partition", "H15",
                    Incidence("router", "keyspace", "router", 0),
                    Incidence("a", "range", "shard", 1),
                    Incidence("b", "range", "shard", 2)
                )
                relation("supervision", "H18",
                    Incidence("supervisor", "supervisor", "supervisor", 0),
                    Incidence("a", "activation", "child", 1),
                    Incidence("b", "activation", "child", 2)
                )
            },
            graphDefinition("distributed-saga", "8b8092117f03ae98324ad886acabb4bd540beea323873fcd1649648aa2cf210b") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("coordinator", "Durable saga coordinator", "N06", "control", ports = listOf(
                    PortDefinition("stock", "P02", "ReservationV1", PortPolarity.Require),
                    PortDefinition("payment", "P02", "ChargeV1", PortPolarity.Require),
                    PortDefinition("dispatch", "P02", "DispatchV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "OrderGrantV1", PortPolarity.Require),
                    PortDefinition("state", "P02", "SagaCommitV1", PortPolarity.Require),
                    PortDefinition("step", "P24", "SagaStepRefV1", PortPolarity.Neutral)
                ))
                node("approve", "Order approval", "N33", "control", ports = listOf(
                    PortDefinition("grant", "P20", "OrderGrantV1", PortPolarity.Offer)
                ))
                node("stock", "Stock service", "N19", "data", ports = listOf(
                    PortDefinition("reserve", "P02", "ReservationV1", PortPolarity.Offer),
                    PortDefinition("release", "P24", "CompensationRefV1", PortPolarity.Neutral)
                ))
                node("payment", "Payment service", "N19", "data", ports = listOf(
                    PortDefinition("charge", "P02", "ChargeV1", PortPolarity.Offer),
                    PortDefinition("refund", "P24", "CompensationRefV1", PortPolarity.Neutral)
                ))
                node("dispatch", "Dispatch service", "N19", "data", ports = listOf(
                    PortDefinition("submit", "P02", "DispatchV1", PortPolarity.Offer),
                    PortDefinition("cancel", "P24", "CompensationRefV1", PortPolarity.Neutral)
                ))
                node("journal", "Saga journal", "N21", "control", ports = listOf(
                    PortDefinition("commit", "P02", "SagaCommitV1", PortPolarity.Offer)
                ))
                relation("stock", "H05",
                    Incidence("stock", "reserve", "provider", 0),
                    Incidence("coordinator", "stock", "consumer", 0)
                )
                relation("pay", "H05",
                    Incidence("payment", "charge", "provider", 0),
                    Incidence("coordinator", "payment", "consumer", 0)
                )
                relation("ship", "H05",
                    Incidence("dispatch", "submit", "provider", 0),
                    Incidence("coordinator", "dispatch", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("approve", "grant", "provider", 0),
                    Incidence("coordinator", "authority", "consumer", 0)
                )
                relation("journal", "H05",
                    Incidence("journal", "commit", "provider", 0),
                    Incidence("coordinator", "state", "consumer", 0)
                )
                relation("compensation", "H17",
                    Incidence("coordinator", "step", "coordinator", 0),
                    Incidence("stock", "release", "stock", 1),
                    Incidence("payment", "refund", "payment", 2),
                    Incidence("dispatch", "cancel", "dispatch", 3)
                )
            },
            graphDefinition("distributed-hybrid", "32911535d1e802871c9e87232134d28060f0619f3c2f6d59e15517810ee6325d") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("edge", "Edge admission", "N27", "control", ports = listOf(
                    PortDefinition("authority", "P20", "WorkloadGrantV1", PortPolarity.Require),
                    PortDefinition("scheduler", "P02", "AllocateV1", PortPolarity.Require),
                    PortDefinition("workload", "P24", "WorkloadRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Workload policy", "N33", "control", ports = listOf(
                    PortDefinition("grant", "P20", "WorkloadGrantV1", PortPolarity.Offer)
                ))
                node("scheduler", "Regional scheduler", "N28", "control", ports = listOf(
                    PortDefinition("allocate", "P02", "AllocateV1", PortPolarity.Offer),
                    PortDefinition("region", "P24", "RegionRefV1", PortPolarity.Neutral)
                ))
                node("native", "Native compute host", "N16", "data", ports = listOf(
                    PortDefinition("input", "P02", "ArtifactReadV1", PortPolarity.Require),
                    PortDefinition("output", "P02", "ArtifactWriteV1", PortPolarity.Require),
                    PortDefinition("receipt", "P03", "ComputeReceiptV1", PortPolarity.Offer),
                    PortDefinition("host", "P24", "HostProfileV1", PortPolarity.Neutral)
                ))
                node("artifact", "Input / result artifacts", "N26", "data", ports = listOf(
                    PortDefinition("read", "P02", "ArtifactReadV1", PortPolarity.Offer),
                    PortDefinition("write", "P02", "ArtifactWriteV1", PortPolarity.Offer)
                ))
                node("result", "Result service", "N19", "control", ports = listOf(
                    PortDefinition("receipt", "P03", "ComputeReceiptV1", PortPolarity.Require)
                ))
                relation("authorize", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("edge", "authority", "consumer", 0)
                )
                relation("place", "H05",
                    Incidence("scheduler", "allocate", "provider", 0),
                    Incidence("edge", "scheduler", "consumer", 0)
                )
                relation("input", "H05",
                    Incidence("artifact", "read", "provider", 0),
                    Incidence("native", "input", "consumer", 0)
                )
                relation("output", "H05",
                    Incidence("artifact", "write", "provider", 0),
                    Incidence("native", "output", "consumer", 0)
                )
                relation("receipt", "H02",
                    Incidence("native", "receipt", "provider", 0),
                    Incidence("result", "receipt", "consumer", 0)
                )
                relation("placement", "H21",
                    Incidence("scheduler", "region", "region", 0),
                    Incidence("native", "host", "host", 1),
                    Incidence("edge", "workload", "workload", 2)
                )
            },
            graphDefinition("distributed-release", "8e854359c8e4e8a7da2e4149c7cfeed85fe3ab5897c4b598c0a363c93c49bd88") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("build", "Compiler / packager", "N34", "control", ports = listOf(
                    PortDefinition("artifacts", "P23", "ArtifactVectorV1", PortPolarity.Offer)
                ))
                node("bundle", "Release artifacts", "N35", "control", ports = listOf(
                    PortDefinition("input", "P23", "ArtifactVectorV1", PortPolarity.Require),
                    PortDefinition("artifacts", "P23", "ArtifactVectorV1", PortPolarity.Offer),
                    PortDefinition("revision", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                node("shadow", "Shadow scenarios", "N36", "control", ports = listOf(
                    PortDefinition("candidate", "P23", "ArtifactVectorV1", PortPolarity.Require),
                    PortDefinition("evidence", "P24", "EvidenceSetV1", PortPolarity.Offer)
                ))
                node("gate", "Independent release gate", "N33", "control", ports = listOf(
                    PortDefinition("evidence", "P24", "EvidenceSetV1", PortPolarity.Require),
                    PortDefinition("decision", "P20", "ReleaseDecisionV1", PortPolarity.Offer)
                ))
                node("rollout", "Rollout controller", "N40", "control", ports = listOf(
                    PortDefinition("authority", "P20", "ReleaseDecisionV1", PortPolarity.Require),
                    PortDefinition("a", "P02", "AdmissionRequestV1", PortPolarity.Require),
                    PortDefinition("b", "P02", "AdmissionRequestV1", PortPolarity.Require),
                    PortDefinition("reconciler", "P24", "ControllerRefV1", PortPolarity.Neutral)
                ))
                node("a", "Host cohort A", "N27", "data", ports = listOf(
                    PortDefinition("admit", "P02", "AdmissionRequestV1", PortPolarity.Offer),
                    PortDefinition("epoch", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("b", "Host cohort B", "N27", "data", ports = listOf(
                    PortDefinition("admit", "P02", "AdmissionRequestV1", PortPolarity.Offer),
                    PortDefinition("epoch", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                relation("bundle", "H26",
                    Incidence("build", "artifacts", "provider", 0),
                    Incidence("bundle", "input", "consumer", 0)
                )
                relation("test", "H26",
                    Incidence("bundle", "artifacts", "provider", 0),
                    Incidence("shadow", "candidate", "consumer", 0)
                )
                relation("proof", "H28",
                    Incidence("shadow", "evidence", "provider", 0),
                    Incidence("gate", "evidence", "consumer", 0)
                )
                relation("decision", "H23",
                    Incidence("gate", "decision", "provider", 0),
                    Incidence("rollout", "authority", "consumer", 0)
                )
                relation("a", "H05",
                    Incidence("a", "admit", "provider", 0),
                    Incidence("rollout", "a", "consumer", 0)
                )
                relation("b", "H05",
                    Incidence("b", "admit", "provider", 0),
                    Incidence("rollout", "b", "consumer", 0)
                )
                relation("cohort", "H29",
                    Incidence("bundle", "revision", "candidate", 0),
                    Incidence("a", "epoch", "host", 1),
                    Incidence("b", "epoch", "host", 2),
                    Incidence("rollout", "reconciler", "reconciler", 3)
                )
            },
            graphDefinition("capability-navigation", "ee9f93df80a63c5664c50a0c50bed123a359307443855aae9c8a61084173672e") {
                region("app", "App")
                region("feature", "Account feature", "app")
                region("modal", "Approval modal", "feature")
                node("entry", "Deep-link resolver", "N12", "app", ports = listOf(
                    PortDefinition("destination", "P11", "RouteAddressV1", PortPolarity.Offer)
                ))
                node("container", "Feature container", "N10", "app", ports = listOf(
                    PortDefinition("destination", "P11", "RouteAddressV1", PortPolarity.Require),
                    PortDefinition("child", "P19", "AccountRouteV1", PortPolarity.Require),
                    PortDefinition("owner", "P24", "OwnerRefV1", PortPolarity.Neutral)
                ))
                node("route", "Account route", "N12", "feature", ports = listOf(
                    PortDefinition("export", "P19", "AccountRouteV1", PortPolarity.Offer),
                    PortDefinition("destination", "P11", "AccountRouteV1", PortPolarity.Offer),
                    PortDefinition("authority", "P20", "RouteGrantV1", PortPolarity.Require),
                    PortDefinition("owned", "P24", "DefinitionRefV1", PortPolarity.Neutral)
                ))
                node("screen", "Account Surface", "N11", "feature", ports = listOf(
                    PortDefinition("route", "P11", "AccountRouteV1", PortPolarity.Require),
                    PortDefinition("approve", "P11", "ApprovalRouteV1", PortPolarity.Offer)
                ))
                node("modal", "Approval Surface", "N11", "modal", ports = listOf(
                    PortDefinition("route", "P11", "ApprovalRouteV1", PortPolarity.Require),
                    PortDefinition("continuation", "P24", "ContinuationRefV1", PortPolarity.Neutral)
                ))
                node("gate", "Route policy", "N33", "app", ports = listOf(
                    PortDefinition("grant", "P20", "RouteGrantV1", PortPolarity.Offer)
                ))
                relation("entry", "H36",
                    Incidence("entry", "destination", "provider", 0),
                    Incidence("container", "destination", "consumer", 0)
                )
                relation("export", "H30",
                    Incidence("route", "export", "provider", 0),
                    Incidence("container", "child", "consumer", 0)
                )
                relation("screen", "H36",
                    Incidence("route", "destination", "provider", 0),
                    Incidence("screen", "route", "consumer", 0)
                )
                relation("modal", "H36",
                    Incidence("screen", "approve", "provider", 0),
                    Incidence("modal", "route", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("gate", "grant", "provider", 0),
                    Incidence("route", "authority", "consumer", 0)
                )
                relation("ownership", "H19",
                    Incidence("container", "owner", "parent", 0),
                    Incidence("route", "owned", "child", 1),
                    Incidence("modal", "continuation", "return", 2)
                )
            },
            graphDefinition("capability-auth", "da11babd2039ade15e3cdeceec7954abf8f1c9e1ceac913c8ff7ff12ec3ac849") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("human", "Human principal", "N33", "control", ports = listOf(
                    PortDefinition("delegation", "P24", "PrincipalRefV1", PortPolarity.Neutral)
                ))
                node("agent", "Agent proposer", "N39", "control", ports = listOf(
                    PortDefinition("proposal", "P01", "EffectProposalV1", PortPolarity.Offer),
                    PortDefinition("identity", "P24", "PrincipalRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Policy evaluator", "N33", "control", ports = listOf(
                    PortDefinition("decision", "P20", "PolicyDecisionV1", PortPolarity.Offer)
                ))
                node("gate", "Effect admission", "N33", "control", ports = listOf(
                    PortDefinition("proposal", "P01", "EffectProposalV1", PortPolarity.Require),
                    PortDefinition("policy", "P20", "PolicyDecisionV1", PortPolarity.Require),
                    PortDefinition("effect", "P02", "PrivilegedOperationV1", PortPolarity.Require),
                    PortDefinition("receipt", "P24", "EffectReceiptV1", PortPolarity.Offer),
                    PortDefinition("admission", "P24", "AuthorityRefV1", PortPolarity.Neutral)
                ))
                node("service", "Privileged service", "N19", "data", ports = listOf(
                    PortDefinition("operation", "P02", "PrivilegedOperationV1", PortPolarity.Offer),
                    PortDefinition("resource", "P24", "ResourceRefV1", PortPolarity.Neutral)
                ))
                node("receipt", "Audit receipt", "N37", "control", ports = listOf(
                    PortDefinition("effect", "P24", "EffectReceiptV1", PortPolarity.Require)
                ))
                relation("proposal", "H02",
                    Incidence("agent", "proposal", "provider", 0),
                    Incidence("gate", "proposal", "consumer", 0)
                )
                relation("decision", "H23",
                    Incidence("policy", "decision", "provider", 0),
                    Incidence("gate", "policy", "consumer", 0)
                )
                relation("execute", "H05",
                    Incidence("service", "operation", "provider", 0),
                    Incidence("gate", "effect", "consumer", 0)
                )
                relation("receipt", "H27",
                    Incidence("gate", "receipt", "provider", 0),
                    Incidence("receipt", "effect", "consumer", 0)
                )
                relation("delegation", "H23",
                    Incidence("human", "delegation", "delegator", 0),
                    Incidence("agent", "identity", "delegate", 1),
                    Incidence("service", "resource", "resource", 2),
                    Incidence("gate", "admission", "admission", 3)
                )
            },
            graphDefinition("capability-telemetry", "82bc1eb404f21646e7ba228dfa6fe2a6b235da41c3b9a8dac6fc6f75905a1444") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("app", "Application operation", "N02", "data", ports = listOf(
                    PortDefinition("span", "P24", "SpanEnvelopeV1", PortPolarity.Offer),
                    PortDefinition("definition", "P24", "DefinitionRefV1", PortPolarity.Neutral)
                ))
                node("interceptor", "Port interceptor", "N38", "data", ports = listOf(
                    PortDefinition("span", "P24", "SpanEnvelopeV1", PortPolarity.Require),
                    PortDefinition("capture", "P20", "CaptureGrantV1", PortPolarity.Require),
                    PortDefinition("envelope", "P01", "RedactedSpanV1", PortPolarity.Offer),
                    PortDefinition("activation", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Capture / privacy policy", "N33", "control", ports = listOf(
                    PortDefinition("grant", "P20", "CaptureGrantV1", PortPolarity.Offer)
                ))
                node("sampler", "Sampling logic", "N01", "data", ports = listOf(
                    PortDefinition("input", "P01", "RedactedSpanV1", PortPolarity.Require),
                    PortDefinition("selected", "P04", "RedactedSpanV1", PortPolarity.Offer)
                ))
                node("exporter", "OTel exporter", "N19", "data", ports = listOf(
                    PortDefinition("input", "P04", "RedactedSpanV1", PortPolarity.Require),
                    PortDefinition("backend", "P02", "TelemetryBatchV1", PortPolarity.Require)
                ))
                node("collector", "Observability backend", "N30", "control", ports = listOf(
                    PortDefinition("ingest", "P02", "TelemetryBatchV1", PortPolarity.Offer),
                    PortDefinition("observation", "P24", "ObservationRefV1", PortPolarity.Neutral)
                ))
                relation("span", "H04",
                    Incidence("app", "span", "provider", 0),
                    Incidence("interceptor", "span", "consumer", 0)
                )
                relation("policy", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("interceptor", "capture", "consumer", 0)
                )
                relation("sample", "H02",
                    Incidence("interceptor", "envelope", "provider", 0),
                    Incidence("sampler", "input", "consumer", 0)
                )
                relation("export", "H02",
                    Incidence("sampler", "selected", "provider", 0),
                    Incidence("exporter", "input", "consumer", 0)
                )
                relation("backend", "H05",
                    Incidence("collector", "ingest", "provider", 0),
                    Incidence("exporter", "backend", "consumer", 0)
                )
                relation("attribution", "H27",
                    Incidence("app", "definition", "definition", 0),
                    Incidence("interceptor", "activation", "activation", 1),
                    Incidence("collector", "observation", "observation", 2)
                )
            },
            graphDefinition("capability-services", "b4bd5132161b90f0191449ba3e53c883ddb5a7230646a82d03be964451d9ca6b") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("client", "Typed client logic", "N02", "control", ports = listOf(
                    PortDefinition("primary", "P02", "SearchV1", PortPolarity.Require),
                    PortDefinition("fallback", "P02", "SearchV1", PortPolarity.Require),
                    PortDefinition("budget", "P08", "CallBudgetV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "ServiceGrantV1", PortPolarity.Require),
                    PortDefinition("selector", "P24", "SelectorRefV1", PortPolarity.Neutral)
                ))
                node("primary", "Primary service", "N19", "data", ports = listOf(
                    PortDefinition("api", "P02", "SearchV1", PortPolarity.Offer),
                    PortDefinition("profile", "P24", "ServiceProfileV1", PortPolarity.Neutral),
                    PortDefinition("implementation", "P24", "ImplementationRefV1", PortPolarity.Neutral)
                ))
                node("fallback", "Qualified fallback", "N19", "data", ports = listOf(
                    PortDefinition("api", "P02", "SearchV1", PortPolarity.Offer),
                    PortDefinition("profile", "P24", "ServiceProfileV1", PortPolarity.Neutral),
                    PortDefinition("implementation", "P24", "ImplementationRefV1", PortPolarity.Neutral)
                ))
                node("breaker", "Circuit / budget state", "N05", "control", ports = listOf(
                    PortDefinition("budget", "P08", "CallBudgetV1", PortPolarity.Offer)
                ))
                node("policy", "Invocation policy", "N33", "control", ports = listOf(
                    PortDefinition("grant", "P20", "ServiceGrantV1", PortPolarity.Offer)
                ))
                node("schema", "Versioned service contract", "N31", "control", ports = listOf(
                    PortDefinition("contract", "P24", "ContractRefV1", PortPolarity.Neutral)
                ))
                relation("primary", "H05",
                    Incidence("primary", "api", "provider", 0),
                    Incidence("client", "primary", "consumer", 0)
                )
                relation("fallback", "H05",
                    Incidence("fallback", "api", "provider", 0),
                    Incidence("client", "fallback", "consumer", 0)
                )
                relation("budget", "H02",
                    Incidence("breaker", "budget", "provider", 0),
                    Incidence("client", "budget", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("client", "authority", "consumer", 0)
                )
                relation("preference", "H33",
                    Incidence("primary", "profile", "preferred", 0),
                    Incidence("fallback", "profile", "fallback", 1),
                    Incidence("client", "selector", "selector", 2)
                )
                relation("contract", "H25",
                    Incidence("schema", "contract", "contract", 0),
                    Incidence("primary", "implementation", "implementation", 1),
                    Incidence("fallback", "implementation", "implementation", 2)
                )
            },
            graphDefinition("capability-partitioning", "53f07b6718ea84a95590b67dfd3164965b70738f10ef7d536d2aef6470bd4210") {
                region("system", "System")
                region("edge", "Edge partition", "system")
                region("native", "Native partition", "system")
                node("planner", "Placement planner", "N28", "system", ports = listOf(
                    PortDefinition("plan", "P24", "PlacementPlanV1", PortPolarity.Neutral)
                ))
                node("edge", "Edge host", "N27", "edge", ports = listOf(
                    PortDefinition("profile", "P24", "HostProfileV1", PortPolarity.Neutral)
                ))
                node("service", "Service export", "N19", "edge", ports = listOf(
                    PortDefinition("compute", "P02", "ComputeV1", PortPolarity.Require),
                    PortDefinition("export", "P24", "ContractRefV1", PortPolarity.Neutral)
                ))
                node("native", "Native host", "N27", "native", ports = listOf(
                    PortDefinition("profile", "P24", "HostProfileV1", PortPolarity.Neutral)
                ))
                node("kernel", "Native algorithm", "N16", "native", ports = listOf(
                    PortDefinition("compute", "P02", "ComputeV1", PortPolarity.Offer),
                    PortDefinition("storage", "P02", "ConditionalStateV1", PortPolarity.Require),
                    PortDefinition("implementation", "P24", "ImplementationRefV1", PortPolarity.Neutral)
                ))
                node("state", "State authority", "N21", "native", ports = listOf(
                    PortDefinition("conditional", "P02", "ConditionalStateV1", PortPolarity.Offer),
                    PortDefinition("fence", "P24", "FenceRefV1", PortPolarity.Neutral)
                ))
                relation("call", "H05",
                    Incidence("kernel", "compute", "provider", 0),
                    Incidence("service", "compute", "consumer", 0)
                )
                relation("state", "H05",
                    Incidence("state", "conditional", "provider", 0),
                    Incidence("kernel", "storage", "consumer", 0)
                )
                relation("placement", "H21",
                    Incidence("planner", "plan", "planner", 0),
                    Incidence("edge", "profile", "edge", 1),
                    Incidence("native", "profile", "native", 2)
                )
                relation("boundary", "H30",
                    Incidence("service", "export", "export", 0),
                    Incidence("kernel", "implementation", "implementation", 1),
                    Incidence("state", "fence", "authority", 2)
                )
            },
            graphDefinition("capability-ffi", "724164211963e316ddea9b20c178c39615ad06a08b19db8f21156cb876ca768e") {
                region("js", "Kotlin/JS host")
                region("native", "Native target adapter")
                node("logic", "Common Kotlin logic", "N01", "js", ports = listOf(
                    PortDefinition("call", "P02", "NativeComputeV1", PortPolarity.Require)
                ))
                node("bridge", "FFI transport adapter", "N15", "js", ports = listOf(
                    PortDefinition("call", "P02", "NativeComputeV1", PortPolarity.Offer),
                    PortDefinition("native", "P02", "NativeComputeV1", PortPolarity.Require),
                    PortDefinition("admission", "P20", "HostGrantV1", PortPolarity.Require),
                    PortDefinition("failure", "P04", "ForeignFailureV1", PortPolarity.Offer),
                    PortDefinition("release", "P24", "ReleaseProtocolV1", PortPolarity.Neutral)
                ))
                node("native", "Foreign function library", "N16", "native", ports = listOf(
                    PortDefinition("compute", "P02", "NativeComputeV1", PortPolarity.Offer),
                    PortDefinition("buffer", "P17", "BufferLeaseV1", PortPolarity.Require),
                    PortDefinition("borrow", "P24", "BorrowRefV1", PortPolarity.Neutral)
                ))
                node("pool", "Native buffer owner", "N26", "native", ports = listOf(
                    PortDefinition("lease", "P17", "BufferLeaseV1", PortPolarity.Offer),
                    PortDefinition("allocator", "P24", "AllocatorRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Host capability gate", "N33", "js", ports = listOf(
                    PortDefinition("grant", "P20", "HostGrantV1", PortPolarity.Offer)
                ))
                node("error", "Error / cancellation adapter", "N15", "js", ports = listOf(
                    PortDefinition("failure", "P04", "ForeignFailureV1", PortPolarity.Require)
                ))
                relation("invoke", "H05",
                    Incidence("bridge", "call", "provider", 0),
                    Incidence("logic", "call", "consumer", 0)
                )
                relation("native", "H05",
                    Incidence("native", "compute", "provider", 0),
                    Incidence("bridge", "native", "consumer", 0)
                )
                relation("buffer", "H01",
                    Incidence("pool", "lease", "provider", 0),
                    Incidence("native", "buffer", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("bridge", "admission", "consumer", 0)
                )
                relation("error", "H02",
                    Incidence("bridge", "failure", "provider", 0),
                    Incidence("error", "failure", "consumer", 0)
                )
                relation("ownership", "H19",
                    Incidence("pool", "allocator", "allocator", 0),
                    Incidence("native", "borrow", "borrower", 1),
                    Incidence("bridge", "release", "adapter", 2)
                )
            },
            graphDefinition("capability-code-push", "7823300823163d4e5ac905949bc0725e0934fc8f6ac1ce9794b4ab1c875c65fc") {
                region("control", "Control plane")
                region("data", "Data plane")
                node("candidate", "Replacement region", "N10", "control", ports = listOf(
                    PortDefinition("source", "P23", "SourceRevisionV1", PortPolarity.Offer),
                    PortDefinition("stateSchema", "P24", "SchemaRefV1", PortPolarity.Neutral),
                    PortDefinition("definition", "P24", "DefinitionRefV1", PortPolarity.Neutral)
                ))
                node("build", "Kotlin/JS packager", "N34", "control", ports = listOf(
                    PortDefinition("source", "P23", "SourceRevisionV1", PortPolarity.Require),
                    PortDefinition("bundle", "P23", "BundleRefV1", PortPolarity.Offer)
                ))
                node("check", "Compatibility / Shadow checks", "N36", "control", ports = listOf(
                    PortDefinition("bundle", "P23", "BundleRefV1", PortPolarity.Require),
                    PortDefinition("evidence", "P24", "EvidenceSetV1", PortPolarity.Offer)
                ))
                node("policy", "Publication authority", "N33", "control", ports = listOf(
                    PortDefinition("evidence", "P24", "EvidenceSetV1", PortPolarity.Require),
                    PortDefinition("decision", "P20", "PublishDecisionV1", PortPolarity.Offer)
                ))
                node("host", "Target host loader", "N27", "data", ports = listOf(
                    PortDefinition("admission", "P20", "PublishDecisionV1", PortPolarity.Require),
                    PortDefinition("state", "P02", "MigrationCommitV1", PortPolarity.Require),
                    PortDefinition("publisher", "P24", "HostRefV1", PortPolarity.Neutral)
                ))
                node("old", "Old activation epoch", "N10", "data", ports = listOf(
                    PortDefinition("stateSchema", "P24", "SchemaRefV1", PortPolarity.Neutral),
                    PortDefinition("epoch", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("state", "State authority", "N21", "data", ports = listOf(
                    PortDefinition("conditional", "P02", "MigrationCommitV1", PortPolarity.Offer),
                    PortDefinition("version", "P24", "StateVersionV1", PortPolarity.Neutral)
                ))
                relation("build", "H26",
                    Incidence("candidate", "source", "provider", 0),
                    Incidence("build", "source", "consumer", 0)
                )
                relation("test", "H26",
                    Incidence("build", "bundle", "provider", 0),
                    Incidence("check", "bundle", "consumer", 0)
                )
                relation("evidence", "H28",
                    Incidence("check", "evidence", "provider", 0),
                    Incidence("policy", "evidence", "consumer", 0)
                )
                relation("publish", "H23",
                    Incidence("policy", "decision", "provider", 0),
                    Incidence("host", "admission", "consumer", 0)
                )
                relation("state", "H05",
                    Incidence("state", "conditional", "provider", 0),
                    Incidence("host", "state", "consumer", 0)
                )
                relation("migration", "H35",
                    Incidence("old", "stateSchema", "old", 0),
                    Incidence("candidate", "stateSchema", "new", 1),
                    Incidence("state", "version", "authority", 2)
                )
                relation("activation", "H29",
                    Incidence("old", "epoch", "old", 0),
                    Incidence("candidate", "definition", "new", 1),
                    Incidence("host", "publisher", "publisher", 2)
                )
            },
            graphDefinition("workflow-onboarding", "997063eb376e5f1e405f0155f0a5b9e36e96980db950c826c9e0bb7cf715c6e8") {
                region("control", "Workflow authority")
                region("participants", "External participants")
                node("workflow", "Onboarding workflow", "N06", "control", ports = listOf(
                    PortDefinition("proposal", "P03", "ReviewRequestV1", PortPolarity.Offer),
                    PortDefinition("decision", "P03", "ReviewDecisionV1", PortPolarity.Require),
                    PortDefinition("deadline", "P15", "DeadlineV1", PortPolarity.Require),
                    PortDefinition("identity", "P02", "IdentityCreateV1", PortPolarity.Require),
                    PortDefinition("state", "P02", "WorkflowCheckpointV1", PortPolarity.Require),
                    PortDefinition("revision", "P24", "ProposalRefV1", PortPolarity.Neutral)
                ))
                node("human", "Human approval", "N20", "participants", ports = listOf(
                    PortDefinition("proposal", "P03", "ReviewRequestV1", PortPolarity.Require),
                    PortDefinition("decision", "P03", "ReviewDecisionV1", PortPolarity.Offer),
                    PortDefinition("principal", "P24", "PrincipalRefV1", PortPolarity.Neutral)
                ))
                node("timer", "Deadline clock", "N09", "control", ports = listOf(
                    PortDefinition("tick", "P15", "DeadlineV1", PortPolarity.Offer)
                ))
                node("identity", "Identity provider", "N30", "participants", ports = listOf(
                    PortDefinition("create", "P02", "IdentityCreateV1", PortPolarity.Offer)
                ))
                node("journal", "Checkpoint authority", "N21", "control", ports = listOf(
                    PortDefinition("checkpoint", "P02", "WorkflowCheckpointV1", PortPolarity.Offer)
                ))
                node("policy", "Activation policy", "N33", "control", ports = listOf(
                    PortDefinition("policy", "P24", "PolicyRefV1", PortPolarity.Neutral)
                ))
                relation("review", "H02",
                    Incidence("workflow", "proposal", "provider", 0),
                    Incidence("human", "proposal", "consumer", 0)
                )
                relation("decision", "H02",
                    Incidence("human", "decision", "provider", 0),
                    Incidence("workflow", "decision", "consumer", 0)
                )
                relation("deadline", "H02",
                    Incidence("timer", "tick", "provider", 0),
                    Incidence("workflow", "deadline", "consumer", 0)
                )
                relation("identity", "H05",
                    Incidence("identity", "create", "provider", 0),
                    Incidence("workflow", "identity", "consumer", 0)
                )
                relation("state", "H05",
                    Incidence("journal", "checkpoint", "provider", 0),
                    Incidence("workflow", "state", "consumer", 0)
                )
                relation("approval", "H23",
                    Incidence("human", "principal", "principal", 0),
                    Incidence("workflow", "revision", "proposal", 1),
                    Incidence("policy", "policy", "policy", 2)
                )
            },
            graphDefinition("workflow-reconciliation", "87300dbef73781c33e93f67d18576c9e6749e83c2908017c9540af530bba1300") {
                region("control", "Workflow authority")
                region("participants", "External participants")
                node("run", "Reconciliation workflow", "N06", "control", ports = listOf(
                    PortDefinition("findings", "P01", "MismatchSetV1", PortPolarity.Require),
                    PortDefinition("proposal", "P03", "ResolutionProposalV1", PortPolarity.Offer),
                    PortDefinition("receipt", "P24", "ReconciliationReceiptV1", PortPolarity.Offer)
                ))
                node("bank", "Payment statement source", "N30", "participants", ports = listOf(
                    PortDefinition("statement", "P01", "StatementV1", PortPolarity.Offer),
                    PortDefinition("cut", "P24", "SourceCutV1", PortPolarity.Neutral)
                ))
                node("ledger", "Internal settlement ledger", "N21", "control", ports = listOf(
                    PortDefinition("snapshot", "P01", "SettlementSnapshotV1", PortPolarity.Offer),
                    PortDefinition("cut", "P24", "SourceCutV1", PortPolarity.Neutral)
                ))
                node("join", "Temporal join", "N07", "control", ports = listOf(
                    PortDefinition("external", "P01", "StatementV1", PortPolarity.Require),
                    PortDefinition("internal", "P01", "SettlementSnapshotV1", PortPolarity.Require),
                    PortDefinition("findings", "P01", "MismatchSetV1", PortPolarity.Offer),
                    PortDefinition("window", "P24", "WindowSpecV1", PortPolarity.Neutral)
                ))
                node("review", "Exception review", "N20", "participants", ports = listOf(
                    PortDefinition("proposal", "P03", "ResolutionProposalV1", PortPolarity.Require)
                ))
                node("receipt", "Reconciliation evidence", "N37", "control", ports = listOf(
                    PortDefinition("input", "P24", "ReconciliationReceiptV1", PortPolarity.Require)
                ))
                relation("statement", "H02",
                    Incidence("bank", "statement", "provider", 0),
                    Incidence("join", "external", "consumer", 0)
                )
                relation("ledger", "H02",
                    Incidence("ledger", "snapshot", "provider", 0),
                    Incidence("join", "internal", "consumer", 0)
                )
                relation("mismatch", "H02",
                    Incidence("join", "findings", "provider", 0),
                    Incidence("run", "findings", "consumer", 0)
                )
                relation("review", "H02",
                    Incidence("run", "proposal", "provider", 0),
                    Incidence("review", "proposal", "consumer", 0)
                )
                relation("evidence", "H27",
                    Incidence("run", "receipt", "provider", 0),
                    Incidence("receipt", "input", "consumer", 0)
                )
                relation("cut", "H11",
                    Incidence("bank", "cut", "external", 0),
                    Incidence("ledger", "cut", "internal", 1),
                    Incidence("join", "window", "join", 2)
                )
            },
            graphDefinition("game-authoritative", "355611235428e4fe1e1e808a16adc40d5b30dceb22333075b468d67392e037ba") {
                region("client", "Game clients")
                region("server", "Authoritative game host")
                node("input", "Player input", "N13", "client", ports = listOf(
                    PortDefinition("command", "P04", "PlayerCommandV1", PortPolarity.Offer),
                    PortDefinition("intent", "P04", "InputIntentV1", PortPolarity.Offer)
                ))
                node("prediction", "Prediction / rendering", "N11", "client", ports = listOf(
                    PortDefinition("intent", "P04", "InputIntentV1", PortPolarity.Require),
                    PortDefinition("authoritative", "P01", "GameSnapshotV1", PortPolarity.Require),
                    PortDefinition("ack", "P24", "AckPositionV1", PortPolarity.Neutral)
                ))
                node("room", "Authoritative room actor", "N05", "server", ports = listOf(
                    PortDefinition("command", "P04", "PlayerCommandV1", PortPolarity.Require),
                    PortDefinition("physics", "P02", "SimulationStepV1", PortPolarity.Require),
                    PortDefinition("tick", "P15", "TickV1", PortPolarity.Require),
                    PortDefinition("snapshot", "P01", "GameSnapshotV1", PortPolarity.Offer),
                    PortDefinition("event", "P04", "MatchEventV1", PortPolarity.Offer),
                    PortDefinition("position", "P24", "TickPositionV1", PortPolarity.Neutral)
                ))
                node("physics", "Simulation kernel", "N16", "server", ports = listOf(
                    PortDefinition("step", "P02", "SimulationStepV1", PortPolarity.Offer)
                ))
                node("clock", "Server tick clock", "N09", "server", ports = listOf(
                    PortDefinition("tick", "P15", "TickV1", PortPolarity.Offer),
                    PortDefinition("clock", "P24", "ClockRefV1", PortPolarity.Neutral)
                ))
                node("log", "Match event log", "N24", "server", ports = listOf(
                    PortDefinition("event", "P04", "MatchEventV1", PortPolarity.Require)
                ))
                relation("command", "H02",
                    Incidence("input", "command", "provider", 0),
                    Incidence("room", "command", "consumer", 0)
                )
                relation("predict", "H02",
                    Incidence("input", "intent", "provider", 0),
                    Incidence("prediction", "intent", "consumer", 0)
                )
                relation("simulate", "H05",
                    Incidence("physics", "step", "provider", 0),
                    Incidence("room", "physics", "consumer", 0)
                )
                relation("tick", "H02",
                    Incidence("clock", "tick", "provider", 0),
                    Incidence("room", "tick", "consumer", 0)
                )
                relation("snapshot", "H02",
                    Incidence("room", "snapshot", "provider", 0),
                    Incidence("prediction", "authoritative", "consumer", 0)
                )
                relation("log", "H02",
                    Incidence("room", "event", "provider", 0),
                    Incidence("log", "event", "consumer", 0)
                )
                relation("alignment", "H31",
                    Incidence("clock", "clock", "clock", 0),
                    Incidence("room", "position", "simulation", 1),
                    Incidence("prediction", "ack", "client", 2)
                )
            },
            graphDefinition("game-world", "ece94168bc9d90e637feb129a3664e527792b19d2c30cef5f09737c4a7111c2b") {
                region("client", "Game clients")
                region("server", "Authoritative game host")
                node("session", "Player session router", "N22", "server", ports = listOf(
                    PortDefinition("a", "P02", "WorldSessionV1", PortPolarity.Require),
                    PortDefinition("b", "P02", "WorldSessionV1", PortPolarity.Require),
                    PortDefinition("session", "P07", "PlayerSessionV1", PortPolarity.Offer)
                ))
                node("a", "World cell A", "N05", "server", ports = listOf(
                    PortDefinition("session", "P02", "WorldSessionV1", PortPolarity.Offer),
                    PortDefinition("authority", "P02", "EntityLeaseV1", PortPolarity.Require),
                    PortDefinition("entity", "P24", "EntityEpochV1", PortPolarity.Neutral),
                    PortDefinition("rules", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("b", "World cell B", "N05", "server", ports = listOf(
                    PortDefinition("session", "P02", "WorldSessionV1", PortPolarity.Offer),
                    PortDefinition("authority", "P02", "EntityLeaseV1", PortPolarity.Require),
                    PortDefinition("entity", "P24", "EntityEpochV1", PortPolarity.Neutral),
                    PortDefinition("rules", "P24", "ActivationRefV1", PortPolarity.Neutral)
                ))
                node("authority", "Entity lease authority", "N21", "server", ports = listOf(
                    PortDefinition("leaseA", "P02", "EntityLeaseV1", PortPolarity.Offer),
                    PortDefinition("leaseB", "P02", "EntityLeaseV1", PortPolarity.Offer),
                    PortDefinition("fence", "P24", "FenceRefV1", PortPolarity.Neutral)
                ))
                node("client", "Player Surface", "N11", "client", ports = listOf(
                    PortDefinition("session", "P07", "PlayerSessionV1", PortPolarity.Require)
                ))
                node("build", "World rules bundle", "N35", "server", ports = listOf(
                    PortDefinition("revision", "P24", "RevisionRefV1", PortPolarity.Neutral)
                ))
                relation("a", "H05",
                    Incidence("a", "session", "provider", 0),
                    Incidence("session", "a", "consumer", 0)
                )
                relation("b", "H05",
                    Incidence("b", "session", "provider", 0),
                    Incidence("session", "b", "consumer", 0)
                )
                relation("leaseA", "H05",
                    Incidence("authority", "leaseA", "provider", 0),
                    Incidence("a", "authority", "consumer", 0)
                )
                relation("leaseB", "H05",
                    Incidence("authority", "leaseB", "provider", 0),
                    Incidence("b", "authority", "consumer", 0)
                )
                relation("session", "H06",
                    Incidence("session", "session", "provider", 0),
                    Incidence("client", "session", "consumer", 0)
                )
                relation("handoff", "H35",
                    Incidence("a", "entity", "old", 0),
                    Incidence("b", "entity", "new", 1),
                    Incidence("authority", "fence", "fence", 2)
                )
                relation("rules", "H29",
                    Incidence("build", "revision", "bundle", 0),
                    Incidence("a", "rules", "cell", 1),
                    Incidence("b", "rules", "cell", 2)
                )
            },
            graphDefinition("robot-allocation", "57d3d7b236867fdf1b2251c728a4569af7de18a8d9ad26114db789c675957c96") {
                region("fleet", "Fleet coordination")
                region("robot", "Robot-local execution")
                node("planner", "Fleet mission planner", "N01", "fleet", ports = listOf(
                    PortDefinition("capacity", "P01", "FleetCapacityV1", PortPolarity.Require),
                    PortDefinition("mission", "P01", "MissionProposalV1", PortPolarity.Offer),
                    PortDefinition("demand", "P24", "DemandRefV1", PortPolarity.Neutral)
                ))
                node("capacity", "Robot capacity view", "N22", "fleet", ports = listOf(
                    PortDefinition("snapshot", "P01", "FleetCapacityV1", PortPolarity.Offer),
                    PortDefinition("supply", "P24", "SupplyRefV1", PortPolarity.Neutral)
                ))
                node("mission", "Mission service", "N19", "fleet", ports = listOf(
                    PortDefinition("proposal", "P01", "MissionProposalV1", PortPolarity.Require),
                    PortDefinition("assign", "P02", "MissionAssignV1", PortPolarity.Offer),
                    PortDefinition("assignment", "P24", "AssignmentRefV1", PortPolarity.Neutral)
                ))
                node("robot", "Robot mission actor", "N05", "robot", ports = listOf(
                    PortDefinition("mission", "P02", "MissionAssignV1", PortPolarity.Require),
                    PortDefinition("motion", "P20", "MotionGrantV1", PortPolarity.Require),
                    PortDefinition("actuate", "P02", "MotionCommandV1", PortPolarity.Require)
                ))
                node("safety", "Local safety controller", "N33", "robot", ports = listOf(
                    PortDefinition("grant", "P20", "MotionGrantV1", PortPolarity.Offer)
                ))
                node("actuator", "Actuator adapter", "N15", "robot", ports = listOf(
                    PortDefinition("move", "P02", "MotionCommandV1", PortPolarity.Offer)
                ))
                relation("capacity", "H02",
                    Incidence("capacity", "snapshot", "provider", 0),
                    Incidence("planner", "capacity", "consumer", 0)
                )
                relation("plan", "H02",
                    Incidence("planner", "mission", "provider", 0),
                    Incidence("mission", "proposal", "consumer", 0)
                )
                relation("delivery", "H05",
                    Incidence("mission", "assign", "provider", 0),
                    Incidence("robot", "mission", "consumer", 0)
                )
                relation("safe", "H23",
                    Incidence("safety", "grant", "provider", 0),
                    Incidence("robot", "motion", "consumer", 0)
                )
                relation("actuator", "H05",
                    Incidence("actuator", "move", "provider", 0),
                    Incidence("robot", "actuate", "consumer", 0)
                )
                relation("allocation", "H09",
                    Incidence("planner", "demand", "demand", 0),
                    Incidence("capacity", "supply", "supply", 1),
                    Incidence("mission", "assignment", "assignment", 2)
                )
            },
            graphDefinition("robot-localization", "dfacbbe033effb48f24871959b0d63e24d4975710abc57260e01495a8c739d80") {
                region("fleet", "Fleet coordination")
                region("robot", "Robot-local execution")
                node("lidar", "Lidar adapter", "N15", "robot", ports = listOf(
                    PortDefinition("sample", "P06", "LidarSampleV1", PortPolarity.Offer),
                    PortDefinition("clock", "P24", "ClockRefV1", PortPolarity.Neutral)
                ))
                node("imu", "IMU adapter", "N15", "robot", ports = listOf(
                    PortDefinition("sample", "P06", "ImuSampleV1", PortPolarity.Offer),
                    PortDefinition("clock", "P24", "ClockRefV1", PortPolarity.Neutral)
                ))
                node("align", "Clock alignment", "N07", "robot", ports = listOf(
                    PortDefinition("lidar", "P06", "LidarSampleV1", PortPolarity.Require),
                    PortDefinition("imu", "P06", "ImuSampleV1", PortPolarity.Require),
                    PortDefinition("kernel", "P02", "FusionStepV1", PortPolarity.Require),
                    PortDefinition("policy", "P24", "TimeAlignmentV1", PortPolarity.Neutral)
                ))
                node("fusion", "Native fusion kernel", "N16", "robot", ports = listOf(
                    PortDefinition("compute", "P02", "FusionStepV1", PortPolarity.Offer),
                    PortDefinition("pose", "P09", "PoseEstimateV1", PortPolarity.Offer)
                ))
                node("pose", "Pose authority", "N21", "robot", ports = listOf(
                    PortDefinition("update", "P09", "PoseEstimateV1", PortPolarity.Require),
                    PortDefinition("snapshot", "P01", "PoseSnapshotV1", PortPolarity.Offer)
                ))
                node("fleet", "Fleet pose subscriber", "N22", "fleet", ports = listOf(
                    PortDefinition("pose", "P01", "PoseSnapshotV1", PortPolarity.Require)
                ))
                relation("lidar", "H02",
                    Incidence("lidar", "sample", "provider", 0),
                    Incidence("align", "lidar", "consumer", 0)
                )
                relation("imu", "H02",
                    Incidence("imu", "sample", "provider", 0),
                    Incidence("align", "imu", "consumer", 0)
                )
                relation("fuse", "H05",
                    Incidence("fusion", "compute", "provider", 0),
                    Incidence("align", "kernel", "consumer", 0)
                )
                relation("pose", "H02",
                    Incidence("fusion", "pose", "provider", 0),
                    Incidence("pose", "update", "consumer", 0)
                )
                relation("publish", "H02",
                    Incidence("pose", "snapshot", "provider", 0),
                    Incidence("fleet", "pose", "consumer", 0)
                )
                relation("time", "H31",
                    Incidence("lidar", "clock", "lidarClock", 0),
                    Incidence("imu", "clock", "imuClock", 1),
                    Incidence("align", "policy", "alignment", 2)
                )
            },
            graphDefinition("iot-sensor", "120a91db6121e8d92f1d0a4ceef8b562284679ecebac1ab725f1648b5f995a35") {
                region("device", "Constrained devices")
                region("gateway", "Gateway")
                region("cloud", "Cloud authority")
                node("sensor", "Sensor firmware boundary", "N30", "device", ports = listOf(
                    PortDefinition("sample", "P04", "SensorSampleV1", PortPolarity.Offer),
                    PortDefinition("identity", "P24", "DeviceRefV1", PortPolarity.Neutral)
                ))
                node("gateway", "Gateway actor", "N05", "gateway", ports = listOf(
                    PortDefinition("sample", "P04", "SensorSampleV1", PortPolarity.Require),
                    PortDefinition("buffer", "P02", "GatewayAppendV1", PortPolarity.Require),
                    PortDefinition("upload", "P02", "SensorBatchV1", PortPolarity.Require),
                    PortDefinition("health", "P24", "DeviceHealthV1", PortPolarity.Offer)
                ))
                node("buffer", "Gateway durable buffer", "N24", "gateway", ports = listOf(
                    PortDefinition("append", "P02", "GatewayAppendV1", PortPolarity.Offer),
                    PortDefinition("position", "P24", "LogPositionV1", PortPolarity.Neutral)
                ))
                node("ingest", "Cloud ingestion", "N19", "cloud", ports = listOf(
                    PortDefinition("batch", "P02", "SensorBatchV1", PortPolarity.Offer),
                    PortDefinition("storage", "P02", "SeriesAppendV1", PortPolarity.Require)
                ))
                node("series", "Time-series authority", "N21", "cloud", ports = listOf(
                    PortDefinition("append", "P02", "SeriesAppendV1", PortPolarity.Offer),
                    PortDefinition("receipt", "P24", "ReceiptRefV1", PortPolarity.Neutral)
                ))
                node("health", "Fleet health view", "N22", "cloud", ports = listOf(
                    PortDefinition("events", "P24", "DeviceHealthV1", PortPolarity.Require)
                ))
                relation("sample", "H02",
                    Incidence("sensor", "sample", "provider", 0),
                    Incidence("gateway", "sample", "consumer", 0)
                )
                relation("buffer", "H05",
                    Incidence("buffer", "append", "provider", 0),
                    Incidence("gateway", "buffer", "consumer", 0)
                )
                relation("upload", "H05",
                    Incidence("ingest", "batch", "provider", 0),
                    Incidence("gateway", "upload", "consumer", 0)
                )
                relation("store", "H05",
                    Incidence("series", "append", "provider", 0),
                    Incidence("ingest", "storage", "consumer", 0)
                )
                relation("health", "H04",
                    Incidence("gateway", "health", "provider", 0),
                    Incidence("health", "events", "consumer", 0)
                )
                relation("lineage", "H27",
                    Incidence("sensor", "identity", "device", 0),
                    Incidence("buffer", "position", "gateway", 1),
                    Incidence("series", "receipt", "cloud", 2)
                )
            },
            graphDefinition("iot-command", "1a11b20d761148f7f75f75d0d955905cec811445932dd24149e5dc54500c9757") {
                region("device", "Constrained devices")
                region("gateway", "Gateway")
                region("cloud", "Cloud authority")
                node("operator", "Operator proposal", "N20", "cloud", ports = listOf(
                    PortDefinition("intent", "P03", "DeviceIntentV1", PortPolarity.Offer),
                    PortDefinition("principal", "P24", "PrincipalRefV1", PortPolarity.Neutral)
                ))
                node("policy", "Device policy", "N33", "cloud", ports = listOf(
                    PortDefinition("grant", "P20", "DeviceGrantV1", PortPolarity.Offer),
                    PortDefinition("policy", "P24", "PolicyRefV1", PortPolarity.Neutral)
                ))
                node("command", "Command service", "N19", "cloud", ports = listOf(
                    PortDefinition("intent", "P03", "DeviceIntentV1", PortPolarity.Require),
                    PortDefinition("authority", "P20", "DeviceGrantV1", PortPolarity.Require),
                    PortDefinition("deliver", "P02", "DeviceCommandV1", PortPolarity.Require)
                ))
                node("gateway", "Gateway executor", "N27", "gateway", ports = listOf(
                    PortDefinition("execute", "P02", "DeviceCommandV1", PortPolarity.Offer),
                    PortDefinition("device", "P02", "DeviceProtocolV1", PortPolarity.Require),
                    PortDefinition("artifact", "P23", "FirmwareRefV1", PortPolarity.Require),
                    PortDefinition("executor", "P24", "HostRefV1", PortPolarity.Neutral)
                ))
                node("device", "Device controller boundary", "N30", "device", ports = listOf(
                    PortDefinition("command", "P02", "DeviceProtocolV1", PortPolarity.Offer),
                    PortDefinition("identity", "P24", "DeviceRefV1", PortPolarity.Neutral)
                ))
                node("bundle", "Signed firmware artifact", "N35", "cloud", ports = listOf(
                    PortDefinition("artifact", "P23", "FirmwareRefV1", PortPolarity.Offer)
                ))
                relation("proposal", "H02",
                    Incidence("operator", "intent", "provider", 0),
                    Incidence("command", "intent", "consumer", 0)
                )
                relation("grant", "H23",
                    Incidence("policy", "grant", "provider", 0),
                    Incidence("command", "authority", "consumer", 0)
                )
                relation("deliver", "H05",
                    Incidence("gateway", "execute", "provider", 0),
                    Incidence("command", "deliver", "consumer", 0)
                )
                relation("device", "H05",
                    Incidence("device", "command", "provider", 0),
                    Incidence("gateway", "device", "consumer", 0)
                )
                relation("artifact", "H26",
                    Incidence("bundle", "artifact", "provider", 0),
                    Incidence("gateway", "artifact", "consumer", 0)
                )
                relation("admission", "H23",
                    Incidence("operator", "principal", "principal", 0),
                    Incidence("device", "identity", "resource", 1),
                    Incidence("policy", "policy", "policy", 2),
                    Incidence("gateway", "executor", "executor", 3)
                )
            }
        )
        assertEquals(30, definitions.size)
        definitions.forEach { definition ->
            assertTrue(definition.problems().isEmpty(), definition.id)
            assertEquals(definition, Json.decodeFromString<GraphDefinition>(Json.encodeToString(definition)))
        }
    }
}
