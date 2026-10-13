package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object NativeLibraries {
    private const val FbjniVersion = "0.8.1"
    const val Fbjni = "com.facebook.fbjni:fbjni:$FbjniVersion"
    const val FbjniJava = "com.facebook.fbjni:fbjni-java-only:$FbjniVersion"
    const val Flatbuffers = "com.google.flatbuffers:flatbuffers-java:2.0.3"
    const val Hermes = "com.facebook.react:hermes-android:${ToolchainVersions.HermesAndroid}"
    const val Jna = "net.java.dev.jna:jna:5.18.1"
    const val OpenSsl = "com.android.ndk.thirdparty:openssl:1.1.1q-beta-1"
    const val ReactNative = "com.facebook.react:react-native:0.68.5"
}
