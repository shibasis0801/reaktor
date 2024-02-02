import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import kotlin.collections.mutableListOf

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("native.cocoapods")
    id("app.cash.sqldelight")
    id("maven-publish")
    id("dev.shibasis.dependeasy.library")
    kotlin("plugin.serialization")
}

group = "com.myntra.appscore"
version = "1.0-SNAPSHOT"

class DarwinConfigure(
    var devBuild: Boolean = true,
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var podDependencies: CocoapodsExtension.() -> Unit = {},
    var cinterops: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {},
    var targets: KotlinNativeTarget.() -> Unit = {}
)

fun KotlinMultiplatformExtension.darwin(
    commonMain: KotlinSourceSet,
    configuration: DarwinConfigure.() -> Unit = {}
) {
    val configure = DarwinConfigure().apply(configuration)

    val targets = mutableListOf<KotlinNativeTarget>(
        iosSimulatorArm64()
    )

    if (!configure.devBuild)
        targets.apply {
            add(iosArm64())
            add(iosX64())
        }

    targets.forEach {
        configure.targets(it)
        it.compilations.getByName("main").cinterops {
            configure.cinterops(this)
        }
    }

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = Version.SDK.targetDarwin
        framework {
            embedBitcode(org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode.DISABLE)
            baseName = project.name
        }

        configure.podDependencies(this)
    }

    sourceSets {
        if (configure.devBuild) {
            val iosSimulatorArm64Main by getting
            val iosMain by creating {
                dependsOn(commonMain)
                iosSimulatorArm64Main.dependsOn(this)

                dependencies {
                    configure.dependencies(this)
                }
            }
        }
        else {
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting

            val iosMain by creating {
                dependsOn(commonMain)
                iosX64Main.dependsOn(this)
                iosArm64Main.dependsOn(this)
                iosSimulatorArm64Main.dependsOn(this)

                dependencies {
                    configure.dependencies(this)
                }
            }
        }
    }
}


class AndroidConfiguration(
    var targets: org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget.() -> Unit = {},
    var dependencies: KotlinDependencyHandler.() -> Unit = {}
)
fun KotlinMultiplatformExtension.droid(
    commonMain: KotlinSourceSet,
    configuration: AndroidConfiguration.() -> Unit = {}
) {
    val configure = AndroidConfiguration().apply(configuration)

    android {
        publishLibraryVariants("release", "debug")
        compilations.all {
            kotlinOptions.jvmTarget = Version.SDK.Java.asString
        }
        configure.targets(this)
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                configure.dependencies(this)
            }
        }
    }
}

class CommonConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var testDependencies: KotlinDependencyHandler.() -> Unit = {}
)

fun KotlinMultiplatformExtension.common(
    configuration: CommonConfiguration.() -> Unit = {}
): Pair<KotlinSourceSet, KotlinSourceSet> {
    val configure = CommonConfiguration().apply(configuration)

    lateinit var _commonMain: KotlinSourceSet
    lateinit var _commonTest: KotlinSourceSet

    sourceSets {
        val commonMain by getting {
            dependencies {
                configure.dependencies(this)
            }
        }
        _commonMain = commonMain
        val commonTest by getting
        _commonTest = commonTest
    }

    return Pair(_commonMain, _commonTest)
}


kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            commonSerialization(Version.Serialization)
            commonCoroutines()
            implementation("app.cash.sqldelight:runtime:${Version.SQLDelight}")
//            implementation("co.touchlab:kermit-crashlytics:${Version.Kermit}")
            implementation("io.ktor:ktor-client-core:${Version.Ktor}")
//            implementation("co.touchlab:kermit:${Version.Kermit}")
        }
    }

    droid(commonMain) {
        dependencies = {
            implementation("app.cash.sqldelight:android-driver:${Version.SQLDelight}")
            api("com.facebook.react:react-native:0.68.5") {
                exclude(module = "fbjni-java-only")
            }
            implementation("com.facebook.fbjni:fbjni:0.2.2")
            implementation("io.ktor:ktor-client-okhttp:${Version.Ktor}")
            androidCoroutines()
        }
    }


//    val xcFramework = XCFramework()
    darwin(commonMain) {
        devBuild = false

        dependencies = {
            implementation("app.cash.sqldelight:native-driver:${Version.SQLDelight}")
            implementation("io.ktor:ktor-client-darwin:${Version.Ktor}")
        }

        podDependencies = {

        }

        targets = {
        }

        cinterops = {}
    }
}
//
//sqldelight {
//    databases {
//        create("LayoutEngineDatabase") {
//            packageName.set("com.myntra.appscore.batcave")
//        }
//    }
//}

android {
    defaults("com.myntra.appscore.batcave", file("cpp/CMakeLists.txt"))
}

tasks.register<Exec>("customTaskForSomething") {
    group = "batcave"
}









