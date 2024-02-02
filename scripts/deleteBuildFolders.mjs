import "zx/globals"
// await $`rm -rf build`

const folders = ["reaktor", "modules", "experiments", "dependeasy", "platforms"]

const kts = await glob('**/*/build.gradle.kts')
const directories = kts

    .map(it => it.replace(/\.gradle\.kts$/, ''))
kts.forEach(console.log)

