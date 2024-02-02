import {trim} from "../helpers.mjs";
import { sourceSets } from "../treeBuilder.mjs";

export const buildGradle = (moduleName, moduleType) => {
    const modules = sourceSets(moduleType)
    return buildGradleTemplate(moduleName, modules)
}

const buildGradleTemplate = (moduleName, modules) => trim`
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.plugin")
}

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            api(project(":reaktor-core"))
        }
    }
    ${modules.includes("webMain") ? `
    web(commonMain) {
        dependencies = {

        }
    }` : ""}
    ${modules.includes("droidMain") ? `
    droid(commonMain) {
        dependencies = {

        }
    }` : ""}
    ${modules.includes("darwinMain") ? `
    darwin(commonMain) {
        dependencies = {

        }
    }` : ""}
    ${modules.includes("serverMain") ? `
    server(commonMain) {
        dependencies = {

        }
    }` : ""}
    ${modules.includes("workerMain") ? `
    worker(commonMain) {
        dependencies = {

        }
    }` : ""}
}

android {
    defaults("app.mehmaan.${moduleName}")
}
`