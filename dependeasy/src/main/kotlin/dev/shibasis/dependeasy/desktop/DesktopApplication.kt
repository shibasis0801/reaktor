package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.JvmApplication
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

internal fun Project.desktopApplication(main: String, configuration: JvmApplication.() -> Unit) {
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    val sharing = providers.gradleProperty("reaktor.desktop.cds")
    val application = extensions.getByType<ComposeExtension>().extensions.getByType<DesktopExtension>().application
    application.apply {
        mainClass = main
        javaHome = extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.application.configure.swing.globals=false")
        if (mac) jvmArgs("-Dsun.java2d.metal=true", "-Dsun.java2d.opengl=false",
            "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        configuration()
        if (mac) jvmArgs("-Xdock:name=${nativeDistributions.packageName}")
        if (sharing.orNull.toBoolean()) {
            val args = sharedClassArchive(jvmArgs, "${System.getProperty("user.home")}/.reaktor/cds", project.name)
            jvmArgs.clear()
            jvmArgs.addAll(args)
        }
    }
    tasks.withType<Jar>().configureEach {
        if (name.contains("UberJar")) {
            isZip64 = true
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
        }
    }
    tasks.withType<AbstractJLinkTask>().configureEach {
        retainJavaLauncher(javaHome, destinationDir, sharing.orElse("true").map(String::toBoolean))
    }
    // Compose assigns launcher arguments in its own afterEvaluate callback.
    afterEvaluate {
        tasks.withType<AbstractJPackageTask>().configureEach {
            if (sharing.orElse("true").get().toBoolean()) {
                val base = launcherJvmArgs.get()
                val home = if (System.getProperty("os.name").startsWith("Windows")) "\$USERPROFILE" else "\$HOME"
                launcherJvmArgs.set(files.elements.map { entries ->
                    sharedClassArchive(base, "$home/.reaktor/cds", packageName.get(), entries.map { it.asFile })
                })
            }
        }
    }
}
