import {AppState, Platform} from 'react-native';

import {makeAutoObservable, runInAction} from 'mobx';
import {makePersistable} from 'mobx-persist-store';
import AsyncStorage from '@react-native-async-storage/async-storage';
import DeviceInfo from 'react-native-device-info';
import Share from 'react-native-share';
import * as RNFS from '@dr.pogodin/react-native-fs';

import {DIAGNOSTICS_UPLOAD_TOKEN} from './diagnosticsConfig';

/**
 * Fork feature (arellaeast): anonymous diagnostics the user can share
 * with the developer, plus opt-in automatic problem reports.
 *
 * A small ring buffer records the events that matter when something
 * breaks in this fork's own features: wake word detections, voice mode
 * phase changes and start failures, model loads/releases, JS errors,
 * and sessions that end without a clean shutdown (a crash marker).
 * Nothing here ever records chat content, prompts, or audio; the only
 * identifiers are a random per-install ID and the model/voice names
 * the app itself displays.
 *
 * The buffer tail is persisted so a crash does not take the evidence
 * with it. Uploads go to the developer's own receiver and only happen
 * when the user turns auto-send on (or shares manually). The upload
 * token is not committed (this fork's repo is public); the release
 * build injects it, and with no token the app never uploads.
 */

export const DIAGNOSTICS_UPLOAD_URL = 'https://diag.jacobeast.dpdns.org/report';

const MAX_EVENTS = 1200;
const PERSISTED_EVENTS = 500;
const EVENTS_KEY = 'DiagnosticsEvents';
const SESSION_KEY = 'DiagnosticsSession';
const UPLOAD_THROTTLE_MS = 10 * 60 * 1000;

export type DiagnosticsEvent = {
  /** Epoch ms. */
  t: number;
  tag: string;
  msg: string;
};

type SessionMarker = {
  startedAt: number;
  /** True while the app was last seen in the foreground. */
  foreground: boolean;
};

type SnapshotProvider = () => Record<string, unknown>;

const errorText = (error: unknown): string => {
  if (error instanceof Error) {
    return error.stack ?? `${error.name}: ${error.message}`;
  }
  return String(error);
};

class DiagnosticsStore {
  /** Random per-install ID, persisted. Never tied to an account. */
  installId = '';
  /** Opt-in: upload a bundle on voice start failures and crashes. */
  autoSendEnabled = false;

  events: DiagnosticsEvent[] = [];

  private snapshotProviders = new Map<string, SnapshotProvider>();
  private persistTimer: ReturnType<typeof setTimeout> | null = null;
  private lastUploadAt = 0;
  private sessionStarted = false;
  private pendingCrashReport = false;

  constructor() {
    makeAutoObservable(this, {}, {autoBind: true});
    makePersistable(this, {
      name: 'DiagnosticsStore',
      properties: ['installId', 'autoSendEnabled'],
      storage: AsyncStorage,
    });
  }

  get id(): string {
    if (!this.installId) {
      const random = Math.floor(Math.random() * 0xffffffff)
        .toString(16)
        .padStart(8, '0');
      runInAction(() => {
        this.installId = `pp-${Date.now().toString(36)}-${random}`;
      });
    }
    return this.installId;
  }

  /** Other stores register a small state snapshot for the bundle. */
  registerSnapshotProvider(name: string, provider: SnapshotProvider): void {
    this.snapshotProviders.set(name, provider);
  }

  log(tag: string, msg: string): void {
    this.events.push({t: Date.now(), tag, msg});
    if (this.events.length > MAX_EVENTS) {
      this.events.splice(0, this.events.length - MAX_EVENTS);
    }
    this.schedulePersist();
  }

  logError(tag: string, error: unknown): void {
    this.log(tag, errorText(error).slice(0, 2000));
  }

  /**
   * A failure worth the developer's attention: always logged, and
   * uploaded when the user opted in. Uploads are throttled so a
   * failure loop cannot spam the receiver.
   */
  reportFailure(reason: string, error?: unknown): void {
    if (error !== undefined) {
      this.logError(`failure:${reason}`, error);
    } else {
      this.log(`failure:${reason}`, reason);
    }
    if (!this.autoSendEnabled || !DIAGNOSTICS_UPLOAD_TOKEN) {
      return;
    }
    const now = Date.now();
    if (now - this.lastUploadAt < UPLOAD_THROTTLE_MS) {
      return;
    }
    this.lastUploadAt = now;
    this.uploadBundle(reason).catch(() => {
      // Uploads are best-effort; the events stay on the phone and can
      // still be shared manually.
    });
  }

