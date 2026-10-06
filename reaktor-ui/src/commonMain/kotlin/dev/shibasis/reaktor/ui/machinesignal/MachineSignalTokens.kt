package dev.shibasis.reaktor.ui.machinesignal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.core.truth.TruthClass

object MachineSignal {
    /** Living Graph editor tokens: reaktor.pen ms2-* variables and MG56E shell geometry. */
    object Editor {
        val Canvas = Color(0xFF17191F)
        val Surface = Color(0xFF202229)
        val Raised = Color(0xFF292C35)
        val Line = Color(0xFF3A3F4B)
        val Text = Color(0xFFE5E7ED)
        val Muted = Color(0xFFADB4C3)
        val Accent = Color(0xFF6F8FFF)
        val AccentSoft = Color(0x1F6F8FFF)
        val Source = Color(0xFF8DA7FF)
        val Unknown = Color(0xFF9EABC1)
        val menuHeight = 28.dp
        val toolbarHeight = 42.dp
        val documentTabHeight = 34.dp
        val toolRailWidth = 40.dp
        val toolHitSize = 36.dp
        val navigatorWidth = 280.dp
        val inspectorWidth = 320.dp
        val statusHeight = 24.dp
        val drawerStripHeight = 30.dp
        val drawerHeadingHeight = 32.dp
        val drawerGap = 14.dp
        val controlHeight = 28.dp
        val label = 12.sp
        val meta = 11.sp
        // MG56E application menu and execution toolbar, measured from the reusable Pencil component.
        val brand = 13.sp
        val brandWidth = 46.dp
        val menuGap = 18.dp
        val toolbarGap = 9.dp
        val controlGap = 6.dp
        val controlIconSize = 15.dp
        val controlRadius = 4.dp
        val projectControlWidth = 94.dp
        val branchControlWidth = 65.dp
        val searchControlWidth = 218.dp
        val shortcutWidth = 39.dp
        val shortcutHeight = 22.dp
        val targetControlWidth = 104.dp
        val runControlWidth = 60.dp
        val debugControlWidth = 75.dp
        val developControlWidth = 84.dp
        val sectionHeaderHeight = 32.dp
        val treeRowHeight = 27.dp
        val layerRowHeight = 34.dp
        val sectionPaddingX = 10.dp
        val treeIndent = 12.dp
        val smallIconSize = 14.dp
        val graphContextHeight = 38.dp
        const val fontFamily = "Inter"
        const val lineHeight = 1.3f

        /** The text plane inside the shell: what a line of code is painted with. */
        object Code {
            val CurrentLine = Color(0xFF1E2129)
            val Selection = Color(0x455C80FF)
            val Match = Color(0x40F3B84B)
            val MatchActive = Color(0x80F3B84B)
            val Occurrence = Color(0x22ADB4C3)
            val Gutter = Color(0xFF5C6676)
            val GutterActive = Color(0xFFADB4C3)
            val GutterLine = Color(0xFF2B2F38)
            val Caret = Color(0xFF6F8FFF)
            val BracketMatch = Color(0x556F8FFF)

            val Keyword = Color(0xFF9A8CFF)
            val Type = Color(0xFF60DDEB)
            val Function = Color(0xFF8DA7FF)
            val Builtin = Color(0xFF55D3C3)
            val Number = Color(0xFFF5B84B)
            val Text = Color(0xFF42D392)
            val Comment = Color(0xFF6B7484)
            val Doc = Color(0xFF7E8AA0)
            val Annotation = Color(0xFFFB923C)
            val Operator = Color(0xFFADB4C3)
            val Bracket = Color(0xFFC6CEDC)

            val gutterPaddingX = 10.dp
            val lineNumberGap = 12.dp
            val caretWidth = 1.5.dp
            const val lineHeight = 1.45f
        }
    }

