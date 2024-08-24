package dev.shibasis.reaktor.navigation.screen

import kotlin.js.JsExport


/*
Copying the React pattern of having all parameters inside a single object.
This helps with navigation stack as well as general code organization.
It may contain other variables as needed in the future.

If it is global, use Ambients
If it does not make sense in an ambient, but it is needed globally then put it here.
Take care to not put too much here.
 */

@JsExport
open class Props()

