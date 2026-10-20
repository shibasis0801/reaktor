package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.model.tools.AppleGradle
import dev.shibasis.dependeasy.model.tools.Xcodebuild

object AppleReleaseKernel {
    fun plan(spec: KernelSpec.ReleasePlan, androidApks: Map<String, String>, worker: NodeId?): ReleasePlan {
        val application = spec.host.targets.single { it.name == spec.host.applicationTarget }
        val base = "tmp/fastlane/ios"
        val artifacts = linkedMapOf("iosArchive" to "$base/${spec.scheme}.xcarchive", "iosIpaDirectory" to "$base/ipa",
            "iosIpa" to "$base/ipa/${spec.scheme}.ipa", "iosExportOptions" to "$base/ExportOptions.generated.plist",
            "iosSymbols" to "$base/ipa/archive-check.json", "iosSymbolUploadResult" to "$base/ipa/symbol-upload-result.json",
            "iosSymbolUploadReceipt" to "$base/ipa/crashlytics-symbols.json", "iosSigningRequest" to "build/dependeasy/apple/signing-request.json",
            "iosSigningConfig" to "build/dependeasy/apple/Signing.local.xcconfig", "iosFirebaseConfig" to "${spec.xcodeDirectory}/iosApp/GoogleService-Info.plist")
        val commands = linkedMapOf<String, ReleaseCommand>()
        listOf("Debug", "Release").forEach { configuration ->
            val derived = "$base/${configuration.lowercase()}"
            artifacts["ios${configuration}DerivedData"] = derived
            artifacts["ios${configuration}App"] = "$derived/Build/Products/$configuration-iphoneos/${spec.scheme}.app"
            commands["ios${configuration}"] = Xcodebuild.build(spec, configuration, derived)
            if (configuration == "Release") {
                artifacts["iosCrashlyticsUploader"] = "$derived/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols"
                commands["iosArchive"] = Xcodebuild.build(spec, configuration, derived, artifacts.getValue("iosArchive"))
            }
        }
        commands["iosExport"] = Xcodebuild.export(artifacts.getValue("iosArchive"), artifacts.getValue("iosIpaDirectory"), artifacts.getValue("iosExportOptions"))
        artifacts.putAll(androidApks)
        artifacts.putAll(spec.androidArtifacts)
        spec.androidTasks.forEach { (key, task) ->
            commands[key] = AppleGradle.android(task, spec.host, spec.versionName, spec.buildNumber, key.contains("Release"))
        }
        if (spec.webWorker.isNotBlank()) {
            commands["webDeploy"] = AppleGradle.deploy(requireNotNull(worker) { "Missing declared worker: ${spec.webWorker}" }, spec.host, spec.versionName, spec.buildNumber)
        }
        commands["iosArchiveCheck"] = AppleGradle.verify(spec.archiveCheckTask, spec.host, spec.versionName, spec.buildNumber)
        commands["iosSymbolUploadCheck"] = AppleGradle.verify(spec.symbolUploadCheckTask, spec.host, spec.versionName, spec.buildNumber)
        commands["iosSigning"] = AppleGradle.verify(spec.signingTask, spec.host, spec.versionName, spec.buildNumber)
        return ReleasePlan(product = spec.product, versionName = spec.versionName, buildNumber = spec.buildNumber,
            bundleId = application.bundleId, extensionBundleId = spec.host.targets.singleOrNull { it.name != spec.host.applicationTarget }?.bundleId,
            team = spec.host.team, artifacts = artifacts, commands = commands)
    }
}
