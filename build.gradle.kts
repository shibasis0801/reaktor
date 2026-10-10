plugins { id("dev.shibasis.dependeasy.pipeline") }

dependeasy {
    buildTooling()
    workspace {
        verify("webPackageChecks", ":reaktor-core:buildCoreTypeScript", ":reaktor-ui:buildUiTypeScript",
            ":reaktor-graph-port:checkGraphPortTypeScript", ":reaktor-mcp:checkMcpTypeScript",
            ":reaktor-performance:checkPerformanceTypeScript", ":reaktor-web:testWebBridge")
        verify("mobileInteropChecks", ":reaktor-ffi:interopTypeScriptVerify", ":reaktor-ffi:interopHostCheck",
            ":reaktor-ffi:assembleDebugAndroidTest", ":reaktor-ffi:compileKotlinIosArm64") { macos() }
        verify("frameworkChecks", ":checkBuildTooling", ":reaktor-core:jvmTest", ":reaktor-graph:jvmTest", ":reaktor-performance:jvmTest")
        artifacts("androidNativeLibraries", ":reaktor-ffi:assembleRelease", ":reaktor-flexbuffer:assembleRelease")
        artifacts("iosNativeLibraries", ":reaktor-ffi:iphoneosCMake", ":reaktor-ffi:iphonesimulatorCMake") { macos() }
    }
    publishing {
        githubPackages()
        includedBuild("dependeasy")
    }
}
