import {useEffect} from 'react';
import {AppState, DeviceEventEmitter} from 'react-native';

import {wakeWordStore} from '../store';

/** Native event emitted by WakeWordModule when the listener fires. */
export const WAKE_WORD_DETECTED_EVENT = 'WakeWordDetected';

/**
 * App-level wake word wiring: forwards native detections to the store and
 * keeps the native listener in sync whenever the app comes to the front
 * (which is also how a wake that relaunched the app gets picked up).
 * Mounted once near the root; the chat screen turns a stored detection
 * into a voice-mode session.
 */
export function useWakeWordListener(): void {
  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener(
      WAKE_WORD_DETECTED_EVENT,
      () => wakeWordStore.handleDetected(),
    );

    wakeWordStore.syncNative();
    const appState = AppState.addEventListener('change', state => {
      if (state === 'active') {
        wakeWordStore.syncNative();
      }
    });

    return () => {
      subscription.remove();
      appState.remove();
    };
  }, []);
}