    /** N54ZXW typed node, with FsQx4's 260px whole-application card width. */
    object GraphCard {
        const val width = 260.0
        const val radius = 5.0
        const val familyHeight = 24.0
        const val titleHeight = 30.0
        const val portHeight = 22.0
        const val footerHeight = 22.0
        const val paddingX = 10.0
        const val gap = 6.0
        const val titleFont = 13.0
        const val portFont = 11.0
        const val metaFont = 10.0
        const val pinSize = 8.0
        val Pin = Color(0xFF38BDF8)
        val kindColors = mapOf(
            "Actor" to Color(0xFF38BDF8), "Interactor" to Color(0xFF9A8CFF),
            "Repository" to Color(0xFFF5B84B), "Route" to Color(0xFF8DA7FF),
            "Screen" to Color(0xFF42D392), "Service" to Color(0xFFFB923C),
        )
    }

    val Bg0 = Color(0xFF06080D)
    val Bg1 = Color(0xFF0B1018)
    val Bg2 = Color(0xFF111925)
    val Bg3 = Color(0xFF182235)
    val Bg4 = Color(0xFF22304A)

    val Line1 = Color(0xFF202A3D)
    val Line2 = Color(0xFF2B3850)
    val Line3 = Color(0xFF3A4A68)

    val Text1 = Color(0xFFF0F3F8)
    val Text2 = Color(0xFFB3BDD0)
    val Text3 = Color(0xFF8995AD)
    val Text4 = Color(0xFF748198)

    val Accent = Color(0xFF5C80FF)
    val Accent2 = Color(0xFF8DA7FF)
    val AccentSoft = Color(0x1A5C80FF)
    val AccentLine = Color(0x735C80FF)
    val AccentText = Color(0xFFD7E0FF)

    val SelectedSoft = Color(0x205C80FF)
    val Signal = Color(0xFF60DDEB)

    object Entity {
        val Route = Color(0xFF658BFF)
        val Screen = Color(0xFF65B58E)
        val Container = Color(0xFF9D85E6)
        val Service = Color(0xFFD2A05C)
        val Cloud = Color(0xFF48B8D0)
        val Actor = Color(0xFF55AFC2)
        val Auth = Color(0xFFD977A8)
        val Infra = Color(0xFF8995AD)
        val Agent = Color(0xFF55D3C3)
        val Topic = Color(0xFFB07CFF)
        val Repo = Color(0xFFB98952)
        val Interactor = Color(0xFF8D73D9)
    }

    object Edge {
        val Exec = Color(0xFFE6EAF2)
        val Navigation = Color(0xFF3B82F6)
        val Data = Color(0xFF8B5CF6)
        val Attachment = Color(0xFF6366F1)
        val Containment = Color(0xFFA78BFA)
        val PortOff = Color(0xFF66748B)
    }

    object Status {
        val Ok = Color(0xFF35C978)
        val Warn = Color(0xFFF3B84B)
        val Error = Color(0xFFFF666B)
    }

    object Space {
        val none = 0.dp
        val s0_25 = 1.dp
        val s0_5 = 2.dp
        val s0_75 = 3.dp
        val s1 = 4.dp
        val s1_25 = 5.dp
        val s1_5 = 6.dp
        val s1_75 = 7.dp
        val s2 = 8.dp
        val s2_25 = 9.dp
        val s2_5 = 10.dp
        val s3 = 12.dp
        val s3_5 = 14.dp
        val s4 = 16.dp
        val s4_5 = 18.dp
        val s5 = 24.dp
        val s6 = 32.dp
    }

    object Stroke {
        val hairline = 1.dp
        val bar = 2.dp
    }

    object Dot {
        val small = 6.dp
        val regular = 7.dp
        val large = 8.dp
    }

