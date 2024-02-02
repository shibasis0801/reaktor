import {capitalize, trim} from "../helpers.mjs";
import {sourceSetPrefix} from "../treeBuilder.mjs";

export function kotlinImplementationTemplate(moduleName, sourceSet, fileName) {
    if (sourceSet === "droidMain") {
        return androidImplementationTemplate(moduleName, sourceSet, fileName)
    }
    if (sourceSet === "darwinMain") {
        return darwinImplementationTemplate(moduleName, sourceSet, fileName)
    }
    if (sourceSet === "webMain") {
        return webImplementationTemplate(moduleName, sourceSet, fileName)
    }
    if (sourceSet == "serverMain") {
        return serverImplementationTemplate(moduleName, sourceSet, fileName)
    }
}

const androidImplementationTemplate = (moduleName, sourceSet, fileName) => trim`
package app.mehmaan.${moduleName.toLowerCase()}.${fileName.toLowerCase()}

import androidx.activity.ComponentActivity

class ${sourceSetPrefix[sourceSet]}${capitalize(fileName)}(activity: ComponentActivity): ${capitalize(fileName)}<ComponentActivity>(activity) {
    
}
`

const darwinImplementationTemplate = (moduleName, sourceSet, fileName) => trim`
package app.mehmaan.${moduleName.toLowerCase()}.${fileName.toLowerCase()}

import platform.UIKit.UIViewController

class ${sourceSetPrefix[sourceSet]}${capitalize(fileName)}(uiViewController: UIViewController): ${capitalize(fileName)}<UIViewController>(uiViewController) {
    
}
`

const webImplementationTemplate = (moduleName, sourceSet, fileName) => trim`
package app.mehmaan.${moduleName.toLowerCase()}.${fileName.toLowerCase()}

import kotlinx.browser.window

class ${sourceSetPrefix[sourceSet]}${capitalize(fileName)}: ${capitalize(fileName)}<Unit>(Unit) {

}
`


const serverImplementationTemplate = (moduleName, sourceSet, fileName) => trim`
package app.mehmaan.${moduleName.toLowerCase()}.${fileName.toLowerCase()}

import io.vertx.core.Vertx

class ${sourceSetPrefix[sourceSet]}${capitalize(fileName)}(vertx: Vertx): ${capitalize(fileName)}<Vertx>(vertx) {

}
`




