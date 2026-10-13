import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }
dependeasy {
    javascript("mcpTypeScript") {
        kotlinLibraries(kotlinJsLibrary(Reaktor.Mcp))
        verify("checkMcpTypeScript", "ts/tsconfig.json", checks = listOf(check()))
    }
    module("dev.shibasis.reaktor.mcp") {
        common {
            dependencies {
                commonSerialization(protobuf = false)
                api(projects.reaktorGraphRuntime)
                implementation(Versions.Tooling.McpClient)
            }
        }
        android {}
        apple {}
        web {}

        jvm {
            bytecode = 21
            dependencies {
                implementation(Versions.Tooling.McpServer)
            }
        }
    }
}
