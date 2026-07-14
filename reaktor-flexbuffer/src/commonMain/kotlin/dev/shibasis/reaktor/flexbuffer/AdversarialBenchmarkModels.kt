package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.core.Struct
import kotlinx.serialization.Serializable

// ─── Adversarial + second-wave realistic benchmark models (2026-07-13) ───
// Each case targets a specific hot path flagged by the 2026-07-11 performance
// audit and the follow-up source verification:
//   AdvDeepNest    — per-object keyBlockBase scan, context-stack depth
//   AdvWideFlat    — wide-map geometry, key-block size, positional decode width
//   AdvPrefixKeys  — raw-tier binary search over long-common-prefix keys
//   AdvUnicode     — non-ASCII UTF-8 encode/decode (Android portable loop, iOS NSString, JS TextDecoder)
//   AdvStringFlood — string allocation floor, per-string decode overhead
//   AdvWidthBomb   — 8-wide container forcing, sign-extended minimal widths
//   RealFeedPage   — social feed pagination envelope (large mixed document)
//   RealGraphTopology — node/edge projection with property maps
//   RealMetricsScrape — many mid-size primitive vectors + label maps
//   RealSessionEnvelope — event-sourcing audit batch
// AdvSparseNulls is deliberately NOT @Struct: nullable fields stay on the raw
// serializer tier until KSP nullable semantics are complete.

// ── 1. Deep nesting: 12-level chain, 2 fields per level ──

@Struct @Serializable data class AdvNestL12(val depth: Int, val tag: String)
@Struct @Serializable data class AdvNestL11(val depth: Int, val child: AdvNestL12)
@Struct @Serializable data class AdvNestL10(val depth: Int, val child: AdvNestL11)
@Struct @Serializable data class AdvNestL9(val depth: Int, val child: AdvNestL10)
@Struct @Serializable data class AdvNestL8(val depth: Int, val child: AdvNestL9)
@Struct @Serializable data class AdvNestL7(val depth: Int, val child: AdvNestL8)
@Struct @Serializable data class AdvNestL6(val depth: Int, val child: AdvNestL7)
@Struct @Serializable data class AdvNestL5(val depth: Int, val child: AdvNestL6)
@Struct @Serializable data class AdvNestL4(val depth: Int, val child: AdvNestL5)
@Struct @Serializable data class AdvNestL3(val depth: Int, val child: AdvNestL4)
@Struct @Serializable data class AdvNestL2(val depth: Int, val child: AdvNestL3)
@Struct @Serializable data class AdvNestL1(val depth: Int, val child: AdvNestL2, val siblings: List<AdvNestL12>)

// ── 2. Wide flat map: 48 ints + 16 longs + 16 strings = 80 fields ──

@Struct @Serializable
data class AdvWideFlat(
    val f00: Int, val f01: Int, val f02: Int, val f03: Int, val f04: Int, val f05: Int,
    val f06: Int, val f07: Int, val f08: Int, val f09: Int, val f10: Int, val f11: Int,
    val f12: Int, val f13: Int, val f14: Int, val f15: Int, val f16: Int, val f17: Int,
    val f18: Int, val f19: Int, val f20: Int, val f21: Int, val f22: Int, val f23: Int,
    val f24: Int, val f25: Int, val f26: Int, val f27: Int, val f28: Int, val f29: Int,
    val f30: Int, val f31: Int, val f32: Int, val f33: Int, val f34: Int, val f35: Int,
    val f36: Int, val f37: Int, val f38: Int, val f39: Int, val f40: Int, val f41: Int,
    val f42: Int, val f43: Int, val f44: Int, val f45: Int, val f46: Int, val f47: Int,
    val g00: Long, val g01: Long, val g02: Long, val g03: Long,
    val g04: Long, val g05: Long, val g06: Long, val g07: Long,
    val g08: Long, val g09: Long, val g10: Long, val g11: Long,
    val g12: Long, val g13: Long, val g14: Long, val g15: Long,
    val s00: String, val s01: String, val s02: String, val s03: String,
    val s04: String, val s05: String, val s06: String, val s07: String,
    val s08: String, val s09: String, val s10: String, val s11: String,
    val s12: String, val s13: String, val s14: String, val s15: String
)

// ── 3. Long-common-prefix keys: worst case for byte-wise key binary search ──

