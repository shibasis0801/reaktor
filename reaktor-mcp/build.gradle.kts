import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.FileSet
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.mcp", targets = Targets.All, bytecode = 21) {
    val packageSources by webPackage(".")
    dependencies {
        common { commonSerialization(protobuf = false); api(Reaktor.GraphRuntime); implementation(Versions.Tooling.McpClient) }
        jvm { implementation(Versions.Tooling.McpServer) }
    }
    val checkMcpTypeScript by typescript(kotlin = listOf(kotlinJsLibrary(Reaktor.Mcp)), tests = FileSet(listOf("ts/test/*.test.mjs")))
}