    object Column {
        val w22 = 22.dp
        val w34 = 34.dp
        val w36 = 36.dp
        val w50 = 50.dp
        val w56 = 56.dp
        val w60 = 60.dp
        val w64 = 64.dp
        val w70 = 70.dp
        val w72 = 72.dp
        val w76 = 76.dp
        val w78 = 78.dp
        val w80 = 80.dp
        val w82 = 82.dp
        val w84 = 84.dp
        val w86 = 86.dp
        val w88 = 88.dp
        val w90 = 90.dp
        val w92 = 92.dp
        val w96 = 96.dp
        val w98 = 98.dp
        val w100 = 100.dp
        val w104 = 104.dp
        val w108 = 108.dp
        val w110 = 110.dp
        val w112 = 112.dp
        val w116 = 116.dp
        val w120 = 120.dp
        val w128 = 128.dp
        val w130 = 130.dp
        val w132 = 132.dp
        val w150 = 150.dp
        val w180 = 180.dp
        val w190 = 190.dp
    }

    object Shape {
        val Tight = RoundedCornerShape(3.dp)
        val Control = RoundedCornerShape(5.dp)
        val Panel = RoundedCornerShape(6.dp)
        val Node = RoundedCornerShape(7.dp)
    }

    object Metrics {
        val topBarHeight = 44.dp
        val modeToolbarHeight = 38.dp
        val workspaceTabsHeight = 34.dp
        val contextBarHeight = 40.dp
        val statusBarHeight = 22.dp
        val drawerStripHeight = 30.dp
        val drawerHeight = 260.dp
        val railStripWidth = 28.dp
        val leftRailWidth = 224.dp
        val inspectorWidth = 296.dp

        val buttonHeight = 30.dp
        val buttonPaddingX = 16.dp
        val ghostPaddingX = 14.dp
        val buttonGap = 7.dp

        val searchFieldHeight = 30.dp
        val searchFieldWidth = 280.dp
        val searchPaddingX = 10.dp

        val modeTabHeight = 26.dp
        val modeTabPaddingX = 11.dp
        val modeTabGap = 6.dp

        val subTabPaddingX = 12.dp
        val subTabPaddingTop = 6.dp
        val subTabGap = 5.dp
        val subTabUnderlineHeight = 2.dp
        val subTabHeight = 27.dp

        val kvRowHeight = 22.dp
        val treeRowHeight = 26.dp
        val commandRowHeight = 44.dp

        val chipPaddingX = 8.dp
        val chipPaddingY = 3.dp
        val entityChipPaddingX = 9.dp
        val entityChipPaddingY = 4.dp
        val statusPillPaddingX = 10.dp
        val statusPillPaddingY = 4.dp
        val statusPillGap = 6.dp
        val countBadgePaddingX = 7.dp
        val countBadgePaddingY = 2.dp
        val kbdPaddingX = 7.dp
        val kbdPaddingY = 3.dp

        val metricTileWidth = 220.dp
        val metricTilePadding = 16.dp
        val metricTileGap = 8.dp

        val shellPaddingX = 14.dp
        val shellGap = 12.dp
        val statusBarGap = 16.dp
        val contextBarGap = 10.dp
        val railStripGap = 10.dp

        val kbdHeight = 19.dp
        val kindBadgeHeight = 19.dp
        val kindBadgePaddingX = 8.dp
        val kindBadgePaddingY = 3.dp
        val entityChipHeight = 22.dp
        val statusPillHeight = 22.dp
        val statusDotSize = 6.dp
        val branchPillHeight = 26.dp
        val branchPillPaddingX = 10.dp
        val branchPillGap = 7.dp
        val treeRowPaddingX = 8.dp
        val treeRowGap = 8.dp
        val commandRowPaddingX = 10.dp
        val commandRowGap = 10.dp
        val envSegmentedHeight = 28.dp
        val envSegmentedPadding = 3.dp
        val envSegmentedGap = 2.dp
        val avatarSize = 28.dp
        val searchFieldGap = 8.dp
        val searchIconSize = 12.dp

        /** The graph surface: ports, nodes, wires and canvas chrome. */
        object Graph {
            val execPin = 12.dp
            val dataPin = 10.dp
            val offPin = 8.dp
            val pinStroke = 1.5.dp
            val rerouteSize = 10.dp