@Struct @Serializable
data class AdvPrefixKeys(
    val metricsAggregationWindowPrimarySeconds: Int,
    val metricsAggregationWindowPrimaryEnabled: Boolean,
    val metricsAggregationWindowPrimaryWeight: Double,
    val metricsAggregationWindowSecondarySeconds: Int,
    val metricsAggregationWindowSecondaryEnabled: Boolean,
    val metricsAggregationWindowSecondaryWeight: Double,
    val metricsAggregationWindowTertiarySeconds: Int,
    val metricsAggregationWindowTertiaryEnabled: Boolean,
    val metricsAggregationWindowTertiaryWeight: Double,
    val metricsAggregationBucketPrimaryCount: Int,
    val metricsAggregationBucketPrimaryLabel: String,
    val metricsAggregationBucketSecondaryCount: Int,
    val metricsAggregationBucketSecondaryLabel: String,
    val metricsAggregationBucketTertiaryCount: Int,
    val metricsAggregationBucketTertiaryLabel: String,
    val metricsAggregationRetentionPolicyDays: Int,
    val metricsAggregationRetentionPolicyName: String,
    val metricsAggregationRetentionPolicyStrict: Boolean,
    val metricsAggregationSamplingRateNumerator: Int,
    val metricsAggregationSamplingRateDenominator: Int,
    val metricsAggregationSamplingRateAdaptive: Boolean,
    val metricsAggregationDownsampleStrategyName: String,
    val metricsAggregationDownsampleStrategyFactor: Double,
    val metricsAggregationDownsampleStrategyMinPoints: Int
)

// ── 4. Non-ASCII heavy: CJK / emoji / Devanagari / Cyrillic / mixed ──

@Struct @Serializable
data class AdvUnicode(
    val titleZh: String,
    val titleJa: String,
    val titleKo: String,
    val titleHi: String,
    val titleRu: String,
    val titleAr: String,
    val bodyMixed: String,
    val emojiReaction: String,
    val userNameCjk: String,
    val cityName: String,
    val quote: String,
    val hashtags: List<String>,
    val translations: Map<String, String>
)

// ── 5. String flood: hundreds of short unique strings ──

@Struct @Serializable
data class AdvStringFlood(
    val ids: List<String>,
    val labels: List<String>,
    val index: Map<String, String>
)

// ── 6. Width bomb: one extreme value forces maximum container width;
//       negatives exercise sign-extended minimal widths (C++ WidthI parity) ──

@Struct @Serializable
data class AdvWidthBomb(
    val smallWithGiant: List<Long>,
    val negatives: List<Long>,
    val boundaryInts: List<Int>,
    val tinyThenHuge: List<Long>,
    val mixedFloats: List<Double>,
    val label: String
)

// ── 7. Social feed page (bestbuds-shaped pagination envelope) ──

@Struct @Serializable
data class RealAuthorLite(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val verified: Boolean
)

@Struct @Serializable
data class RealReaction(val type: String, val count: Int)

@Struct @Serializable
data class RealCommentPreview(val author: String, val text: String, val likeCount: Int)

@Struct @Serializable
data class RealFeedPost(
    val id: Long,
    val author: RealAuthorLite,
    val text: String,
    val mediaUrls: List<String>,
    val reactions: List<RealReaction>,
    val topComments: List<RealCommentPreview>,
    val commentCount: Int,
    val shareCount: Int,
    val liked: Boolean,
    val createdAtEpochMs: Long
)

@Struct @Serializable
data class RealFeedPage(
    val posts: List<RealFeedPost>,
    val nextCursor: String,
    val syncToken: String,
    val hasMore: Boolean,
    val serverTimeMs: Long
)

// ── 8. Graph topology projection (reaktor-flow / Memgraph scope view) ──

@Struct @Serializable
data class RealGraphNode(
    val id: Long,
    val label: String,
    val props: Map<String, String>
)

@Struct @Serializable
data class RealGraphEdge(
    val src: Long,
    val dst: Long,
    val type: String,
    val weight: Double
)

@Struct @Serializable
data class RealGraphTopology(
    val scopeId: String,
    val nodes: List<RealGraphNode>,
    val edges: List<RealGraphEdge>,
    val generation: Long
)

// ── 9. Metrics scrape: many mid-size primitive vectors + label maps ──

@Struct @Serializable
data class RealMetricSeries(
    val name: String,
    val labels: Map<String, String>,
    val values: List<Double>,
    val timestamps: List<Long>
)

@Struct @Serializable
data class RealMetricsScrape(
    val instance: String,
    val scrapeTimeMs: Long,
    val series: List<RealMetricSeries>
)

// ── 10. Event-sourcing session envelope (Nexergy audit/compliance shape) ──

@Struct @Serializable
data class RealSessionEvent(
    val seq: Long,
    val timestampMs: Long,
    val actor: String,
    val action: String,
    val targetId: String,
    val attrs: Map<String, String>
)

@Struct @Serializable
data class RealSessionEnvelope(
    val sessionId: String,
    val tenant: String,
    val events: List<RealSessionEvent>,
    val checkpointSeq: Long
)

// ── Raw-tier only: sparse nullable document (NOT @Struct on purpose) ──

