import React, {useContext, useEffect, useState} from 'react';
import {Platform, View} from 'react-native';

import {observer} from 'mobx-react-lite';
import {Button, Card, Switch, Text} from 'react-native-paper';

import {useTheme} from '../../hooks';
import {t} from '../../locales';
import {
  WAKE_WORD_PHRASES,
  wakeWordStore,
  type WakeWordPhraseKey,
} from '../../store';
import {L10nContext} from '../../utils';
import {
  ensureMicPermission,
  openMicSettings,
  type MicPermissionResult,
} from '../../utils/asrMicPermission';

import {createStyles} from './styles';
import {WakeWordTeachSheet} from './WakeWordTeachSheet';

/**
 * Settings for the hands-free wake word (fork feature). The listener is an
 * Android foreground service, so the card only exists on Android.
 */
export const WakeWordCard: React.FC = observer(() => {
  const theme = useTheme();
  const styles = createStyles(theme);
  const l10n = useContext(L10nContext);
  const copy = l10n.settings.wakeWord;
  const store = wakeWordStore;

  const [micIssue, setMicIssue] = useState<MicPermissionResult | null>(null);
  const [teachVisible, setTeachVisible] = useState(false);

  useEffect(() => {
    if (Platform.OS === 'android') {
      wakeWordStore.refreshCustom();
    }
  }, []);

  if (Platform.OS !== 'android') {
    return null;
  }

  const onSwitchChange = async (enabled: boolean) => {
    if (!enabled) {
      setMicIssue(null);
      store.setEnabled(false);
      return;
    }
    const permission = await ensureMicPermission();
    if (permission !== 'granted') {
      setMicIssue(permission);
      return;
    }
    setMicIssue(null);
    store.setEnabled(true);
  };

  const onPhrasePress = (key: WakeWordPhraseKey) => {
    store.setPhraseKey(key);
  };

  const openTeachSheet = async () => {
    const permission = await ensureMicPermission();
    if (permission !== 'granted') {
      setMicIssue(permission);
      return;
    }
    setMicIssue(null);
    setTeachVisible(true);
  };

  return (
    <>
    <Card elevation={0} style={styles.card} testID="wake-word-card">
      <Card.Title title={copy.title} />
      <Card.Content>
        <View style={styles.settingItemContainer}>
          <View style={styles.switchContainer}>
            <View style={styles.textContainer}>
              <Text variant="titleMedium" style={styles.textLabel}>
                {copy.enable}
              </Text>
              <Text variant="labelSmall" style={styles.textDescription}>
                {copy.enableDescription}
              </Text>
            </View>
            <Switch
              testID="wake-word-switch"
              value={store.enabled}
              onValueChange={onSwitchChange}
            />
          </View>

          <Text variant="bodyMedium" style={styles.textDescription}>
            {store.listening
              ? t(copy.listening, {phrase: store.phraseLabel})
              : copy.off}
          </Text>

          {micIssue === 'denied' && (
            <Text
              variant="bodyMedium"
              style={styles.errorText}
              testID="wake-word-mic-denied">
              {copy.micDenied}
            </Text>
          )}

          {micIssue === 'blocked' && (
            <View style={styles.switchContainer}>
              <Text
                variant="bodyMedium"
                style={[styles.errorText, styles.textContainer]}
                testID="wake-word-mic-blocked">
                {copy.micBlocked}
              </Text>
              <Button mode="text" compact onPress={() => openMicSettings()}>
                {copy.openSettings}
              </Button>
            </View>
          )}

          <View style={styles.textContainer}>
            <Text variant="titleMedium" style={styles.textLabel}>
              {copy.phrase}
            </Text>
            <View style={styles.switchContainer}>
              {WAKE_WORD_PHRASES.map(phrase => (
                <Button
                  key={phrase.key}
                  testID={`wake-word-phrase-${phrase.key}`}
                  mode={
                    store.phraseKey === phrase.key ? 'contained' : 'outlined'
                  }
                  compact
                  onPress={() => onPhrasePress(phrase.key)}>
                  {phrase.label}
                </Button>
              ))}
              {store.hasCustom && (
                <Button
                  testID="wake-word-phrase-custom"
                  mode={
                    store.phraseKey === 'custom' ? 'contained' : 'outlined'
                  }
                  compact
                  onPress={() => onPhrasePress('custom')}>
                  {store.customName}
                </Button>
              )}
            </View>
          </View>

          <View style={styles.switchContainer}>
            <View style={styles.textContainer}>
              <Text variant="titleMedium" style={styles.textLabel}>
                {copy.customPhrase}
              </Text>
              <Text variant="labelSmall" style={styles.textDescription}>
                {store.hasCustom ? store.customName : copy.noCustom}
              </Text>
            </View>
            {store.hasCustom ? (
              <View style={styles.switchContainer}>
                <Button
                  testID="wake-word-teach-reteach"
                  mode="text"
                  compact
                  onPress={openTeachSheet}>
                  {copy.reteachButton}
                </Button>
                <Button
                  testID="wake-word-teach-delete"
                  mode="text"
                  compact
                  onPress={() => store.deleteCustom()}>
                  {copy.deleteCustom}
                </Button>
              </View>
            ) : (
              <Button
                testID="wake-word-teach-open"
                mode="outlined"
                compact
                onPress={openTeachSheet}>
                {copy.teachButton}
              </Button>
            )}
          </View>

          <Text variant="labelSmall" style={styles.textDescription}>
            {copy.batteryNote}
          </Text>
        </View>
      </Card.Content>
    </Card>

    <WakeWordTeachSheet
      isVisible={teachVisible}
      onClose={() => setTeachVisible(false)}
    />
    </>
  );
});