            val nodeWidth = 230.dp
            val nodeHeadHeight = 28.dp
            val nodeFootHeight = 20.dp
            val nodePortRowHeight = 18.dp
            val nodePaddingX = 10.dp
            val scopeSummaryHeight = 60.dp
            val scopeSummaryBodyHeight = 32.dp

            val wireLabelHeight = 20.dp
            val wireLabelPaddingX = 8.dp
            val wireValuePaddingX = 9.dp
            val wireValueGap = 5.dp
            val wireValueRadius = 10.dp

            val minimapWidth = 212.dp
            val minimapHeight = 134.dp
            val zoomClusterHeight = 30.dp
            val zoomClusterPaddingX = 6.dp
            val zoomClusterGap = 2.dp
            val zoomSegmentHeight = 22.dp
            val legendWidth = 196.dp
            val legendPadding = 10.dp
            val legendGap = 2.dp
            val legendRowHeight = 20.dp
            val canvasStatsGap = 8.dp

            val connectionCardHeight = 46.dp
            val connectionCardPaddingX = 12.dp
            val codeDiffHeadHeight = 28.dp
            val codeDiffLineHeight = 20.dp

            val sparklineWidth = 72.dp
            val sparklineHeight = 22.dp
            val sparklineStroke = 1.6.dp
        }
    }

    object Radius {
        val mark = 2.dp
        val tight = 3.dp
        val control = 5.dp
        val panel = 6.dp
        val node = 7.dp
        val card = 8.dp
        val countBadge = 9.dp
        val pill = 10.dp
        val statusPill = 12.dp
    }

    object Type {
        // Prose ramp: labels and controls the eye reads as language. Deliberately larger than the
        // design file, which authors chrome down to 9.5px — see the divergence ledger.
        val label = 11.sp
        val body = 13.sp
        val title = 17.sp
        val display = 24.sp
        val title2 = 16.sp
        val title3 = 15.sp
        val title4 = 14.sp
        val body2 = 11.5.sp
        val fine = 10.sp

        val micro = label
        val eyebrow = label
        val caption = body
        val control = body
        val heading = title

        // Data ramp: monospaced values in badges, chips, rows and status bars, where density is
        // the point and the design's authored sizes are exactly right. These match reaktor.pen.
        val data = 10.5.sp
        val dataMicro = 9.5.sp
        val dataStrong = 11.sp

        val eyebrowTracking = 0.06.sp
        val kindTracking = 1.sp
        val base = TextStyle(fontSize = body2, lineHeight = 15.sp, letterSpacing = 0.sp)
    }

    object ActivityStrip {
        val height = 64.dp
    }

    object AgentContext {
        val nextMaxHeight = 360.dp
    }

    object AgentInspector {
        val stepToolWidth = 130.dp
    }

    object AgentReview {
        val checksMaxHeight = 480.dp
        val resultsMaxHeight = 360.dp
    }

    object AgentWorkflow {
        val runbooksMaxHeight = 240.dp
    }

    object AiFindings {
        val maxHeight = 240.dp
    }

    object AiInspector {
        val usesMaxHeight = 240.dp
    }

    object AnalyticsChart {
        val height = 90.dp
    }

    object AuditChart {
        val height = 64.dp
    }

    object AuditFindings {
        val maxHeight = 420.dp
    }

    object BotRail {
        val width = 330.dp
    }

    object BottomBar {
        val widgetDividerHeight = 12.dp
        val widgetIconSize = 12.dp
    }

    object Breakpoint {
        val compact = 720.dp
        val extraWide = 1500.dp
        val medium = 900.dp
        val wide = 1240.dp
    }

    object Chart {
        val empty = Color.White.copy(alpha = .08f)
        val track = Color.White.copy(alpha = .05f)
        val selection = Color.White
    }

    object ChartLegend {
        val swatchSize = 8.dp
    }

    object CloudAppSection {
        val labelWidth = 92.dp
    }

    object CloudInspector {
        val bindingLabelWidth = 150.dp
        val changeAgeWidth = 64.dp
        val relationVerbWidth = 112.dp
    }

    object CodeBlock {
        val copyInset = 34.dp
    }

