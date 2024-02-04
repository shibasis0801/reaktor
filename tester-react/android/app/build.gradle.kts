import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.android.defaults
import groovy.lang.Closure

plugins {
    id("dev.shibasis.dependeasy.application")
}

project.extra.apply {
    set("react", mapOf(
        "enableHermes" to true,
    ))
}

apply(from = file("../../node_modules/react-native/react.gradle"))

android {
    defaults("dev.shibasis.flatinvoker.react.tester")
    kotlinOptions { defaults() }

    splits {
        abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include(*Version.architectures.toTypedArray())
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            ndk {
//                abiFilters.addAll(Version.architectures)
            }
        }

        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // For each APK output variant, override versionCode with a combination of
    // abiCodes * 1000 + variant.versionCode. In this example, variant.versionCode
    // is equal to defaultConfig.versionCode. If you configure product flavors that
    // define their own versionCode, variant.versionCode uses that value instead.
    androidComponents {
        onVariants { variant ->
            // For each separate APK per architecture, set a unique version code as described here:
            // https://developer.android.com/studio/build/configure-apk-splits.html
            // Example: versionCode 1 will generate 1001 for armeabi-v7a, 1002 for x86, etc.

            // Assigns a different version code for each output APK
            // other than the universal APK.
            variant.outputs.forEach { output ->
                val name = output.filters.find { it.filterType == ABI }?.identifier
                val baseAbiCode = Version.architectures.indexOf(name)
                // We want universal APKs to have the lowest version code, so for non-mapped ABIs don't do anything
                if (baseAbiCode != -1) {
                    // Assigns the new version code to output.versionCode, which changes the version code
                    // for only the output APK, not for the variant itself.
                    output.versionCode.set((baseAbiCode + 1) * 1000 + (output.versionCode.get() ?: 0))
                }
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    api("com.facebook.react:react-native:0.68.5") {
        exclude(module = "fbjni-java-only")
    }
    implementation("com.facebook.fbjni:fbjni:0.2.2")

    api(project(":flatinvoker-react"))
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.0.0")

//    flipper()

    val hermesPath= "../../node_modules/hermes-engine/android/"
    debugImplementation(files(hermesPath + "hermes-debug.aar"))
    releaseImplementation(files(hermesPath + "hermes-release.aar"))

}

apply(from = file("../../node_modules/@react-native-community/cli-platform-android/native_modules.gradle"));
val applyNativeModulesAppBuildGradle = extra["applyNativeModulesAppBuildGradle"] as Closure<*>
applyNativeModulesAppBuildGradle(project)
