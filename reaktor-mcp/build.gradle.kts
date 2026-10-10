import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonSerialization

plugins { id("dev.shibasis.dependeasy.library") }
dependeasy {
    javascript("mcpTypeScript") {
        kotlinLibraries("reaktor-mcp")
        verify("checkMcpTypeScript", "ts/tsconfig.json", checks = listOf(check()))
    }
    module("dev.shibasis.reaktor.mcp") {
        common {
            dependencies {
                commonSerialization(protobuf = false)
                api(project(":reaktor-graph-runtime"))
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
