package dev.shibasis.reaktor.core.truth

enum class TruthClass(val label: String, val abbreviation: String) {
    Live("Live", "LIVE"),
    Source("Source", "SRC"),
    Inferred("Inferred", "INF"),
    Imported("Imported", "IMP"),
    Stale("Stale", "STALE"),
    Partial("Partial", "PART"),
    Fixture("Fixture", "FIXTURE"),
    Failed("Failed", "FAILED"),
    Unknown("Unknown", "—");

    val provesHealth: Boolean get() = this == Live || this == Imported

    companion object {
        fun weakest(classes: Iterable<TruthClass>): TruthClass =
            classes.maxByOrNull { it.ordinal } ?: Unknown
    }
}

data class Fact<out T>(
    val value: T,
    val truth: TruthClass,
    val origin: String,
    val asOfEpochMillis: Long? = null,
) {
    val provesHealth: Boolean get() = truth.provesHealth

    fun <R> map(transform: (T) -> R): Fact<R> = Fact(transform(value), truth, origin, asOfEpochMillis)

    companion object {
        fun <T> live(value: T, origin: String, asOfEpochMillis: Long? = null) =
            Fact(value, TruthClass.Live, origin, asOfEpochMillis)

        fun <T> source(value: T, origin: String) = Fact(value, TruthClass.Source, origin)

        fun <T> inferred(value: T, origin: String) = Fact(value, TruthClass.Inferred, origin)

        fun <T> fixture(value: T, origin: String) = Fact(value, TruthClass.Fixture, origin)

        fun <T> stale(value: T, origin: String, asOfEpochMillis: Long? = null) =
            Fact(value, TruthClass.Stale, origin, asOfEpochMillis)

        fun <T> failed(value: T, origin: String) = Fact(value, TruthClass.Failed, origin)

        fun <T> unknown(value: T, origin: String = "not probed") = Fact(value, TruthClass.Unknown, origin)
    }
}
