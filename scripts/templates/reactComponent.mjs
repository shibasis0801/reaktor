import {capitalize} from "../helpers.mjs";

export const jsxTemplate = (moduleName) => `
import React from "react"

export type ${capitalize(moduleName)}Props = {
}

export const ${capitalize(moduleName)} = (props: ${capitalize(moduleName)}Props) => {
    return (
        <div>
            Hello from ${moduleName}
        </div>
    )
}
`

