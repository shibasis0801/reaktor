package dev.shibasis.reaktor.media.speech

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

abstract class SpeechSynthesizer<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {

}

var Feature.SpeechSynthesizer by CreateSlot<SpeechRecognizer<*>>()