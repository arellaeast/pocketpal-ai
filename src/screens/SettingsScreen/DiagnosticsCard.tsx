import React, {useContext} from 'react';
import {View} from 'react-native';

import {observer} from 'mobx-react-lite';
import {Button, Card, Switch, Text} from 'react-native-paper';

import {useTheme} from '../../hooks';
import {t} from '../../locales';
import {diagnosticsStore} from '../../store';
import {L10nContext} from '../../utils';

import {createStyles} from './styles';

/**
 * Fork feature: share anonymous diagnostics with the developer, and
 * opt in to automatic problem reports. See DiagnosticsStore for what
 * is (and is not) recorded.
 */
export const DiagnosticsCard: React.FC = observer(() => {
  const theme = useTheme();
  const styles = createStyles(theme);
  const l10n = useContext(L10nContext);
  const copy = l10n.settings.diagnostics;
  const store = diagnosticsStore;

  return (
    <Card elevation={0} style={styles.card} testID="diagnostics-card">
      <Card.Title title={copy.title} />
      <Card.Content>
        <View style={styles.settingItemContainer}>
          <Text variant="bodyMedium" style={styles.textDescription}>
            {copy.description}
          </Text>

          <View style={styles.switchContainer}>
            <View style={styles.textContainer}>
              <Text variant="titleMedium" style={styles.textLabel}>
                {copy.autoSend}
              </Text>
              <Text variant="labelSmall" style={styles.textDescription}>
                {copy.autoSendDescription}
              </Text>
            </View>
            <Switch
              testID="diagnostics-autosend-switch"
              value={store.autoSendEnabled}
              onValueChange={enabled => store.setAutoSendEnabled(enabled)}
            />
          </View>

          <Text variant="bodyMedium" style={styles.textDescription}>
            {t(copy.anonymousId, {id: store.id})}
          </Text>
          <Text variant="bodyMedium" style={styles.textDescription}>
            {t(copy.eventsRecorded, {count: store.events.length})}
          </Text>

          <Button
            mode="contained"
            testID="diagnostics-share-button"
            onPress={() => store.shareBundle()}>
            {copy.share}
          </Button>
        </View>
      </Card.Content>
    </Card>
  );
});
