import dev.shibasis.dependeasy.components.webPackage
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.workspace") }
group = Reaktor.build.group

val javascriptPackage by webPackage("dependeasy/javascript")

workspace {
    val tooling = buildTooling()
    val webPackageChecks by verify {
        include(verification(Reaktor.Core, Verification.TypeScript), verification(Reaktor.Ui, Verification.TypeScript),
            verification(Reaktor.GraphPort, Verification.TypeScript), verification(Reaktor.Mcp, Verification.TypeScript),
            verification(Reaktor.Performance, Verification.TypeScript), verification(Reaktor.Web, Verification.WebBridge))
    }
    val mobileInteropChecks by verify {
        include(verification(Reaktor.Ffi, Verification.InteropTypeScript), verification(Reaktor.Ffi, Verification.InteropHost),
            output(Reaktor.Ffi, OutputKind.AndroidInstrumentedTests, Build.Debug),
            output(Reaktor.Ffi, OutputKind.AppleCompilation, Apple.IosArm64))
    }
    val frameworkChecks by verify(role = Workspace.Verify) {
        include(tooling)
        include(verification(Reaktor.Core, Verification.Jvm), verification(Reaktor.Graph, Verification.Jvm),
            verification(Reaktor.Performance, Verification.Jvm))
    }
    val androidNativeLibraries by artifacts(role = Workspace.Android) {
        outputs(androidLibrary(Reaktor.Ffi, Build.Release), androidLibrary(Reaktor.Flexbuffer, Build.Release))
    }
    val iosNativeLibraries by artifacts(role = Workspace.Apple) {
        outputs(nativeLibrary(Reaktor.Ffi, Apple.IosArm64), nativeLibrary(Reaktor.Ffi, Apple.IosSimulatorArm64))
    }
    val buildAll by artifacts(role = Workspace.Build, run = Run.onMac()) {
        include(tooling, webPackageChecks, mobileInteropChecks)
        outputs(*Reaktor.modules.map { output(it, OutputKind.ModuleBuild) }.toTypedArray())
        outputs(verification(Dependeasy.build, Verification.Plugin))
    }
    publishing { githubPackages() }
    packageScripts()
}
