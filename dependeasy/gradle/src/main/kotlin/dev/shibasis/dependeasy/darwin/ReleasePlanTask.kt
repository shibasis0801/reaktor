package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@CacheableTask
abstract class ReleasePlanTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ReleasePlan(product.get(), versionName.get(), buildNumber.get(), output.get().asFile.absolutePath)
    @get:Input abstract val product: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val host: Property<String>
    @get:Input abstract val xcodeDirectory: Property<String>
    @get:Input abstract val xcodeProject: Property<String>
    @get:Input abstract val scheme: Property<String>
    @get:Input abstract val androidArtifacts: MapProperty<String, String>
    @get:Input abstract val androidTasks: MapProperty<String, String>
    @get:Input abstract val apkDirectories: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val apkMetadata: ConfigurableFileCollection
    @get:Internal abstract val artifactsLoader: Property<com.android.build.api.variant.BuiltArtifactsLoader>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val webWorker: Property<String>
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val workerFleet: RegularFileProperty
    @get:Input abstract val archiveCheckTask: Property<String>
    @get:Input abstract val symbolUploadCheckTask: Property<String>
    @get:Input abstract val signingTask: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty
    init { androidArtifacts.convention(emptyMap()); androidTasks.convention(emptyMap()); apkDirectories.convention(emptyMap()); rootDirectory.set(project.rootProject.layout.projectDirectory) }
    @TaskAction fun write() {
        val apple = BuildModelReader.json.decodeFromString<AppleHostContract>(host.get())
        val base = "tmp/fastlane/ios"
        val versionArgs = listOf(apple.versionOption to versionName.get(), apple.numberOption to buildNumber.get().toString()).filter { it.first.isNotBlank() }.map { "-P${it.first}=${it.second}" }
        val artifacts = linkedMapOf("iosArchive" to "$base/${scheme.get()}.xcarchive", "iosIpaDirectory" to "$base/ipa",
            "iosIpa" to "$base/ipa/${scheme.get()}.ipa", "iosExportOptions" to "$base/ExportOptions.generated.plist",
            "iosSymbols" to "$base/ipa/archive-check.json", "iosSymbolUploadResult" to "$base/ipa/symbol-upload-result.json",
            "iosSymbolUploadReceipt" to "$base/ipa/crashlytics-symbols.json", "iosSigningRequest" to "build/dependeasy/apple/signing-request.json",
            "iosSigningConfig" to "build/dependeasy/apple/Signing.local.xcconfig", "iosFirebaseConfig" to "${xcodeDirectory.get()}/iosApp/GoogleService-Info.plist")
        val commands = linkedMapOf<String, ReleaseCommand>()
        listOf("Debug", "Release").forEach { configuration ->
            val derived = "$base/${configuration.lowercase()}"
            artifacts["ios${configuration}DerivedData"] = derived
            artifacts["ios${configuration}App"] = "$derived/Build/Products/$configuration-iphoneos/${scheme.get()}.app"
            val args = versionArgs + if (configuration == "Release" && apple.developmentToolsOption.isNotBlank()) listOf("-P${apple.developmentToolsOption}=false") else emptyList()
            val tokens = listOf("xcodebuild", "-jobs", "3", "-project", "${xcodeDirectory.get()}/${xcodeProject.get()}", "-scheme", scheme.get(),
                "-configuration", configuration, "-destination", "generic/platform=iOS", "-derivedDataPath", derived,
                "MARKETING_VERSION=${versionName.get()}", "CURRENT_PROJECT_VERSION=${buildNumber.get()}") +
                apple.cacheSettings().map { (key, value) -> "$key=$value" }
            val environment = mapOf(
                "BESTBUDS_GRADLE_ARGS" to args.joinToString(" "),
                "LLVM_CAS_MAX_MAPPING_SIZE" to "134217728",
            )
            val invocation = tokens + "BESTBUDS_GRADLE_ARGS=${args.joinToString(" ")}"
            commands["ios${configuration}"] = ReleaseCommand(".", invocation + "build", environment)
            if (configuration == "Release") {
                artifacts["iosCrashlyticsUploader"] = "$derived/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols"
                commands["iosArchive"] = ReleaseCommand(".", invocation + listOf("-archivePath", artifacts.getValue("iosArchive"), "archive"), environment)
            }
        }
        commands["iosExport"] = ReleaseCommand(".", listOf("xcodebuild", "-exportArchive", "-archivePath", artifacts.getValue("iosArchive"),
            "-exportPath", artifacts.getValue("iosIpaDirectory"), "-exportOptionsPlist", artifacts.getValue("iosExportOptions")))
        apkDirectories.get().forEach { (label, path) ->
            val directory = rootDirectory.dir(path)
            artifactsLoader.orNull?.load(directory.get())?.elements?.singleOrNull()?.let {
                artifacts["android${label}Apk"] = java.io.File(it.outputFile).relativeTo(rootDirectory.get().asFile).invariantSeparatorsPath
            }
        }
        androidArtifacts.get().forEach { (key, path) -> artifacts[key] = path }
        androidTasks.get().forEach { (key, path) ->
            commands[key] = ReleaseCommand(".", listOf("./gradlew", path) + versionArgs +
                if (key.contains("Release")) listOf("-Pdependeasy.publishCrashlytics=true", "-P${apple.developmentToolsOption}=false", "--console=plain") else listOf("--console=plain"))
        }
        if (webWorker.get().isNotBlank()) {
            val fleet = BuildModelReader.json.decodeFromString<WorkerFleetContract>(workerFleet.get().asFile.readText())
            val worker = fleet.workers.single { it.name == webWorker.get() }
            val task = worker.node.project.trimEnd(':') + ":" + worker.node.name
            commands["webDeploy"] = ReleaseCommand(".", listOf("./gradlew", task, "-P${apple.developmentToolsOption}=false") + versionArgs + "--console=plain")
        }
        commands["iosArchiveCheck"] = ReleaseCommand(".", listOf("./gradlew", archiveCheckTask.get(), "--console=plain") + versionArgs)
        commands["iosSymbolUploadCheck"] = ReleaseCommand(".", listOf("./gradlew", symbolUploadCheckTask.get(), "--console=plain") + versionArgs)
        commands["iosSigning"] = ReleaseCommand(".", listOf("./gradlew", signingTask.get(), "--console=plain") + versionArgs)
        val plan = ReleasePlan(product = product.get(), versionName = versionName.get(), buildNumber = buildNumber.get(),
            bundleId = apple.targets.first().bundleId, extensionBundleId = apple.targets.drop(1).firstOrNull()?.bundleId,
            team = apple.team, artifacts = artifacts, commands = commands)
        output.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(plan) + "\n") }
    }
}
