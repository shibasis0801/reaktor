package dev.shibasis.reaktor.performance

import android.content.Context
import sh.measure.android.Measure
import sh.measure.android.config.MeasureConfig

fun ReaktorMeasure.initialize(context: Context, fullCollection: Boolean = false) =
    Measure.init(context, MeasureConfig(enableFullCollectionMode = fullCollection))
