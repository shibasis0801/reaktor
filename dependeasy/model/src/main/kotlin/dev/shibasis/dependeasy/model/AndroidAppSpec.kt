package dev.shibasis.dependeasy.model

data class AndroidSigning(val keystore: BuildOption<String>, val alias: String, val password: BuildOption<String>) {
    init { require(alias.isNotBlank()) { "Android signing needs its key alias" } }
}

data class AndroidBuild(val signing: AndroidSigning? = null, val applicationIdSuffix: String? = null, val rules: List<String> = emptyList()) {
    init { require(rules.all(String::isNotBlank)) { "Android shrinking needs its authored rule paths" } }
}

data class AndroidAppSpec(val applicationId: String, val versionCode: Int, val versionName: String,
    val debug: AndroidBuild, val release: AndroidBuild) {
    init {
        require(applicationId.isNotBlank()) { "An Android application needs its id" }
        require(versionCode > 0 && versionName.isNotBlank()) { "An Android application needs its version" }
    }
}
