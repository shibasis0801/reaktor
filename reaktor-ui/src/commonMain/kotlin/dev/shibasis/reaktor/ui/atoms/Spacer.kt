package dev.shibasis.reaktor.ui.atoms

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Atom: Spacer
 *
 * Empty space for layout purposes.
 * Uses design tokens for consistent spacing.
 */

// ============================================================================
// TOKEN-BASED SPACERS
// ============================================================================

@Composable
fun RSpaceXxs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xxs).width(spacing.xxs))
}

@Composable
fun RSpaceXs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xs).width(spacing.xs))
}

@Composable
fun RSpaceSm() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.sm).width(spacing.sm))
}

@Composable
fun RSpaceMd() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.md).width(spacing.md))
}

@Composable
fun RSpaceLg() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.lg).width(spacing.lg))
}

@Composable
fun RSpaceXl() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xl).width(spacing.xl))
}

@Composable
fun RSpaceXxl() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xxl).width(spacing.xxl))
}

// ============================================================================
// DIRECTIONAL SPACERS
// ============================================================================

@Composable
fun RVerticalSpace(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun RHorizontalSpace(width: Dp) {
    Spacer(Modifier.width(width))
}

// ============================================================================
// CONVENIENCE VERTICAL
// ============================================================================

@Composable
fun RVerticalSpaceXxs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xxs))
}

@Composable
fun RVerticalSpaceXs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xs))
}

@Composable
fun RVerticalSpaceSm() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.sm))
}

@Composable
fun RVerticalSpaceMd() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.md))
}

@Composable
fun RVerticalSpaceLg() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.lg))
}

@Composable
fun RVerticalSpaceXl() {
    val spacing = Tokens.spacing
    Spacer(Modifier.height(spacing.xl))
}

// ============================================================================
// CONVENIENCE HORIZONTAL
// ============================================================================

@Composable
fun RHorizontalSpaceXxs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.xxs))
}

@Composable
fun RHorizontalSpaceXs() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.xs))
}

@Composable
fun RHorizontalSpaceSm() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.sm))
}

@Composable
fun RHorizontalSpaceMd() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.md))
}

@Composable
fun RHorizontalSpaceLg() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.lg))
}

@Composable
fun RHorizontalSpaceXl() {
    val spacing = Tokens.spacing
    Spacer(Modifier.width(spacing.xl))
}
