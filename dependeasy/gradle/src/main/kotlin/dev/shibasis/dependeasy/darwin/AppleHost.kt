package dev.shibasis.dependeasy.darwin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.WorkspaceTargetTask
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.*

internal data class AppleFrameworkDeclaration(val name: String, val exports: List<Any>, val linkFlags: List<String>)
data class AppleFrameworkSpec(val module: ModuleHandle, val name: String)

class AppleHost internal constructor(private val project: Project, private val directory: String,
                                     private val application: ProjectDependency) {
    var entryPoint: String = ""
    var bundleId: String = ""
    var team: String = ""
    var backgroundTasks: List<String> = emptyList()
    var extension: AppleFrameworkSpec? = null
    var extensionTarget: String = "NotificationService"
    var target: String = "iosApp"
    var version: BuildOption<String>? = null
    var number: BuildOption<Int>? = null
    var developmentTools: BuildOption<Boolean>? = null
    var android: ProjectDependency? = null
    var web: dev.shibasis.dependeasy.handles.NodeHandle? = null
    val archiveCheck = project.tasks.register<DarwinArchiveCheck>("darwinArchiveCheck")
    val symbolUploadCheck = project.tasks.register<DarwinArchiveCheck>("verifyDarwinSymbolUpload")
    val signing = project.tasks.register<SigningXcconfigTask>("generateSigningXcconfig") {
        request.set(project.layout.buildDirectory.file("dependeasy/apple/signing-request.json"))
        config.set(project.layout.buildDirectory.file("dependeasy/apple/Signing.local.xcconfig"))
        exportOptions.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ExportOptions.generated.plist"))
    }
    val generate = project.tasks.register<XcodeHostTask>("generateXcodeHost")
    val verify = project.tasks.register<VerifyXcodeHost>("verifyXcodeHost")
    val release = project.tasks.register<ReleasePlanTask>("generateReleasePlan")
    val frameworks = project.tasks.register<WorkspaceTargetTask>("embedAppleFrameworksForXcode") {
        dependsOn(project.outputFiles(project.appleXcodeFramework(application)))
    }

    fun applicationFile(build: Build): org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> =
        project.layout.file(release.flatMap { it.output }.map { file ->
            val plan = BuildModelReader.json.decodeFromString<ReleasePlan>(file.asFile.readText())
            project.file(plan.artifacts.getValue("ios${build.name}App"))
        })

    internal fun install() {
        listOf(generate, verify, release, signing).forEach { project.node(it, OutputKind.GeneratedContract) }
        project.node(frameworks, OutputKind.AppleXcodeFramework)
        extension?.let { framework -> frameworks.configure { dependsOn(project.outputFiles(appleXcodeFramework(framework.module))) } }
        project.gradle.projectsEvaluated {
            val owner = project.project(application.path)
            val declaration = owner.extensions.getByType<AppleFrameworkDeclaration>()
            val adapters = mapOf(
                Reaktor.Core to "AppleFirebaseRuntime.shared.install(implementation: FirebaseSdk())",
                Reaktor.Auth to "AppleGoogleSignInRuntime.shared.install(implementation: GoogleSignInSdk())",
                Reaktor.NotificationFcm to "AppleRemoteMessagingRuntime.shared.install(implementation: FirebaseMessagingSdk())",
                Reaktor.Telemetry to "AppleCrashReporterRuntime.shared.install(implementation: CrashlyticsSdk())")
            val hostDirectory = project.file(directory)
            fun relative(file: java.io.File) = file.relativeTo(hostDirectory).invariantSeparatorsPath
            val targets = mutableListOf(AppleHostTarget(target, bundleId, declaration.name,
                relative(owner.layout.buildDirectory.dir("xcode-frameworks").get().asFile),
                declaration.exports.mapNotNull { adapters[it] }, backgroundTasks, declaration.linkFlags))
            extension?.let { framework ->
                val included = project.gradle.includedBuild(framework.module.build.name)
                targets.add(AppleHostTarget(extensionTarget, "$bundleId.$extensionTarget", framework.name,
                    relative(included.projectDir.resolve(framework.module.name).resolve("build/xcode-frameworks")),
                    linkFlags = listOf("-lsqlite3", "-framework", "CoreLocation")))
            }
            if (Reaktor.Core in declaration.exports) { project.node(archiveCheck); project.node(symbolUploadCheck) }
            val pins = linkedMapOf("appauth-ios" to Versions.AppAuthDarwin, "gtmappauth" to Versions.GtmAppAuthDarwin)
            if (Reaktor.Core in declaration.exports) pins["firebase-ios-sdk"] = Versions.FirebaseDarwin
            val contract = BuildModelReader.json.encodeToString(AppleHostContract(deploymentTarget = Versions.SDK.targetDarwin,
                swiftVersion = Versions.SDK.Swift, team = team, entryPoint = entryPoint, versionOption = version?.name.orEmpty(),
                numberOption = number?.name.orEmpty(), developmentToolsOption = developmentTools?.name.orEmpty(), targets = targets, swiftPackages = pins))
            val settings = project.layout.projectDirectory.dir("config/apple-host")
            val versionValue = version?.let(project::option)?.get() ?: "1.0"
            val numberValue = number?.let(project::option)?.get() ?: 1
            val root = relative(project.rootDir)
            val include = relative(project.layout.buildDirectory.file("dependeasy/apple/Signing.local.xcconfig").get().asFile)
            generate.configure {
                this.declaration.set(contract); rootPath.set(root); kotlinTask.set(frameworks.name)
                versionName.set(versionValue); buildNumber.set(numberValue); signingInclude.set(include)
                destination.set(settings); this.contract.set(project.layout.buildDirectory.file("dependeasy/apple/host.json"))
            }
            verify.configure {
                this.declaration.set(contract); rootPath.set(root); wrapper.set("gradlew"); kotlinTask.set(frameworks.name)
                versionName.set(versionValue); buildNumber.set(numberValue); signingInclude.set(include); destination.set(settings)
                mustRunAfter(generate)
                resolvedPackages.set(project.layout.projectDirectory.file("$directory/$target.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"))
            }
            listOf(archiveCheck, symbolUploadCheck).forEach { provider -> provider.configure {
                ipa.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/$target.ipa"))
                archive.set(project.layout.projectDirectory.dir("tmp/fastlane/ios/$target.xcarchive"))
                firebaseConfig.set(project.layout.projectDirectory.file("$directory/iosApp/GoogleService-Info.plist"))
                uploader.set(project.layout.projectDirectory.file("tmp/fastlane/ios/release/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols"))
                this.bundleId.set(this@AppleHost.bundleId); versionName.set(versionValue); buildNumber.set(numberValue)
                scratch.set(project.layout.projectDirectory.dir("tmp/fastlane/ios/archive-check-scratch"))
            } }
            archiveCheck.configure {
                receipt.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/archive-check.json"))
                packagedFirebaseConfig.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/GoogleService-Info.packaged.plist"))
            }
            symbolUploadCheck.configure {
                verifyUpload.set(true)
                qualification.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/archive-check.json"))
                providerResult.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/symbol-upload-result.json"))
                receipt.set(project.layout.projectDirectory.file("tmp/fastlane/ios/ipa/crashlytics-symbols.json"))
            }
            release.configure {
                dependsOn(generate)
                product.set(project.name); host.set(contract); versionName.set(versionValue); buildNumber.set(numberValue)
                xcodeDirectory.set(directory); xcodeProject.set("$target.xcodeproj"); scheme.set(target)
                webTask.set(web?.task?.get()?.path.orEmpty())
                output.set(project.layout.buildDirectory.file("dependeasy/release/${project.name}.json"))
            }
        }
        android?.let { dependency ->
            val owner = project.project(dependency.path)
            owner.pluginManager.withPlugin("com.android.application") {
                owner.extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                    val label = variant.name.replaceFirstChar(Char::uppercaseChar)
                    val apk = variant.artifacts.get(SingleArtifact.APK)
                    val aab = variant.artifacts.get(SingleArtifact.BUNDLE)
                    val loader = variant.artifacts.getBuiltArtifactsLoader()
                    project.gradle.projectsEvaluated { release.configure {
                        androidArtifacts.put("android${label}ApkDirectory", apk.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)
                        androidArtifacts.put("android${label}Aab", aab.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)
                        apkDirectories.put(label, apk.get().asFile.absolutePath)
                        apkMetadata.from(project.fileTree(apk.get().asFile) { include("*.json") })
                        artifactsLoader.set(loader)
                        androidTasks.put("android$label", "${owner.path}:assemble$label")
                        androidTasks.put("android${label}Bundle", "${owner.path}:bundle$label")
                    } }
                }
            }
        }
    }
}
