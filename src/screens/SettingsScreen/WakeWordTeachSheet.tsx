import React, {useContext, useEffect, useRef, useState} from 'react';
import {Keyboard, View} from 'react-native';

import AudioRecord from '@fugood/react-native-audio-pcm-stream';
import {fromByteArray, toByteArray} from 'base64-js';
import {Button, Text} from 'react-native-paper';

import {Sheet, TextInput} from '../../components';
import {useTheme} from '../../hooks';
import {t} from '../../locales';
import {
  ASR_BITS_PER_SAMPLE,
  ASR_CHANNELS,
  ASR_CHUNK_BYTES,
  ASR_SAMPLE_RATE,
} from '../../services/asr/constants';
import NativeWakeWord from '../../specs/NativeWakeWord';
import {wakeWordStore} from '../../store';
import {L10nContext} from '../../utils';

import {createStyles} from './styles';

/** Keep in sync with WakeWordTeacher on the native side. */
const TARGET_TAKES = 15;
const MIN_TAKES = 10;
const MAX_TAKE_MS = 3000;

type TakePhase = 'idle' | 'recording' | 'processing';

interface TakeVerdict {
  accepted: boolean;
  reason?: string;
  count: number;
}

/**
 * Teach-a-name flow for the wake word (fork feature): the user names the
 * assistant anything they like and records themselves saying it; the
 * native teacher turns the takes into a personal wake template. While the
 * sheet is open the background listener is suspended, since both need the
 * microphone.
 */