  /**
   * Call once at app start: restores the persisted event tail, works
   * out whether the previous session ended mid-foreground (a crash or
   * a force-kill while open), and starts tracking this session.
   */
  async startSession(): Promise<void> {
    if (this.sessionStarted) {
      return;
    }
    this.sessionStarted = true;
    try {
      const rawEvents = await AsyncStorage.getItem(EVENTS_KEY);
      if (rawEvents) {
        const parsed = JSON.parse(rawEvents) as DiagnosticsEvent[];
        if (Array.isArray(parsed)) {
          runInAction(() => {
            this.events = parsed.slice(-PERSISTED_EVENTS);
          });
        }
      }
      const rawSession = await AsyncStorage.getItem(SESSION_KEY);
      if (rawSession) {
        const previous = JSON.parse(rawSession) as SessionMarker;
        if (previous.foreground) {
          this.pendingCrashReport = true;
          this.log(
            'session',
            `previous session (started ${new Date(
              previous.startedAt,
            ).toISOString()}) ended while foregrounded, with no clean shutdown recorded: possible crash`,
          );
        }
      }
    } catch {
      // Diagnostics must never break app start.
    }
    await this.writeSessionMarker(true);
    this.log(
      'session',
      `started, app ${DeviceInfo.getVersion()} (${DeviceInfo.getBuildNumber()}), ${Platform.OS}`,
    );
    AppState.addEventListener('change', state => {
      this.writeSessionMarker(state === 'active').catch(() => {});
      if (state === 'background') {
        this.persistEvents().catch(() => {});
      }
    });
    if (this.pendingCrashReport) {
      this.pendingCrashReport = false;
      // Goes through reportFailure so the opt-in and throttle apply.
      this.reportFailure('previous-session-crash');
    }
  }

  /** Chain the JS global error handler into the log. */
  installGlobalErrorHandler(): void {
    const errorUtils = (global as {ErrorUtils?: any}).ErrorUtils;
    if (!errorUtils?.getGlobalHandler || !errorUtils?.setGlobalHandler) {
      return;
    }
    const previous = errorUtils.getGlobalHandler();
    errorUtils.setGlobalHandler((error: unknown, isFatal?: boolean) => {
      this.logError(isFatal ? 'js-fatal' : 'js-error', error);
      if (isFatal) {
        // The process is about to die; flush synchronously-ish.
        this.persistEvents().catch(() => {});
      }
      if (previous) {
        previous(error, isFatal);
      }
    });
  }

  buildBundle(reason: string): Record<string, unknown> {
    const state: Record<string, unknown> = {};
    this.snapshotProviders.forEach((provider, name) => {
      try {
        state[name] = provider();
      } catch {
        state[name] = 'snapshot failed';
      }
    });
    return {
      schema: 1,
      reason,
      exportedAt: new Date().toISOString(),
      installId: this.id,
      app: {
        version: DeviceInfo.getVersion(),
        build: DeviceInfo.getBuildNumber(),
        bundleId: DeviceInfo.getBundleId(),
      },
      device: {
        brand: DeviceInfo.getBrand(),
        model: DeviceInfo.getModel(),
        os: Platform.OS,
        osVersion: String(Platform.Version),
        systemVersion: DeviceInfo.getSystemVersion(),
      },
      state,
      events: this.events,
    };
  }

  async shareBundle(): Promise<void> {
    const bundle = this.buildBundle('manual-share');
    const json = JSON.stringify(bundle, null, 1);
    // Share a real .json file; long text shares get truncated by some
    // targets. If the write fails, fall back to sharing the text.
    let url: string | undefined;
    try {
      const path = `${RNFS.CachesDirectoryPath}/pocketpal-diagnostics-${Date.now()}.json`;
      await RNFS.writeFile(path, json, 'utf8');
      url = `file://${path}`;
    } catch {
      url = undefined;
    }
    try {
      await Share.open(
        url
          ? {
              title: 'PocketPal diagnostics',
              subject: 'PocketPal diagnostics',
              url,
              type: 'application/json',
              filename: 'pocketpal-diagnostics.json',
            }
          : {
              title: 'PocketPal diagnostics',
              subject: 'PocketPal diagnostics',
              message: json,
            },
      );
    } catch {
      // User dismissed the share sheet, or no target accepted it.
    }
  }

  async uploadBundle(reason: string): Promise<boolean> {
    if (!DIAGNOSTICS_UPLOAD_TOKEN) {
      return false;
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(DIAGNOSTICS_UPLOAD_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Diag-Token': DIAGNOSTICS_UPLOAD_TOKEN,
          'X-Install-Id': this.id,
        },
        body: JSON.stringify(this.buildBundle(reason)),
        signal: controller.signal,
      });
      return response.ok;
    } finally {
      clearTimeout(timeout);
    }
  }

  setAutoSendEnabled(enabled: boolean): void {
    this.autoSendEnabled = enabled;
    this.log('settings', `auto-send ${enabled ? 'on' : 'off'}`);
  }

  private async writeSessionMarker(foreground: boolean): Promise<void> {
    const marker: SessionMarker = {startedAt: Date.now(), foreground};
    await AsyncStorage.setItem(SESSION_KEY, JSON.stringify(marker));
  }

  private schedulePersist(): void {
    if (this.persistTimer) {
      return;
    }
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null;
      this.persistEvents().catch(() => {});
    }, 3000);
  }

  private async persistEvents(): Promise<void> {
    const tail = this.events.slice(-PERSISTED_EVENTS);
    await AsyncStorage.setItem(EVENTS_KEY, JSON.stringify(tail));
  }
}

export const diagnosticsStore = new DiagnosticsStore();