@Serializable
data class AdvSparseNulls(
    val a0: String? = null, val a1: String? = null, val a2: String? = null, val a3: String? = null,
    val a4: String? = null, val a5: String? = null, val a6: String? = null, val a7: String? = null,
    val b0: Int? = null, val b1: Int? = null, val b2: Int? = null, val b3: Int? = null,
    val b4: Int? = null, val b5: Int? = null, val b6: Int? = null, val b7: Int? = null,
    val c0: Long? = null, val c1: Long? = null, val c2: Long? = null, val c3: Long? = null,
    val c4: Double? = null, val c5: Double? = null, val c6: Double? = null, val c7: Double? = null,
    val d0: Boolean? = null, val d1: Boolean? = null, val d2: Boolean? = null, val d3: Boolean? = null,
    val populatedName: String? = null,
    val populatedCount: Int? = null,
    val populatedTs: Long? = null,
    val populatedFlag: Boolean? = null
)

// ─── Deterministic data factories (no Random: stable across platforms/runs) ───

object AdversarialData {

    fun deepNest(): AdvNestL1 {
        val leaf = { i: Int -> AdvNestL12(12, "leaf_$i") }
        return AdvNestL1(
            1,
            AdvNestL2(2, AdvNestL3(3, AdvNestL4(4, AdvNestL5(5, AdvNestL6(6,
                AdvNestL7(7, AdvNestL8(8, AdvNestL9(9, AdvNestL10(10, AdvNestL11(11, leaf(0))))))))))),
            List(8) { leaf(it + 1) }
        )
    }

    fun wideFlat() = AdvWideFlat(
        0, 1, 4, 9, 16, 25, 36, 49, 64, 81, 100, 121, 144, 169, 196, 225,
        256, 289, 324, 361, 400, 441, 484, 529, 576, 625, 676, 729, 784, 841, 900, 961,
        1024, 1089, 1156, 1225, 1296, 1369, 1444, 1521, 1600, 1681, 1764, 1849, 1936, 2025, 2116, 2209,
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
        -1L, -10L, -100L, -1_000L, 4_503_599_627_370_496L, 9_007_199_254_740_993L, -4_503_599_627_370_496L, 0L,
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
        "india", "juliett", "kilo", "lima", "mike", "november", "oscar", "papa"
    )

    fun prefixKeys() = AdvPrefixKeys(
        300, true, 0.75,
        900, false, 0.20,
        3600, true, 0.05,
        64, "p50", 128, "p95", 256, "p99",
        90, "standard-90d", true,
        1, 100, true,
        "lttb", 4.0, 16
    )

    fun unicode() = AdvUnicode(
        titleZh = "高性能序列化格式对比测试报告",
        titleJa = "高速シリアライズ形式の比較ベンチマーク",
        titleKo = "고성능 직렬화 포맷 벤치마크 보고서",
        titleHi = "उच्च-प्रदर्शन क्रमांकन प्रारूप तुलना",
        titleRu = "Отчёт о сравнении форматов сериализации",
        titleAr = "تقرير مقارنة تنسيقات التسلسل عالية الأداء",
        bodyMixed = "FlexBuffers vs JSON: 性能 🚀 быстро 빠르게 तेज़ سريع — mixed-script body with ASCII islands and 数字123 embedded.",
        emojiReaction = "🎉🔥💯🚀🧪✨🏆🥇",
        userNameCjk = "王小明_テスト_사용자",
        cityName = "São Paulo — Zürich — 東京 — Bengaluru",
        quote = "«Скорость — это фича» × 「速さは機能だ」 × “速度就是功能”",
        hashtags = listOf("#性能测试", "#ベンチマーク", "#성능", "#производительность", "#كفاءة", "#परीक्षण"),
        translations = mapOf(
            "en" to "Performance is a feature",
            "zh" to "性能就是功能",
            "ja" to "速さは機能だ",
            "ko" to "속도는 기능이다",
            "ru" to "Скорость — это фича",
            "hi" to "गति ही विशेषता है",
            "ar" to "السرعة ميزة"
        )
    )

    fun stringFlood(): AdvStringFlood {
        val ids = List(256) { "id_${it}_${(it * 2654435761L).toString(16)}" }
        val labels = List(128) { "label-${'a' + (it % 26)}$it" }
        val index = buildMap {
            repeat(64) { put("k${it}_${(it * 31)}", "v${it}_${(it * it)}") }
        }
        return AdvStringFlood(ids, labels, index)
    }

    fun widthBomb() = AdvWidthBomb(
        smallWithGiant = List(63) { it.toLong() } + listOf(Long.MAX_VALUE),
        negatives = listOf(-1L, -128L, -129L, -32768L, -32769L, -2147483648L, -2147483649L, Long.MIN_VALUE),
        boundaryInts = listOf(127, 128, 255, 256, 32767, 32768, 65535, 65536, Int.MAX_VALUE, -127, -128, -32767, -32768, Int.MIN_VALUE),
        tinyThenHuge = List(31) { 1L } + listOf(4611686018427387904L),
        mixedFloats = listOf(0.5, 1.0, -0.5, 3.4028235E38, 1.7976931348623157E308, -1.1754944E-38),
        label = "width-bomb"
    )

