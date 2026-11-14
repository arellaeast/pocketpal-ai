import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  /**
   * Start (or restart) the always-on wake word foreground service with the
   * given phrase and score threshold. The caller must hold the microphone
   * permission; without it the service stops itself.
   */
  startListening(phraseKey: string, threshold: number): void;
  stopListening(): void;
  isListening(): Promise<boolean>;
  /**
   * True (once) if the service detected the wake word while the app was
   * not running and relaunched it. Clears the pending flag.
   */
  consumePendingWake(): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('WakeWordModule');
