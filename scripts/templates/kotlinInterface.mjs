import {capitalize, trim} from "../helpers.mjs";

export const kotlinInterfaceTemplate = (moduleName, sourceSet, fileName) => {
    const fileNameLower = fileName.toLowerCase();
    const fileNameCap = capitalize(fileName);

    return trim`
package app.mehmaan.${moduleName.toLowerCase()}.${fileNameLower}

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.Feature

abstract class ${fileNameCap}<Controller>(controller: Controller): Adapter<Controller>(controller) {

}

private val ${fileNameLower}Id = Feature.createId()
var Feature.${fileNameCap}: ${fileNameCap}<*>?
    get() = fetchDependency(${fileNameLower}Id)
    set(${fileNameLower}) = storeDependency(${fileNameLower}Id, ${fileNameLower})
    `
}