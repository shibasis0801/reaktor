/*
Usage

yarn newModule <moduleName> <filename> <moduleType> <target>
creates a new multiplatform module in the modules directory

filename is the starting interface name for scaffolding
moduleType can be -> full, client, server, native
target can be -> modules, reaktor

Todo
1. Replace interface with AbstractAdapter pattern
2. Make this interactive with prompts so that one does not have to remember the arguments
3. Make the code more obvious
4. Add suffixes, .droid.kt, .darwin.kt, .web.kt, etc
5. Generate expect/actuals
6. Add testing support

*/


import "zx/globals"
import "fs/promises"
import {capitalize, once, trim, writeOnce} from "./helpers.mjs";
import {buildGradle} from "./templates/buildGradle.mjs";
import {packageJson} from "./templates/packageJson.mjs";
import {kotlinImplementationTemplate} from "./templates/kotlinImplementation.mjs";
import {kotlinInterfaceTemplate} from "./templates/kotlinInterface.mjs";
import {jsxTemplate} from "./templates/reactComponent.mjs";
import {sourceSets, sourceSetPrefix} from "./treeBuilder.mjs";
import {README} from "./templates/README.mjs";


const moduleName = process.argv[3]
const fileName = process.argv[4]
const moduleType = process.argv[5] || "full"
const moduleTarget = process.argv[6] || "reaktor"

// add enum verification for args

await cd(moduleTarget)
await $`pwd`
await once(`lib-${moduleName}`,() => $`mkdir lib-${moduleName}`)
await cd(`lib-${moduleName}`)
await writeOnce("build.gradle.kts", buildGradle(moduleName, moduleType))
await writeOnce("README.md", README(moduleName))
// await writeOnce("package.json",packageJson(moduleName))

sourceSets(moduleType).forEach(async sourceSet => {
    await once(sourceSet, () => $`mkdir ${sourceSet}`)
    if (sourceSet === "reactMain") {
        const file = `${sourceSet}/index.tsx`
        await once(file, () => fs.writeFile(file, jsxTemplate(moduleName)))
    }
    else {
        const path = `${sourceSet}/${fileName || moduleName}`.toLowerCase()
        await once(path, () => $`mkdir ${path}`)
        const name = fileName || moduleName

        if (sourceSet === "commonMain") {
            const filePath = `${path}/${name}.kt`
            await once(filePath, () => fs.writeFile(filePath, kotlinInterfaceTemplate(moduleName, sourceSet, fileName)))
        }
        else {
            const template = kotlinImplementationTemplate(moduleName, sourceSet, fileName)
            const filePath = `${path}/${sourceSetPrefix[sourceSet]}${name}.kt`
            if (template)
                await once(filePath, () => fs.writeFile(filePath, template))
        }
    }
})

