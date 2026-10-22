package dev.shibasis.reaktor.cloud.observability

import dev.shibasis.reaktor.core.build.Entry
import com.pulumi.Pulumi


@Entry
fun main() {
    Pulumi.run(CurrentObservability::declare)
}
