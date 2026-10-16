@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.surface.react

import react.*

/** Structural surfaces use ordinary semantic HTML; apps supply tokens and slot content. */
@JsExport object SurfaceFrames {
    fun Bar(props: dynamic): dynamic = surfaceAppearance("Bar", props, element("div", attrs("className" to "surface-bar", "role" to props.role,
        "aria-label" to props.label), props.children))
    fun Section(props: dynamic): dynamic = surfaceAppearance("Section", props, element("section", attrs("className" to "surface-section", "aria-label" to props.label),
        element("header", null, props.heading, props.trailing), props.children))
    fun Property(props: dynamic): dynamic = surfaceAppearance("Property", props, element("div", attrs("className" to "surface-property"),
        element("span", attrs("className" to "surface-property__label"), props.label), props.children))
    fun Metric(props: dynamic): dynamic = surfaceAppearance("Metric", props, element("div", attrs("className" to "surface-metric"),
        element("span", null, props.label), element("strong", null, props.value), props.trend, props.detail))
    fun Finding(props: dynamic): dynamic = surfaceAppearance("Finding", props, element("article", attrs("className" to "surface-finding", "data-selected" to (props.selected == true)),
        element("header", null, props.mark, props.title, props.aside), props.detail, props.evidence, props.fix))
    fun ListRow(props: dynamic): dynamic = surfaceAppearance("ListRow", props, element("div", attrs("className" to "surface-list-row"), props.leading,
        element("div", null, props.title, props.detail), props.trailing))
    fun ActionGroup(props: dynamic): dynamic = actionGroup(props)
    fun Presence(props: dynamic): dynamic = element(SurfacePresence, props)
    fun ContactPlate(props: dynamic): dynamic = surfaceAppearance("ContactPlate", props, element("div", attrs("className" to "surface-contact-plate", "style" to attrs(
        "boxShadow" to "0 ${props.depth ?: 0}px 0 ${props.color ?: "transparent"}", "borderRadius" to props.radius)), props.children))
    fun MovingFace(props: dynamic): dynamic {
        val environment = useSurfaceEnvironment()
        val press = (props.press as Number?)?.toDouble() ?: 0.0
        val scale = 1 - (1 - ((props.scale as Number?)?.toDouble() ?: 1.0)) * press
        return surfaceAppearance("MovingFace", props, element("div", attrs("className" to "surface-moving-face", "style" to attrs("transform" to if (environment.reducedMotion) null else
            "translateY(${((props.travel as Number?)?.toDouble() ?: 0.0) * press}px) scale($scale)")), props.children))
    }
    fun Tag(props: dynamic): dynamic = surfaceAppearance("Tag", props, element("span", attrs("className" to "surface-tag", "data-automation-id" to props.id), props.children))
}
external interface SurfaceFrameProps : PropsWithChildren {
    var role: String?
    var label: String?
    var heading: ReactNode?
    var trailing: ReactNode?
    var leading: ReactNode?
    var title: ReactNode?
    var detail: ReactNode?
    var value: ReactNode?
    var trend: ReactNode?
    var mark: ReactNode?
    var aside: ReactNode?
    var evidence: ReactNode?
    var fix: ReactNode?
    var selected: Boolean?
}
val SurfaceBar = SurfaceFrames.asDynamic().Bar.unsafeCast<FC<SurfaceFrameProps>>()
val SurfaceSection = SurfaceFrames.asDynamic().Section.unsafeCast<FC<SurfaceFrameProps>>()
val SurfaceProperty = SurfaceFrames.asDynamic().Property.unsafeCast<FC<SurfaceFrameProps>>()
val SurfaceMetric = SurfaceFrames.asDynamic().Metric.unsafeCast<FC<SurfaceFrameProps>>()
val SurfaceFinding = SurfaceFrames.asDynamic().Finding.unsafeCast<FC<SurfaceFrameProps>>()
val SurfaceListRow = SurfaceFrames.asDynamic().ListRow.unsafeCast<FC<SurfaceFrameProps>>()
external interface SurfaceActionGroupProps : SurfaceFrameProps { var axis: String?; var dir: String? }
val SurfaceActionGroup = SurfaceFrames.asDynamic().ActionGroup.unsafeCast<FC<SurfaceActionGroupProps>>()

external interface SurfaceTagProps : PropsWithChildren { var id: String }
val SurfaceTag = SurfaceFrames.asDynamic().Tag.unsafeCast<FC<SurfaceTagProps>>()
