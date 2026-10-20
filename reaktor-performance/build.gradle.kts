import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.native.NativeAbi
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.performance", targets = Targets.All) {
    val packageSources by webPackage(".", "tools")
    val resources by webResources()
    dependencies { common { api(Reaktor.Core); commonSerialization() } }
    native(name = "ReaktorPerformance", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64))
    val checkPerformanceTypeScript by typescript()
}