    object CommandRunner {
        val actionWidth = 120.dp
    }

    object CostLine {
        val labelWidth = 170.dp
        val valueWidth = 150.dp
    }

    object DataDocument {
        val blockerMaxHeight = 124.dp
        val identityMaxHeight = 160.dp
    }

    object DataInspector {
        val keyMarkWidth = 10.dp
    }

    object DataSystemBar {
        val height = 32.dp
    }

    object DeployOrder {
        val labelWidth = 84.dp
    }

    object DetailCode {
        val lineHeight = 17.dp
        val inset = 12.dp
        val minHeight = 40.dp
        val requestHeight = 220.dp
        val shortHeight = 240.dp
        val mediumHeight = 280.dp
        val tallHeight = 360.dp
    }

    object Device {
        val frameWidth = 8.dp
        val phoneHeight = 900.dp
        val phoneRadius = 40.dp
        val phoneScreenRadius = 32.dp
        val phoneWidth = 440.dp
        val tabletHeight = 960.dp
        val tabletRadius = 30.dp
        val tabletScreenRadius = 24.dp
        val tabletWidth = 720.dp
        val screen = Color.Black
    }

    object DeviceBar {
        val height = 40.dp
    }

    object Drawer {
        val runOutputHeight = 220.dp
        val tabHeight = 38.dp
    }

    object ElementRow {
        val height = 24.dp
    }

    object ExecutionTrace {
        val ageWidth = 56.dp
        val durationWidth = 64.dp
        val headerHeight = 30.dp
        val outcomeWidth = 44.dp
        val rowHeight = 22.dp
    }

    object FindingStrip {
        val severityWidth = 62.dp
    }

    object FixtureRows {
        val labelWidth = 128.dp
    }

    object Funnel {
        val barHeight = 16.dp
        val countWidth = 48.dp
        val labelWidth = 180.dp
        val shareWidth = 56.dp
    }

    object GatewayTraffic {
        val emptyHeight = 150.dp
    }

    object GatewayTable {
        val headerHeight = 28.dp
        val rowHeight = 22.dp
    }

    object Gauge {
        val ringSize = 56.dp
        val strokeWidth = 5.dp
        val width = 84.dp
    }

    object Glance {
        val authLabelWidth = 72.dp
        val barHeight = 6.dp
        val canvasInset = 376.dp
        val cloudLabelWidth = 84.dp
        val dataFindingsMaxHeight = 300.dp
        val dataLabelWidth = 70.dp
        val findingsMaxHeight = 340.dp
        val graphCanvasInset = 364.dp
        val graphLabelWidth = 76.dp
        val graphOnScreenMaxHeight = 240.dp
        val graphWidth = 348.dp
        val loadLabelWidth = 52.dp
        val pipelineWidth = 320.dp
        val trendHeight = 16.dp
        val width = 360.dp
    }

    object GraphInspector {
        val factLabelWidth = 96.dp
        val roleStripeHeight = 4.dp
        val trafficCallsWidth = 48.dp
        val trafficP50Width = 88.dp
    }

    object GraphResults {
        val width = 300.dp
    }

    object GraphWorkspace {
        val liveBorderWidth = 1.5.dp
    }

    object HistoryRow {
        val actorWidth = 150.dp
        val kindWidth = 86.dp
        val resourceWidth = 240.dp
        val timeWidth = 104.dp
    }

    object Icon {
        val viewport = 24.dp
        val fill = Color.Black
    }

    object IdleCapacity {
        val barHeight = 10.dp
    }

    object InfoRows {
        val labelWidth = 120.dp
    }

    object InspectionWorkspace {
        val elementsWidth = 300.dp
        val sourceDockHeight = 150.dp
    }

    object InspectorList {
        val heightCap = 4000.dp
    }

    object Inventory {
        val rowHeight = 38.dp
    }

