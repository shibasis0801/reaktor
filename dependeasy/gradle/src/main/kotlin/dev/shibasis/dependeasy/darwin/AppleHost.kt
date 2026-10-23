package dev.shibasis.dependeasy.darwin

import com.android.build.api.artifact.SingleArtifact
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.componentRole
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.WorkspaceNodeTask
import dev.shibasis.dependeasy.workspace.declarationSite
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.*

internal data class AppleFrameworkDeclaration(val name: String, val exports: List<Any>, val linkFlags: List<String>)
internal class AppleHost(
    private val project: Project,
    private val application: ProjectDependency,
    private val spec: AppleAppSpec,
    private val android: ProjectDependency?,
    private val fleet: OutputHandle?,
) {
    private val rootLayout = project.rootProject.layout
    private val directory = project.rootProject.relativePath(project.projectDir)
    internal val archiveCheck = project.tasks.register<DarwinArchiveCheck>("darwinArchiveCheck")
    internal val symbolUploadCheck = project.tasks.register<DarwinArchiveCheck>("verifyDarwinSymbolUpload")
    internal val signing = project.tasks.register<SigningXcconfigTask>("generateSigningXcconfig") {
        request.set(rootLayout.buildDirectory.file("dependeasy/apple/signing-request.json"))
        config.set(rootLayout.buildDirectory.file("dependeasy/apple/Signing.local.xcconfig"))
        exportOptions.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ExportOptions.generated.plist"))
    }
    internal val generate = project.tasks.register<XcodeHostTask>("generateXcodeHost")
    internal val verify = project.tasks.register<VerifyXcodeHost>("verifyXcodeHost")
    internal val release = project.tasks.register<ReleasePlanTask>("generateReleasePlan")
    internal val frameworks = project.tasks.register<WorkspaceNodeTask>("embedAppleFrameworksForXcode") {
        bind(ArtifactSetKernel)
        dependsOn(project.outputFiles(project.appleXcodeFramework(application)))
    }

    internal fun install(): NodeHandle = with(spec) {
        val releaseNode = project.node(release, OutputKind.GeneratedContract)
        listOf(generate, verify, signing).forEach { project.node(it, OutputKind.GeneratedContract) }
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
            val hostDirectory = project.projectDir
            fun relative(file: java.io.File) = file.relativeTo(hostDirectory).invariantSeparatorsPath
            val targets = mutableListOf(AppleHostTarget(target, bundleId, declaration.name,
                relative(owner.layout.buildDirectory.dir("xcode-frameworks").get().asFile),
                settingsPrefix, declaration.exports.mapNotNull { adapters[it] }, backgroundTasks, declaration.linkFlags))
            extension?.let { framework ->
                val included = project.gradle.includedBuild(framework.module.build.name)
                targets.add(AppleHostTarget(extensionTarget, "$bundleId.$extensionTarget", framework.name,
                    relative(included.projectDir.resolve(framework.module.name).resolve("build/xcode-frameworks")),
                    settingsPrefix = requireNotNull(extensionSettingsPrefix), linkFlags = listOf("-lsqlite3", "-framework", "CoreLocation")))
            }
            if (Reaktor.Core in declaration.exports) { project.node(archiveCheck); project.node(symbolUploadCheck) }
            val pins = linkedMapOf("appauth-ios" to Versions.AppAuthDarwin, "gtmappauth" to Versions.GtmAppAuthDarwin)
            if (Reaktor.Core in declaration.exports) pins["firebase-ios-sdk"] = Versions.FirebaseDarwin
            val contract = BuildModelReader.json.encodeToString(AppleHostContract(deploymentTarget = Versions.SDK.targetDarwin,
                swiftVersion = Versions.SDK.Swift, team = team, applicationTarget = target, entryPoint = entryPoint, versionOption = version?.name.orEmpty(),
                numberOption = number?.name.orEmpty(), developmentToolsOption = developmentTools?.name.orEmpty(), targets = targets, swiftPackages = pins))
            signing.configure { host.set(contract) }
            val settings = rootLayout.projectDirectory.dir("config/apple-host")
            val versionValue = version?.let(project::option)?.get() ?: "1.0"
            val numberValue = number?.let(project::option)?.get() ?: 1
            val root = relative(project.rootDir)
            val include = relative(rootLayout.buildDirectory.file("dependeasy/apple/Signing.local.xcconfig").get().asFile)
            generate.configure {
                this.declaration.set(contract); rootPath.set(root); kotlinTask.set(frameworks.get().path)
                versionName.set(versionValue); buildNumber.set(numberValue); signingInclude.set(include)
                destination.set(settings); this.contract.set(rootLayout.buildDirectory.file("dependeasy/apple/host.json"))
            }
            verify.configure {
                this.declaration.set(contract); rootPath.set(root); wrapper.set("gradlew"); kotlinTask.set(frameworks.get().path)
                versionName.set(versionValue); buildNumber.set(numberValue); signingInclude.set(include); destination.set(settings)
                mustRunAfter(generate)
                resolvedPackages.set(rootLayout.projectDirectory.file("$directory/$target.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"))
            }
            listOf(archiveCheck, symbolUploadCheck).forEach { provider -> provider.configure {
                ipa.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/$target.ipa"))
                archive.set(rootLayout.projectDirectory.dir("tmp/fastlane/ios/$target.xcarchive"))
                firebaseConfig.set(rootLayout.projectDirectory.file("$directory/iosApp/GoogleService-Info.plist"))
                uploader.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/release/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols"))
                this.bundleId.set(spec.bundleId); versionName.set(versionValue); buildNumber.set(numberValue)
                scratch.set(rootLayout.projectDirectory.dir("tmp/fastlane/ios/archive-check-scratch"))
            } }
            archiveCheck.configure {
                receipt.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/archive-check.json"))
                packagedFirebaseConfig.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/GoogleService-Info.packaged.plist"))
            }
            symbolUploadCheck.configure {
                verifyUpload.set(true)
                qualification.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/archive-check.json"))
                providerResult.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/symbol-upload-result.json"))
                receipt.set(rootLayout.projectDirectory.file("tmp/fastlane/ios/ipa/crashlytics-symbols.json"))
            }
            release.configure {
                dependsOn(generate)
                product.set(project.rootProject.name); host.set(contract); versionName.set(versionValue); buildNumber.set(numberValue)
                xcodeDirectory.set(directory); xcodeProject.set("$target.xcodeproj"); scheme.set(target)
                webWorker.set(web?.name.orEmpty())
                fleet?.let {
                    workerFleetNode.set(it.nodeId)
                    workerFleet.set(project.layout.file(project.outputFiles(it).elements.map { files -> files.single().asFile }))
                }
                archiveCheckTask.set(archiveCheck.get().path)
                symbolUploadCheckTask.set(symbolUploadCheck.get().path)
                signingTask.set(signing.get().path)
                this.distribution.set(BuildModelReader.json.encodeToString(spec.distribution))
                spec.distribution?.artifacts?.get("androidFirebaseConfig")?.let {
                    androidFirebaseConfiguration.set(rootLayout.projectDirectory.file(it))
                }
                output.set(rootLayout.buildDirectory.file("dependeasy/release/${project.rootProject.name}.json"))
            }
        }
        android?.let { dependency ->
            val owner = project.project(dependency.path)
            project.gradle.projectsEvaluated {
                owner.extensions.getByType<AndroidApplicationOutputs>().variants.forEach { variant ->
                    val label = variant.name.replaceFirstChar(Char::uppercaseChar)
                    val apk = variant.artifacts.get(SingleArtifact.APK)
                    val aab = variant.artifacts.get(SingleArtifact.BUNDLE)
                    val loader = variant.artifacts.getBuiltArtifactsLoader()
                    release.configure {
                        androidArtifacts.put("android${label}ApkDirectory", apk.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)
                        androidArtifacts.put("android${label}Aab", aab.get().asFile.relativeTo(project.rootDir).invariantSeparatorsPath)
                        apkDirectories.put(label, apk.get().asFile.absolutePath)
                        apkMetadata.from(project.fileTree(apk.get().asFile) { include("*.json") })
                        artifactsLoader.set(loader)
                        androidTasks.put("android$label", owner.tasks.named("assemble$label").get().path)
                        androidTasks.put("android${label}Bundle", owner.tasks.named("bundle$label").get().path)
                    }
                    Build.entries.singleOrNull { it.name.equals(variant.buildType, true) }?.let { build ->
                        val inputs = listOf(OutputKind.AndroidApk, OutputKind.AndroidBundle).map { project.output(dependency, it, build) }
                        BuildWorkspace.get(project).graph.configure {
                            edges.addAll(inputs.map { input -> BuildModelReader.json.encodeToString<Edge>(
                                Edge.Consumes(releaseNode.id, input.nodeId, input.kind.name)) })
                        }
                    }
                }
            }
        }
        project.publishOutput(OutputKind.GeneratedContract, generate.flatMap { it.contract }, Contract.AppleHost, producer = generate)
        project.publishOutput(OutputKind.GeneratedContract, release.flatMap { it.output }, Contract.AppleRelease, producer = release)
        val workspace = BuildWorkspace.get(project)
        releaseNode.consumes(project.artifact(generate) { it.contract })
        fleet?.let { input -> workspace.graph.configure {
            edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(releaseNode.id, input.nodeId, input.kind.name)))
        } }
        val verification = workspace.aggregate(project, "checkApple", NodeKind.Verification, listOf(project.node(verify)))
        val included = listOf(project.node(generate))
        val outputs = Apple.entries.map { project.appleFramework(application, it, Build.Release) }
        val assembled = workspace.emit(project, KernelSpec.WorkspaceNode(
            NodeId(project.rootProject.name, project.path, "assembleApple"), NodeKind.ArtifactSet, Run.Default,
            included.map { it.id }, outputs.map { it.nodeId }, emptyList(), emptyList()),
            ArtifactSetKernel, project.declarationSite(), null, included, outputs, emptyList())
        project.componentRole(Workspace.Verify, verification)
        project.componentRole(Workspace.Build, assembled)
        project.componentRole(Workspace.Apple, assembled)
        project.tasks.named("check") { dependsOn(requireNotNull(verification.task)) }
        project.tasks.named("assemble") { dependsOn(requireNotNull(assembled.task)) }
        releaseNode
    }
}
