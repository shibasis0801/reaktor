package dev.shibasis.tester.react;

import android.os.Bundle
import com.facebook.react.ReactActivity
import dev.shibasis.flatinvoker.core.EncodingComplexCase
import dev.shibasis.reaktor.core.extensions.finishAndStart

class MainActivity: ReactActivity() {
  override fun getMainComponentName() = "ReaktorTester"
}