    object Lab {
        val catalogueWidth = 220.dp
        val cellHeight = 440.dp
        val cellWidth = 320.dp
        val codeViewHeight = 240.dp
        val environmentWidth = 280.dp
        val hangarStoryHeight = 600.dp
        val islandStoryHeight = 460.dp
        val liveHeight = 720.dp
        val liveWidth = 390.dp
        val markHeight = 36.dp
        val markWidth = 200.dp
        val mediumStoryWidth = 900.dp
        val narrowPaneHostWidth = 600.dp
        val paneHostHeight = 640.dp
        val panesStoryHeight = 700.dp
        val rowLabelWidth = 96.dp
        val sampleHeight = 320.dp
        val splitMin = 80.dp
        val staleViewHeight = 56.dp
        val stateViewHeight = 220.dp
        val swatchSize = 18.dp
        val tableHeight = 420.dp
        val tableStoryHeight = 480.dp
        val wideStoryWidth = 1100.dp
    }

    object Ledger {
        val measureWidth = 96.dp
    }

    object LivePreview {
        val noticeMaxWidth = 520.dp
        val sidebarWidth = 440.dp
    }

    object LogRow {
        val height = 22.dp
        val levelWidth = 12.dp
        val tagWidth = 150.dp
        val timeWidth = 62.dp
    }

    object Matrix {
        val cellHeight = 22.dp
        val emptyCellMark = 3.dp
        val headerHeight = 118.dp
        val holdersColumn = 150.dp
        val permissionColumn = 300.dp
        val permissionIndent = 22.dp
        val roleColumn = 30.dp
    }

    object McpDoor {
        val approvalFieldWidth = 260.dp
        val approvalsMaxHeight = 420.dp
        val callsMaxHeight = 360.dp
        val callsMinHeight = 120.dp
    }

    object McpInspector {
        val argumentsHeight = 110.dp
        val schemaHeight = 120.dp
    }

    object MeasureEventRow {
        val detailIndent = 70.dp
        val offsetWidth = 64.dp
        val typeWidth = 120.dp
    }

    object MeasureHeader {
        val statusMaxWidth = 220.dp
    }

    object Mirror {
        val frameRadius = 14.dp
        val headerHeight = 34.dp
        val readoutLift = 26.dp
        val toolbarHeight = 30.dp
    }

    object ModeRail {
        val width = 188.dp
    }

    object NodeTree {
        val detailLabelWidth = 92.dp
        val detailsMinHeight = 180.dp
        val disclosureHeight = 22.dp
        val disclosureWidth = 15.dp
        val splitHandleHeight = 22.dp
        val splitHandlePillHeight = 18.dp
        val splitHandlePillWidth = 58.dp
        val splitHandleStroke = 1.4.dp
        val treeMinHeight = 160.dp
    }

    object OperationRow {
        val actionsWidth = 150.dp
        val blockedMaxWidth = 360.dp
        val detailIndent = 36.dp
        val lastRunMinWidth = 180.dp
    }

    object Palette {
        val emptyInset = 20.dp
        val maxHeight = 640.dp
        val maxWidth = 760.dp
        val minWidth = 620.dp
        val topInset = 72.dp
    }

    object PaneSearch {
        val compactWidth = 240.dp
        val modelsWidth = 220.dp
        val sessionsWidth = 360.dp
        val socketsWidth = 260.dp
        val standardWidth = 300.dp
        val wideWidth = 320.dp
    }

    object PersonActivity {
        val sparklineHeight = 28.dp
    }

    object PipelineInspector {
        val linkLabelWidth = 76.dp
    }

    object PortStatsRow {
        val callsWidth = 48.dp
        val durationWidth = 84.dp
        val failuresWidth = 64.dp
        val height = 24.dp
        val portWidth = 200.dp
    }

    object QueryPlan {
        val documentHeight = 280.dp
        val emptyWidth = 480.dp
        val toolbarHeight = 40.dp
    }

    object RefreshChain {
        val indexWidth = 28.dp
    }

    object Results {
        val cellValueHeight = 120.dp
        val comparisonStatementHeight = 64.dp
        val nodePropertiesHeight = 200.dp
    }

