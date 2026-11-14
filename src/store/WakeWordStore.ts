import {makeAutoObservable, runInAction} from 'mobx';
import {makePersistable} from 'mobx-persist-store';
import AsyncStorage from '@react-native-async-storage/async-storage';

import NativeWakeWord from '../specs/NativeWakeWord';

export type WakeWordPhraseKey = 'hey_jarvis' | 'alexa' | 'hey_mycroft';

export const WAKE_WORD_PHRASES: {key: WakeWordPhraseKey; label: string}[] = [
  {key: 'hey_jarvis', label: 'Hey Jarvis'},
  {key: 'alexa', label: 'Alexa'},
  {key: 'hey_mycroft', label: 'Hey Mycroft'},
];

export const DEFAULT_WAKE_THRESHOLD = 0.5;

/**
 * Settings and runtime state for the hands-free wake word (fork feature).
 *
 * The actual listening happens in a native foreground service
 * (WakeWordService) so it works with the app closed; this store owns the
 * user's choices, mirrors whether the service is running, and carries a
 * detection from the native side to the chat screen, which turns it into
 * a voice-mode session.
 */
class WakeWordStore {
  enabled = false;
  phraseKey: WakeWordPhraseKey = 'hey_jarvis';
  threshold = DEFAULT_WAKE_THRESHOLD;

  /** The native service is running (runtime only, never persisted). */
  listening = false;
  /** A voice conversation is using the mic; the listener stays down. */
  suspended = false;
  /** A detection is waiting for the chat screen to open voice mode. */
  pendingVoiceStart = false;

  constructor() {
    makeAutoObservable(this, {}, {autoBind: true});
    makePersistable(this, {
      name: 'WakeWordStore',
      properties: ['enabled', 'phraseKey', 'threshold'],
      storage: AsyncStorage,
    });
  }

  get phraseLabel(): string {
    return (
      WAKE_WORD_PHRASES.find(phrase => phrase.key === this.phraseKey)?.label ??
      WAKE_WORD_PHRASES[0].label
    );
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    if (enabled) {
      this.startNative();
    } else {
      this.stopNative();
    }
  }

  setPhraseKey(phraseKey: WakeWordPhraseKey): void {
    this.phraseKey = phraseKey;
    if (this.enabled && !this.suspended) {
      this.startNative();
    }
  }

  startNative(): void {
    try {
      NativeWakeWord.startListening(this.phraseKey, this.threshold);
      this.listening = true;
    } catch (error) {
      console.warn('[WakeWordStore] failed to start listener:', error);
      this.listening = false;
    }
  }

  stopNative(): void {
    try {
      NativeWakeWord.stopListening();
    } catch (error) {
      console.warn('[WakeWordStore] failed to stop listener:', error);
    }
    this.listening = false;
  }

  /** Voice mode is taking the mic: park the listener until it closes. */
  suspendNative(): void {
    if (!this.enabled || this.suspended) {
      return;
    }
    this.suspended = true;
    this.stopNative();
  }

  resumeNative(): void {
    if (!this.suspended) {
      return;
    }
    this.suspended = false;
    if (this.enabled) {
      this.startNative();
    }
  }

  /**
   * Reconcile with the native side: restart the service if it should be
   * running but is not (e.g. after a process restart), and pick up a wake
   * that relaunched the app while it was closed.
   */
  async syncNative(): Promise<void> {
    try {
      const running = await NativeWakeWord.isListening();
      runInAction(() => {
        this.listening = running;
      });
      if (this.enabled && !this.suspended && !running) {
        this.startNative();
      }
      if (await NativeWakeWord.consumePendingWake()) {
        this.handleDetected();
      }
    } catch (error) {
      console.warn('[WakeWordStore] sync failed:', error);
    }
  }

  /** A wake word detection arrived from the native listener. */
  handleDetected(): void {
    this.pendingVoiceStart = true;
  }

  clearPendingVoiceStart(): void {
    this.pendingVoiceStart = false;
  }
}

export const wakeWordStore = new WakeWordStore();
