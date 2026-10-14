import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.pipeline") }
group = Reaktor.build.group

dependeasy {
    val tooling = buildTooling()
    workspace {
        dagger()
        val webPackages = verify("webPackageChecks") {
            consumes(verification(Reaktor.Core, Verification.TypeScript), verification(Reaktor.Ui, Verification.TypeScript),
                verification(Reaktor.GraphPort, Verification.TypeScript), verification(Reaktor.Mcp, Verification.TypeScript),
                verification(Reaktor.Performance, Verification.TypeScript), verification(Reaktor.Web, Verification.WebBridge))
        }
        val mobileInterop = verify("mobileInteropChecks") {
            consumes(verification(Reaktor.Ffi, Verification.InteropTypeScript), verification(Reaktor.Ffi, Verification.InteropHost),
                output(Reaktor.Ffi, OutputKind.AndroidInstrumentedTests, Build.Debug),
                output(Reaktor.Ffi, OutputKind.AppleCompilation, Apple.IosArm64))
        }
        verify("frameworkChecks", role = Workspace.Verify) {
            includes(tooling)
            consumes(verification(Reaktor.Core, Verification.Jvm), verification(Reaktor.Graph, Verification.Jvm),
                verification(Reaktor.Performance, Verification.Jvm))
        }
        artifacts("androidNativeLibraries", role = Workspace.Android) {
            consumes(androidLibrary(Reaktor.Ffi, Build.Release), androidLibrary(Reaktor.Flexbuffer, Build.Release))
        }
        artifacts("iosNativeLibraries", role = Workspace.Apple) {
            consumes(nativeLibrary(Reaktor.Ffi, Apple.IosArm64), nativeLibrary(Reaktor.Ffi, Apple.IosSimulatorArm64))
        }
        artifacts("buildAll", role = Workspace.Build) {
            includes(tooling, webPackages, mobileInterop)
            consumes(*Reaktor.modules.map { output(it, OutputKind.ModuleBuild) }.toTypedArray())
            consumes(verification(Dependeasy.build, Verification.Plugin))
            host = HostRequirement(HostPlatform.Macos)
        }
    }
    publishing { githubPackages() }
}