    object RowMarker {
        val compactHeight = 12.dp
        val height = 14.dp
        val outlineHeight = 13.dp
        val width = 3.dp
    }

    object SchemaView {
        val emptyWidth = 460.dp
        val searchResultsMaxHeight = 360.dp
        val searchResultsWidth = 320.dp
        val viewNameWidth = 170.dp
    }

    object Scrim {
        val modal = Color.Black.copy(alpha = .72f)
        val readout = Color.Black.copy(alpha = .62f)
    }

    object SearchField {
        val iconSize = 13.dp
    }

    object SessionChart {
        val height = 56.dp
        val width = 420.dp
    }

    object SimulatorInput {
        val width = 360.dp
    }

    object SimulatorSteps {
        val titleWidth = 170.dp
    }

    object SocketMessageRow {
        val offsetWidth = 56.dp
        val sizeWidth = 52.dp
    }

    object Sparkline {
        val deployTrafficWidth = 56.dp
        val errorsHeight = 18.dp
        val inlineHeight = 14.dp
        val portLatencyWidth = 80.dp
        val requestsHeight = 36.dp
        val resourceTrafficWidth = 96.dp
        val usageHeight = 28.dp
    }

    object TenancyTree {
        val width = 272.dp
    }

    object TokenChecks {
        val nameWidth = 84.dp
        val width = 440.dp
    }

    object TokenClaims {
        val nameWidth = 110.dp
    }

    object TokenInput {
        val maxHeight = 140.dp
        val minHeight = 72.dp
    }

    object VerdictTrace {
        val stepNumberWidth = 14.dp
    }

    object WindowChrome {
        val trafficLightRowHeight = 40.dp
        val trafficLightsWidth = 78.dp
    }

    object ZoomBar {
        val levelWidth = 44.dp
    }
    fun provenance(truth: TruthClass): ProvenanceColors = when (truth) {
        TruthClass.Live -> ProvenanceColors(Color(0xFF35C978), Color(0x1435C978), Color(0x5235C978))
        TruthClass.Source -> ProvenanceColors(Color(0xFF5C80FF), Color(0x165C80FF), Color(0x525C80FF))
        TruthClass.Inferred -> ProvenanceColors(Color(0xFFB07CFF), Color(0x14B07CFF), Color(0x52B07CFF))
        TruthClass.Imported -> ProvenanceColors(Color(0xFF4DD0E1), Color(0x144DD0E1), Color(0x524DD0E1))
        TruthClass.Stale, TruthClass.Partial ->
            ProvenanceColors(Color(0xFFF3B84B), Color(0x14F3B84B), Color(0x52F3B84B))
        TruthClass.Fixture -> ProvenanceColors(Color(0xFF748198), Color(0x14748198), Color(0x52748198))
        TruthClass.Failed -> ProvenanceColors(Color(0xFFFF666B), Color(0x14FF666B), Color(0x52FF666B))
        TruthClass.Unknown -> ProvenanceColors(Color(0xFF748198), Color(0x0F748198), Color(0x33748198))
    }

    fun statusColor(status: String): Color = when (status.lowercase()) {
        "available", "succeeded", "ok" -> Status.Ok
        "degraded", "partial", "awaitingapproval" -> Status.Warn
        "unavailable", "failed", "blocked" -> Status.Error
        else -> Text4
    }

    fun entityColor(kind: String): Color = when (kind.lowercase()) {
        "route" -> Entity.Route
        "screen", "ui" -> Entity.Screen
        "container", "group" -> Entity.Container
        "service", "worker" -> Entity.Service
        "cloud", "edge" -> Entity.Cloud
        "actor" -> Entity.Actor
        "auth" -> Entity.Auth
        "infra" -> Entity.Infra
        "agent" -> Entity.Agent
        "topic", "queue" -> Entity.Topic
        "repository", "repo", "data", "database", "store" -> Entity.Repo
        "interactor" -> Entity.Interactor
        else -> Text3
    }
}

data class ProvenanceColors(val base: Color, val soft: Color, val line: Color)
