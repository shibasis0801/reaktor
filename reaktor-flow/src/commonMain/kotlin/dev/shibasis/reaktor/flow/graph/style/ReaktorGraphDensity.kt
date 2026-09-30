package dev.shibasis.reaktor.flow.graph.style

/**
 * Converts an authored logical-pixel style into the physical graph-space coordinates consumed by
 * layout, handles, canvas drawing and camera framing. Apply once, before building the projection,
 * and pass that same style to the renderer. This does not scale an existing camera or user layout.
 *
 * Density is the actual host display density, not camera zoom or a visual-fit multiplier. Font
 * metrics use the same scale as their rows; the existing px-to-sp renderer boundary remains the
 * owner of converting them to Compose units. Dimensionless ratios, counts and time are unchanged.
 */
fun ReaktorGraphStyle.atDisplayDensity(density: Float): ReaktorGraphStyle {
    require(density.isFinite() && density > 0f) { "Graph display density must be finite and positive" }
    if (density == 1f) return this
    val scale = density.toDouble()
    return copy(
        layout = layout.copy(
            rootOriginPx = layout.rootOriginPx * scale,
            columnGapPx = layout.columnGapPx * scale,
            rowGapPx = layout.rowGapPx * scale,
            compactColumnGapPx = layout.compactColumnGapPx * scale,
            compactRowGapPx = layout.compactRowGapPx * scale,
            groupColumnGapPx = layout.groupColumnGapPx * scale,
            targetContentWidthPx = layout.targetContentWidthPx * scale,
            targetContentHeightPx = layout.targetContentHeightPx * scale,
        ),
        node = node.copy(
            minWidthPx = node.minWidthPx * scale,
            titleHeightPx = node.titleHeightPx * scale,
            footerHeightPx = node.footerHeightPx * scale,
            measurementSlackPx = node.measurementSlackPx * scale,
            cornerRadiusPx = node.cornerRadiusPx * scale,
            verticalPaddingPx = node.verticalPaddingPx * scale,
            titlePaddingXPx = node.titlePaddingXPx * scale,
            titlePaddingYPx = node.titlePaddingYPx * scale,
            titleToBadgeGapPx = node.titleToBadgeGapPx * scale,
            bodyPaddingXPx = node.bodyPaddingXPx * scale,
            titleFontPx = node.titleFontPx * scale,
            titleCharWidthPx = node.titleCharWidthPx * scale,
            rootBadgeFontPx = node.rootBadgeFontPx * scale,
            rootBadgePaddingXPx = node.rootBadgePaddingXPx * scale,
            rootBadgePaddingYPx = node.rootBadgePaddingYPx * scale,
        ),
        port = port.copy(
            rowHeightPx = port.rowHeightPx * scale,
            columnGapPx = port.columnGapPx * scale,
            gapPx = port.gapPx * scale,
            dotSizePx = port.dotSizePx * scale,
            fontPx = port.fontPx * scale,
            charWidthPx = port.charWidthPx * scale,
            insetPx = port.insetPx * scale,
        ),
        region = region.copy(
            contentPaddingXPx = region.contentPaddingXPx * scale,
            contentPaddingTopPx = region.contentPaddingTopPx * scale,
            contentPaddingBottomPx = region.contentPaddingBottomPx * scale,
            childRegionGapXPx = region.childRegionGapXPx * scale,
            childRegionGapYPx = region.childRegionGapYPx * scale,
            boundsInsetXPx = region.boundsInsetXPx * scale,
            boundsInsetTopPx = region.boundsInsetTopPx * scale,
            boundsInsetBottomPx = region.boundsInsetBottomPx * scale,
            labelOffsetXPx = region.labelOffsetXPx * scale,
            labelOffsetYPx = region.labelOffsetYPx * scale,
            labelPaddingXPx = region.labelPaddingXPx * scale,
            labelPaddingYPx = region.labelPaddingYPx * scale,
            labelRadiusPx = region.labelRadiusPx * scale,
            cornerRadiusPx = region.cornerRadiusPx * scale,
            dashOnPx = region.dashOnPx * scale,
            dashOffPx = region.dashOffPx * scale,
            strokeWidthPx = region.strokeWidthPx * scale,
            selectedStrokeWidthPx = region.selectedStrokeWidthPx * scale,
        ),
        chrome = chrome.copy(
            overlayPaddingPx = chrome.overlayPaddingPx * scale,
            panelRadiusPx = chrome.panelRadiusPx * scale,
            borderWidthPx = chrome.borderWidthPx * scale,
            shellPaddingXPx = chrome.shellPaddingXPx * scale,
            shellPaddingYPx = chrome.shellPaddingYPx * scale,
            controlPaddingXPx = chrome.controlPaddingXPx * scale,
            controlPaddingYPx = chrome.controlPaddingYPx * scale,
            sectionGapPx = chrome.sectionGapPx * scale,
            itemGapPx = chrome.itemGapPx * scale,
            microGapPx = chrome.microGapPx * scale,
            legendWidthPx = chrome.legendWidthPx * scale,
            indicatorSizePx = chrome.indicatorSizePx * scale,
            miniMapWidthPx = chrome.miniMapWidthPx * scale,
            miniMapHeightPx = chrome.miniMapHeightPx * scale,
            miniMapInnerPaddingPx = chrome.miniMapInnerPaddingPx * scale,
            miniMapNodeMinSizePx = chrome.miniMapNodeMinSizePx * scale,
            miniMapNodeCornerPx = chrome.miniMapNodeCornerPx * scale,
            miniMapViewportCornerPx = chrome.miniMapViewportCornerPx * scale,
            miniMapViewportStrokePx = chrome.miniMapViewportStrokePx * scale,
            miniMapEdgeStrokePx = chrome.miniMapEdgeStrokePx * scale,
            hiddenHandleSizePx = chrome.hiddenHandleSizePx * scale,
            editorPaddingPx = chrome.editorPaddingPx * scale,
            titleFontPx = chrome.titleFontPx * scale,
            bodyFontPx = chrome.bodyFontPx * scale,
            captionFontPx = chrome.captionFontPx * scale,
        ),
        viewport = viewport.copy(
            chromeClearanceTopPx = viewport.chromeClearanceTopPx * scale,
            chromeClearanceBottomPx = viewport.chromeClearanceBottomPx * scale,
            chromeClearanceLeftPx = viewport.chromeClearanceLeftPx * scale,
            chromeClearanceRightPx = viewport.chromeClearanceRightPx * scale,
            readablePaddingXPx = viewport.readablePaddingXPx * scale,
            readablePaddingYPx = viewport.readablePaddingYPx * scale,
            fitPaddingXPx = viewport.fitPaddingXPx * scale,
            fitPaddingYPx = viewport.fitPaddingYPx * scale,
        ),
        typedNode = typedNode?.let { it.copy(familyHeightPx = it.familyHeightPx * scale) },
    )
}
