package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.framework.Adapter

abstract class NotificationAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {

}

