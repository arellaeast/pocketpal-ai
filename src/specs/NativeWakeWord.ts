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
  /** Start a teach-a-name session (see WakeWordTeacher). */
  beginTeaching(): void;
  /**
   * Analyze one recorded take (base64 little-endian 16 kHz PCM). Resolves
   * a JSON string: {accepted, reason?, count}. Rejected reasons are
   * 'too_quiet' | 'too_short' | 'too_long' | 'outlier'.
   */
  addTeachingTake(pcmBase64: string): Promise<string>;
  /** Persist the taught phrase under the given name; false if too few takes. */
  finishTeaching(name: string): Promise<boolean>;
  cancelTeaching(): void;
  /** JSON string: {exists, name} for the taught phrase, if any. */
  customWakeWordInfo(): Promise<string>;
  deleteCustomWakeWord(): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('WakeWordModule');
