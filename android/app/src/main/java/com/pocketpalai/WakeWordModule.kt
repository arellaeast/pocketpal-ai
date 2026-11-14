package com.pocketpal

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.pocketpal.specs.NativeWakeWordSpec

/**
 * JS bridge for [WakeWordService]. Starting and stopping the listener goes
 * through here; detections come back as a `WakeWordDetected` device event
 * while the bridge is alive, and as a persisted pending wake (see
 * [consumePendingWake]) when the service had to relaunch the app.
 */
@ReactModule(name = NativeWakeWordSpec.NAME)
class WakeWordModule(reactContext: ReactApplicationContext) :
    NativeWakeWordSpec(reactContext) {

  init {
    WakeWordService.detectionListener = {
      try {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(EVENT_NAME, null)
      } catch (t: Throwable) {
        // Bridge not ready; the pending-wake flag still carries the event.
      }
    }
  }

  override fun invalidate() {
    WakeWordService.detectionListener = null
    super.invalidate()
  }

  override fun startListening(phraseKey: String, threshold: Double) {
    WakeWordService.start(reactApplicationContext, phraseKey, threshold)
  }

  override fun stopListening() {
    WakeWordService.stop(reactApplicationContext)
  }

  override fun isListening(promise: Promise) {
    promise.resolve(WakeWordService.isRunning)
  }

  override fun consumePendingWake(promise: Promise) {
    promise.resolve(WakeWordService.consumePendingWake(reactApplicationContext))
  }

  companion object {
    const val EVENT_NAME = "WakeWordDetected"
  }
}