    fun feedPage(): RealFeedPage {
        val reactionTypes = listOf("like", "love", "laugh", "wow", "sad")
        val posts = List(40) { i ->
            RealFeedPost(
                id = 8_000_000_000L + i,
                author = RealAuthorLite(
                    id = 5_000L + (i % 12),
                    username = "user_${i % 12}_handle",
                    displayName = "Display Name ${i % 12}",
                    avatarUrl = "https://cdn.bestbuds.ai/avatars/u${i % 12}/profile_256.webp",
                    verified = i % 5 == 0
                ),
                text = "Post $i — spent the weekend hacking on the sliding-window renderer, " +
                    "60fps export finally clean. Long-form body text to be realistic about " +
                    "average feed payload entropy and length distribution #buildinpublic",
                mediaUrls = List(i % 4) { m -> "https://cdn.bestbuds.ai/media/p$i/img_$m.webp" },
                reactions = reactionTypes.take(1 + i % 5).mapIndexed { r, t -> RealReaction(t, (i * 7 + r * 13) % 900) },
                topComments = List(2) { c ->
                    RealCommentPreview("commenter_${(i + c) % 20}", "Comment $c on post $i — nice work, ship it!", (i + c * 3) % 50)
                },
                commentCount = (i * 3) % 120,
                shareCount = (i * 2) % 40,
                liked = i % 3 == 0,
                createdAtEpochMs = 1_784_000_000_000L + i * 60_000L
            )
        }
        return RealFeedPage(posts, "cursor_opaque_v2_00812345", "sync_9f8e7d6c5b4a", true, 1_784_003_600_000L)
    }

    fun graphTopology(): RealGraphTopology {
        val labels = listOf("Service", "Module", "Actor", "Store", "Topic", "Job")
        val nodes = List(120) { i ->
            RealGraphNode(
                id = i.toLong(),
                label = labels[i % labels.size],
                props = mapOf(
                    "name" to "node_${labels[i % labels.size].lowercase()}_$i",
                    "scope" to "app.module${i % 8}",
                    "state" to if (i % 4 == 0) "active" else "idle"
                )
            )
        }
        val edges = List(300) { e ->
            RealGraphEdge(
                src = (e * 7L) % 120,
                dst = (e * 13L + 1) % 120,
                type = if (e % 3 == 0) "DEPENDS_ON" else "EMITS",
                weight = (e % 10) / 10.0
            )
        }
        return RealGraphTopology("scope://app/root", nodes, edges, 42L)
    }

    fun metricsScrape(): RealMetricsScrape {
        val series = List(24) { s ->
            RealMetricSeries(
                name = "reaktor_${listOf("http_requests", "db_queries", "cache_hits", "frame_time")[s % 4]}_series$s",
                labels = mapOf(
                    "instance" to "edge-worker-${s % 6}",
                    "region" to listOf("bom", "sin", "fra", "iad")[s % 4],
                    "job" to "reaktor-server",
                    "quantile" to listOf("0.5", "0.9", "0.99")[s % 3]
                ),
                values = List(240) { p -> (s + 1) * 0.25 + p * 0.001 + (p % 7) * 0.03 },
                timestamps = List(240) { p -> 1_784_000_000_000L + p * 15_000L }
            )
        }
        return RealMetricsScrape("edge-worker-3.bom", 1_784_003_600_000L, series)
    }

    fun sessionEnvelope(): RealSessionEnvelope {
        val actions = listOf("lot.created", "bid.placed", "bid.accepted", "shipment.booked", "invoice.issued", "payment.settled")
        val events = List(150) { i ->
            RealSessionEvent(
                seq = i.toLong(),
                timestampMs = 1_784_000_000_000L + i * 1_250L,
                actor = "trader_${i % 9}@nexergy",
                action = actions[i % actions.size],
                targetId = "lot_${1000 + i / 6}",
                attrs = mapOf(
                    "qty_tons" to "${(i % 40) + 5}",
                    "grade" to listOf("A", "B", "C")[i % 3],
                    "price_inr" to "${4200 + (i % 17) * 25}"
                )
            )
        }
        return RealSessionEnvelope("sess_20260713_0091", "nexergy-prod", events, 149L)
    }

    fun sparseNulls() = AdvSparseNulls(
        populatedName = "only-four-of-thirty-two",
        populatedCount = 42,
        populatedTs = 1_784_003_600_000L,
        populatedFlag = true
    )
}
