package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanePlanTest {
    private val widths = listOf(400f, 600f, 700f, 720f, 840f, 1100f, 1120f, 1512f, 1920f, 2560f)
    private val heights = listOf(480f, 800f, 1000f, 1440f)
    private val textScales = listOf(1f, 1.6f)

    private val handle = 8f
    private val mapToolbar = 32f
    private val controlHeight = 28f
    private val pixel = 0.5f
    private val pixelAspect = 1080f / 2400f

    private val drawer = PaneSpec(
        listOf(Region("drawer", RegionEdge.Bottom, preferred = 260f, min = 4 * controlHeight, collapse = 0)),
        mainMinWidth = 0f,
        mainMinHeight = 0f,
    )

    private val graph = PaneSpec(
        listOf(
            Region("outline", RegionEdge.Start, preferred = 272f, min = 200f, max = 460f, collapse = 0),
            Region("inspector", RegionEdge.End, preferred = 360f, min = 280f, max = 720f, collapse = 1),
            Region("trace", RegionEdge.Bottom, preferred = 170f, min = 90f, max = 480f, collapse = 0),
        ),
        mainMinWidth = 2 * handle,
        mainMinHeight = mapToolbar + handle,
    )

    private fun onePhoneFitted(height: Float) = (height - 100f) * pixelAspect + 22f + 16f

    private fun devTools(height: Float) = PaneSpec(
        listOf(
            Region("screens", RegionEdge.Start, preferred = maxOf(onePhoneFitted(height), 300f), min = 260f, max = 1400f, collapse = 0),
            Region("details", RegionEdge.End, preferred = 360f, min = 260f, max = 900f, collapse = 1),
        ),
        mainMinWidth = 780f,
        mainMinHeight = 0f,
    )

    private class Case(val name: String, val spec: (Float) -> PaneSpec, val preferences: PanePreferences)

    private val cases = listOf(
        Case("drawer", { drawer }, PanePreferences()),
        Case("dragged drawer", { drawer }, PanePreferences(sizes = mapOf("drawer" to 600f))),
        Case("expanded drawer", { drawer }, PanePreferences(sizes = mapOf("drawer" to Float.POSITIVE_INFINITY))),
        Case("graph", { graph }, PanePreferences()),
        Case("graph at its largest", { graph }, PanePreferences(sizes = mapOf("outline" to 460f, "inspector" to 720f, "trace" to 480f))),
        Case("graph below its minimums", { graph }, PanePreferences(sizes = mapOf("outline" to 10f, "inspector" to 10f, "trace" to 10f))),
        Case("graph without an inspector", { graph }, PanePreferences(hidden = setOf("inspector"))),
        Case("devtools", ::devTools, PanePreferences()),
        Case("devtools with wide screens", ::devTools, PanePreferences(sizes = mapOf("screens" to 1400f))),
        Case("devtools without details", ::devTools, PanePreferences(hidden = setOf("details"))),
    )

    private fun PaneSpec.across() = regions.filter { it.edge != RegionEdge.Bottom }.map { it.id }.toSet()

    private fun PaneSpec.down() = regions.filter { it.edge == RegionEdge.Bottom }.map { it.id }.toSet()

    @Test
    fun fitSizeIsTodaysClampAndTheMinimumWinsOverASmallerMaximum() {
        assertEquals(200f, fitSize(120f, 200f, 460f))
        assertEquals(460f, fitSize(900f, 200f, 460f))
        assertEquals(300f, fitSize(300f, 200f, 460f))
        assertEquals(300f, fitSize(120f, 300f, 250f))
        assertEquals(300f, fitSize(900f, 300f, 250f))
    }

    @Test
    fun everyCellOfTheContainerMatrixKeepsEveryRegionInRangeAndMainAboveItsMinimum() = cases.forEach { case ->
        heights.forEach { height ->
            val spec = case.spec(height)
            widths.forEach { width ->
                textScales.forEach { textScale ->
                    val plan = spec.plan(width, height, textScale, case.preferences)
                    val cell = "${case.name} at $width x $height, text $textScale"
                    val scale = maxOf(textScale, 1f)
                    spec.regions.forEach { region ->
                        val states = listOf(region.id in plan.sizes, region.id in plan.collapsed, region.id in case.preferences.hidden)
                        assertEquals(1, states.count { it }, "$cell: ${region.id} is shown, collapsed or hidden, and only one of them")
                        plan.sizes[region.id]?.let { size ->
                            assertTrue(size >= region.min * scale - 0.01f, "$cell: ${region.id} is $size, below its minimum")
                            assertTrue(size <= maxOf(region.max, region.min * scale) + 0.01f, "$cell: ${region.id} is $size, above its maximum")
                        }
                    }
                    if (spec.across().any { it in plan.sizes }) {
                        assertTrue(plan.mainWidth >= spec.mainMinWidth * scale - 0.01f, "$cell: main is ${plan.mainWidth} wide beside a shown region")
                    }
                    if (spec.down().any { it in plan.sizes }) {
                        assertTrue(plan.mainHeight >= spec.mainMinHeight * scale - 0.01f, "$cell: main is ${plan.mainHeight} tall above a shown region")
                    }
                }
            }
        }
    }

    @Test
    fun wideningNeverCollapsesARegionThatWasShownAndNeitherDoesGrowingTaller() = cases.forEach { case ->
        textScales.forEach { textScale ->
            heights.forEach { height ->
                val spec = case.spec(height)
                widths.zipWithNext().forEach { (narrow, wide) ->
                    val before = spec.plan(narrow, height, textScale, case.preferences).collapsed intersect spec.across()
                    val after = spec.plan(wide, height, textScale, case.preferences).collapsed intersect spec.across()
                    assertTrue(before.containsAll(after), "${case.name}: widening from $narrow to $wide at $height collapsed ${after - before}")
                }
            }
            widths.forEach { width ->
                heights.zipWithNext().forEach { (short, tall) ->
                    val before = case.spec(short).plan(width, short, textScale, case.preferences).collapsed intersect case.spec(short).down()
                    val after = case.spec(tall).plan(width, tall, textScale, case.preferences).collapsed intersect case.spec(tall).down()
                    assertTrue(before.containsAll(after), "${case.name}: growing from $short to $tall at $width collapsed ${after - before}")
                }
            }
        }
    }

    @Test
    fun theSameInputsGiveTheSamePlan() = cases.forEach { case ->
        heights.forEach { height ->
            widths.forEach { width ->
                textScales.forEach { textScale ->
                    assertEquals(
                        case.spec(height).plan(width, height, textScale, case.preferences),
                        case.spec(height).copy().plan(width, height, textScale, case.preferences.copy()),
                        "${case.name} at $width x $height, text $textScale",
                    )
                }
            }
        }
    }

    @Test
    fun aUserSizeSurvivesANarrowPlanAndComesBackWhenSpaceReturns() {
        val chosen = PanePreferences(sizes = mapOf("inspector" to 520f))
        assertEquals(mapOf("outline" to 272f, "inspector" to 520f, "trace" to 170f), graph.plan(1920f, 1000f, 1f, chosen).sizes)
        assertEquals(mapOf("outline" to 200f, "inspector" to 484f, "trace" to 170f), graph.plan(700f, 1000f, 1f, chosen).sizes)
        val narrowest = graph.plan(400f, 1000f, 1f, chosen)
        assertEquals(setOf("outline"), narrowest.collapsed)
        assertEquals(384f, narrowest.sizes["inspector"])
        assertEquals(mapOf("outline" to 272f, "inspector" to 520f, "trace" to 170f), graph.plan(1920f, 1000f, 1f, chosen).sizes)
        assertEquals(PanePreferences(sizes = mapOf("inspector" to 520f)), chosen)
    }

    @Test
    fun aUserSizeBeyondTheMaximumIsClampedForThePlanButKeptAsTheUsersSize() {
        val chosen = PanePreferences(sizes = mapOf("inspector" to 900f))
        assertEquals(720f, graph.plan(2560f, 1000f, 1f, chosen).sizes["inspector"])
        assertEquals(900f, chosen.sizes["inspector"])
    }

    @Test
    fun aHiddenRegionIsNeverShownAndIsNotCountedAsCollapsed() {
        val hidden = PanePreferences(hidden = setOf("outline"))
        widths.forEach { width ->
            val plan = graph.plan(width, 1000f, 1f, hidden)
            assertTrue("outline" !in plan.sizes && "outline" !in plan.collapsed, "the outline at $width")
        }
    }

    @Test
    fun regionsShrinkLowestCollapseFirstThenCollapseInThatOrderAndTheRestGetTheirSizeBack() {
        assertEquals(mapOf("outline" to 272f, "inspector" to 360f), graph.plan(700f, 1000f, 1f, PanePreferences()).sizes - "trace")
        assertEquals(mapOf("outline" to 224f, "inspector" to 360f), graph.plan(600f, 1000f, 1f, PanePreferences()).sizes - "trace")
        assertEquals(mapOf("outline" to 200f, "inspector" to 304f), graph.plan(520f, 1000f, 1f, PanePreferences()).sizes - "trace")
        val collapsed = graph.plan(480f, 1000f, 1f, PanePreferences())
        assertEquals(setOf("outline"), collapsed.collapsed)
        assertEquals(mapOf("inspector" to 360f), collapsed.sizes - "trace")
        assertEquals(setOf("outline", "inspector"), graph.plan(200f, 1000f, 1f, PanePreferences()).collapsed)
        assertEquals(200f, graph.plan(200f, 1000f, 1f, PanePreferences()).mainWidth)
    }

    @Test
    fun bottomRegionsFitAgainstTheMainMinimumHeight() {
        assertEquals(170f, graph.plan(1100f, 400f, 1f, PanePreferences()).sizes["trace"])
        assertEquals(160f, graph.plan(1100f, 200f, 1f, PanePreferences()).sizes["trace"])
        val short = graph.plan(1100f, 120f, 1f, PanePreferences())
        assertEquals(setOf("trace"), short.collapsed)
        assertEquals(120f, short.mainHeight)
    }

    @Test
    fun minimumsGrowWithTextScale() {
        val large = graph.plan(1100f, 1000f, 1.6f, PanePreferences())
        assertEquals(320f, large.sizes["outline"])
        assertEquals(448f, large.sizes["inspector"])
        assertEquals(170f, large.sizes["trace"])
        assertEquals(graph.plan(1100f, 1000f, 1f, PanePreferences()), graph.plan(1100f, 1000f, 0.8f, PanePreferences()))
        assertEquals(setOf("screens", "details"), devTools(1000f).plan(1100f, 1000f, 1.6f, PanePreferences()).collapsed)
    }

    private class Reading(val window: String, val width: Float, val height: Float, val sizes: Map<String, Float?>, val mainWidth: Float, val mainHeight: Float) {
        val hidden: Set<String> get() = sizes.filterValues { it == null }.keys
    }

    private fun PaneSpec.reproduces(reading: Reading, preferences: PanePreferences = PanePreferences(hidden = reading.hidden)) {
        val plan = plan(reading.width, reading.height, 1f, preferences)
        val shown = reading.sizes.entries.mapNotNull { (id, size) -> size?.let { id to it } }.toMap()
        assertEquals(shown.keys, plan.sizes.keys, "${reading.window}: regions shown")
        shown.forEach { (id, size) -> assertEquals(size, plan.sizes.getValue(id), pixel, "${reading.window}: $id") }
        assertEquals(reading.mainWidth, plan.mainWidth, pixel, "${reading.window}: main width")
        assertEquals(reading.mainHeight, plan.mainHeight, pixel, "${reading.window}: main height")
    }

    private fun graphReading(window: String, rowWidth: Float, rowHeight: Float, outline: Float?, inspector: Float?, trace: Float?, mapWidth: Float, canvasHeight: Float) = Reading(
        window, rowWidth, rowHeight,
        mapOf("outline" to outline, "inspector" to inspector, "trace" to trace),
        mainWidth = mapWidth + handle * listOfNotNull(outline, inspector).size,
        mainHeight = canvasHeight + mapToolbar + if (trace != null) handle else 0f,
    )

    private val graphSceneDriverReadings = listOf(
        graphReading("1100x720 outline shown by the user", 1060f, 612f, 272f, null, 170f, 780f, 402f),
        graphReading("1100x720 outline shown by the user, node selected", 1060f, 612f, 272f, 360f, 170f, 412f, 402f),
        graphReading("1512x982 outline shown by the user", 1472f, 874f, 272f, null, 170f, 1192f, 664f),
        graphReading("1512x982 outline shown by the user, node selected", 1472f, 874f, 272f, 360f, 170f, 824f, 664f),
        graphReading("1920x1080 at rest", 1880f, 972f, 272f, null, 170f, 1600f, 762f),
        graphReading("1920x1080 node selected", 1880f, 972f, 272f, 360f, 170f, 1232f, 762f),
        graphReading("1920x1080 node selected, drawer open", 1880f, 712f, 272f, 360f, 170f, 1232f, 502f),
        graphReading("1920x1080 node selected, drawer expanded", 1880f, 270f, 272f, 360f, 170f, 1232f, 60f),
        graphReading("1920x1080 outline hidden by the user", 1880f, 972f, null, null, 170f, 1880f, 762f),
        graphReading("1920x1080 outline hidden by the user, node selected", 1880f, 972f, null, 360f, 170f, 1512f, 762f),
    )

    private val graphSceneDriverReadingsWithTheOutlineHiddenByTheFifteenHundredRule = listOf(
        graphReading("1100x720 at rest", 1060f, 612f, null, null, 170f, 1060f, 402f),
        graphReading("1100x720 node selected", 1060f, 612f, null, 360f, 170f, 692f, 402f),
        graphReading("1100x720 node selected, drawer open", 1060f, 352f, null, 360f, 170f, 692f, 142f),
        graphReading("1100x720 node selected, drawer expanded", 1060f, 144f, null, 360f, 104f, 692f, 0f),
        graphReading("1512x982 at rest", 1472f, 874f, null, null, 170f, 1472f, 664f),
        graphReading("1512x982 node selected", 1472f, 874f, null, 360f, 170f, 1104f, 664f),
        graphReading("1512x982 node selected, drawer open", 1472f, 614f, null, 360f, 170f, 1104f, 404f),
        graphReading("1512x982 node selected, drawer expanded", 1472f, 236f, null, 360f, 170f, 1104f, 26f),
    )

    @Test
    fun theGraphPaneMatchesWhatSceneDriverReadAt1100And1512And1920WhereverTheOutlineIsTheUsersChoiceOrFits() =
        graphSceneDriverReadings.forEach { graph.reproduces(it) }

    @Test
    fun theGraphOutlineSceneDriverSawHiddenBelow1500dpIsTodaysOwnRuleWhichThePlanAloneWouldNotApply() =
        graphSceneDriverReadingsWithTheOutlineHiddenByTheFifteenHundredRule.forEach { reading ->
            graph.reproduces(reading)
            val withoutTheRule = graph.plan(reading.width, reading.height, 1f, PanePreferences(hidden = reading.hidden - "outline"))
            assertEquals(272f, withoutTheRule.sizes["outline"], reading.window)
        }

    private val drawerSceneDriverReadings = listOf(
        Reading("1100x720 drawer open", 1060f, 648f, mapOf("drawer" to 260f), 1060f, 388f),
        Reading("1512x982 drawer open", 1472f, 910f, mapOf("drawer" to 260f), 1472f, 650f),
        Reading("1920x1080 drawer open", 1880f, 1008f, mapOf("drawer" to 260f), 1880f, 748f),
    )

    private val expandedDrawerSceneDriverReadings = listOf(
        720f to Reading("1100x720 drawer expanded", 1060f, 648f, mapOf("drawer" to 468f), 1060f, 180f),
        982f to Reading("1512x982 drawer expanded", 1472f, 910f, mapOf("drawer" to 638f), 1472f, 272f),
        1080f to Reading("1920x1080 drawer expanded", 1880f, 1008f, mapOf("drawer" to 702f), 1880f, 306f),
    )

    @Test
    fun theDrawerOpensAt260AtEachWidthAsSceneDriverReadIt() = drawerSceneDriverReadings.forEach { drawer.reproduces(it) }

    @Test
    fun theExpandedDrawerSceneDriverReadIsSixtyFivePercentOfTheWindowWhichNoContainerSpecHoldsSoItsOwnerPassesItAsTheUsersSize() =
        expandedDrawerSceneDriverReadings.forEach { (window, reading) ->
            drawer.reproduces(reading, PanePreferences(sizes = mapOf("drawer" to window * 0.65f)))
            assertEquals(reading.height, drawer.plan(reading.width, reading.height, 1f, PanePreferences(sizes = mapOf("drawer" to Float.POSITIVE_INFINITY))).sizes["drawer"])
        }

    private val devToolsSceneDriverReadings = listOf(
        Reading("1512x982 one phone", 1472f, 870f, mapOf("screens" to 332f, "details" to 360f), 764f + 2 * handle, 870f),
        Reading("1920x1080 one phone", 1880f, 968f, mapOf("screens" to 429f, "details" to 360f), 1075f + 2 * handle, 968f),
    )

    private val devToolsSceneDriverReadingAt1100WithTheMiddleSqueezedBelow780 =
        Reading("1100x720 one phone", 1060f, 608f, mapOf("screens" to 300f, "details" to 360f), 384f + 2 * handle, 608f)

    @Test
    fun devToolsMatchesWhatSceneDriverReadAt1512And1920() = devToolsSceneDriverReadings.forEach { devTools(it.height).reproduces(it) }

    @Test
    fun devToolsAsSceneDriverReadItAt1100SqueezesItsMiddleBelow780dpWhereThePlanCollapsesTheScreensAndNarrowsTheDetails() {
        val today = devToolsSceneDriverReadingAt1100WithTheMiddleSqueezedBelow780
        val spec = devTools(today.height)
        assertTrue(today.mainWidth < spec.mainMinWidth)
        val plan = spec.plan(today.width, today.height, 1f, PanePreferences())
        assertEquals(setOf("screens"), plan.collapsed)
        assertEquals(mapOf("details" to 280f), plan.sizes)
        assertEquals(780f, plan.mainWidth)
    }
}
