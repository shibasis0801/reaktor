import {trim} from "../helpers.mjs";

// also import the kotlin/js output into this and re-export it
export const packageJson = (moduleName) => trim`
{
  "name": "@mehmaan/${moduleName}",
  "version": "1.0.0",
  "main": "reactMain/index.tsx",
  "scripts": {}
}
`