export const WakeWordTeachSheet: React.FC<{
  isVisible: boolean;
  onClose: () => void;
}> = ({isVisible, onClose}) => {
  const theme = useTheme();
  const styles = createStyles(theme);
  const l10n = useContext(L10nContext);
  const copy = l10n.settings.wakeWord.teach;

  const [name, setName] = useState('');
  const [count, setCount] = useState(0);
  const [phase, setPhase] = useState<TakePhase>('idle');
  const [feedback, setFeedback] = useState<string | null>(null);
  const [finishFailed, setFinishFailed] = useState(false);

  const sessionRef = useRef(0);
  const chunksRef = useRef<Uint8Array[]>([]);
  const listenerRef = useRef<{remove: () => void} | null>(null);
  const stopTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const finishedRef = useRef(false);

  // Session lifecycle: opening suspends the listener and starts a native
  // teaching session; closing (any path) ends it and resumes listening.
  useEffect(() => {
    if (!isVisible) {
      return;
    }
    const session = ++sessionRef.current;
    finishedRef.current = false;
    setName(wakeWordStore.customName);
    setCount(0);
    setPhase('idle');
    setFeedback(null);
    setFinishFailed(false);
    wakeWordStore.suspendNative();
    try {
      NativeWakeWord.beginTeaching();
    } catch (error) {
      console.warn('[WakeWordTeachSheet] beginTeaching failed:', error);
    }
    return () => {
      if (session !== sessionRef.current) {
        return;
      }
      stopRecording();
      if (!finishedRef.current) {
        try {
          NativeWakeWord.cancelTeaching();
        } catch (error) {
          console.warn('[WakeWordTeachSheet] cancelTeaching failed:', error);
        }
      }
      wakeWordStore.resumeNative();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isVisible]);

  const stopRecording = () => {
    if (stopTimerRef.current) {
      clearTimeout(stopTimerRef.current);
      stopTimerRef.current = null;
    }
    listenerRef.current?.remove();
    listenerRef.current = null;
    Promise.resolve(AudioRecord.stop()).catch(() => {});
  };

  const startTake = async () => {
    if (phase !== 'idle') {
      return;
    }
    setFeedback(null);
    setFinishFailed(false);
    try {
      await AudioRecord.init({
        sampleRate: ASR_SAMPLE_RATE,
        channels: ASR_CHANNELS,
        bitsPerSample: ASR_BITS_PER_SAMPLE,
        bufferSize: ASR_CHUNK_BYTES,
        wavFile: '',
      });
    } catch (error) {
      console.warn('[WakeWordTeachSheet] audio init failed:', error);
      setFeedback(copy.tooQuiet);
      return;
    }
    chunksRef.current = [];
    listenerRef.current = AudioRecord.on('data', (chunk: string) => {
      chunksRef.current.push(toByteArray(chunk));
    });
    AudioRecord.start();
    setPhase('recording');
    stopTimerRef.current = setTimeout(() => finishTake(), MAX_TAKE_MS);
  };

  const finishTake = async () => {
    if (phase !== 'recording') {
      return;
    }
    stopRecording();
    setPhase('processing');
    const totalLength = chunksRef.current.reduce(
      (sum, chunk) => sum + chunk.length,
      0,
    );
    const pcm = new Uint8Array(totalLength);
    let offset = 0;
    for (const chunk of chunksRef.current) {
      pcm.set(chunk, offset);
      offset += chunk.length;
    }
    chunksRef.current = [];
    try {
      const verdict = JSON.parse(
        await NativeWakeWord.addTeachingTake(fromByteArray(pcm)),
      ) as TakeVerdict;
      setCount(verdict.count);
      if (verdict.accepted) {
        setFeedback(copy.goodTake);
      } else {
        setFeedback(
          verdict.reason === 'too_quiet'
            ? copy.tooQuiet
            : verdict.reason === 'too_short'
              ? copy.tooShort
              : verdict.reason === 'too_long'
                ? copy.tooLong
                : copy.outlier,
        );
      }
    } catch (error) {
      console.warn('[WakeWordTeachSheet] take analysis failed:', error);
      setFeedback(copy.outlier);
    }
    setPhase('idle');
  };

  const onRecordPress = () => {
    if (phase === 'idle') {
      startTake();
    } else if (phase === 'recording') {
      finishTake();
    }
  };

  const finishTeaching = async () => {
    const trimmed = name.trim();
    if (trimmed.length === 0 || count < MIN_TAKES) {
      return;
    }
    Keyboard.dismiss();
    try {
      if (await NativeWakeWord.finishTeaching(trimmed)) {
        finishedRef.current = true;
        wakeWordStore.onCustomTaught(trimmed);
        onClose();
        return;
      }
    } catch (error) {
      console.warn('[WakeWordTeachSheet] finishTeaching failed:', error);
    }
    setFinishFailed(true);
  };

  const closeSheet = () => {
    Keyboard.dismiss();
    onClose();
  };

  if (!isVisible) {
    return null;
  }

  return (
    <Sheet isVisible={isVisible} onClose={closeSheet} title={copy.title}>
      <Sheet.ScrollView contentContainerStyle={styles.keySheetContent}>
        <Text variant="bodyMedium" style={styles.textDescription}>
          {t(copy.intro, {target: TARGET_TAKES})}
        </Text>
        <TextInput
          testID="wake-word-teach-name"
          value={name}
          onChangeText={setName}
          label={copy.nameLabel}
          placeholder={copy.namePlaceholder}
          autoCapitalize="words"
          autoCorrect={false}
        />
        <Text variant="titleMedium" style={styles.textLabel}>
          {t(copy.progress, {count, target: TARGET_TAKES})}
        </Text>
        <Button
          testID="wake-word-teach-record"
          mode={phase === 'recording' ? 'contained' : 'outlined'}
          onPress={onRecordPress}
          disabled={phase === 'processing'}>
          {phase === 'recording' ? copy.recording : copy.record}
        </Button>
        {feedback !== null && (
          <Text variant="bodyMedium" style={styles.textDescription}>
            {feedback}
          </Text>
        )}
        {finishFailed && (
          <Text variant="bodyMedium" style={styles.errorText}>
            {copy.finishFailed}
          </Text>
        )}
        {count < MIN_TAKES && (
          <Text variant="labelSmall" style={styles.textDescription}>
            {t(copy.finishHint, {min: MIN_TAKES})}
          </Text>
        )}
      </Sheet.ScrollView>
      <Sheet.Actions>
        <View style={styles.keySheetActions}>
          <Button mode="text" onPress={closeSheet}>
            {l10n.common.cancel}
          </Button>
          <Button
            testID="wake-word-teach-finish"
            mode="contained"
            onPress={finishTeaching}
            disabled={name.trim().length === 0 || count < MIN_TAKES}>
            {copy.finish}
          </Button>
        </View>
      </Sheet.Actions>
    </Sheet>
  );
};
