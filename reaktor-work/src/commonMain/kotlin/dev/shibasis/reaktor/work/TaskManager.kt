package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.core.framework.Adapter

abstract class TaskManager<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {

}