import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common { dependencies { commonCoroutines() } }
    droid {}
    darwin {}
    web {}
    server {}
}

android {
    defaults("dev.shibasis.reaktor.surface")
}
