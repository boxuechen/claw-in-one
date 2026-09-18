import React, {useState} from 'react';
import {
  Pressable,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

const APP_NAME = __APP_NAME_JSON__;

export default function App(): React.JSX.Element {
  const [count, setCount] = useState(0);

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <View style={styles.card}>
        <Text style={styles.eyebrow}>REACT NATIVE · ANDROID</Text>
        <Text style={styles.title}>{APP_NAME}</Text>
        <Text style={styles.subtitle}>
          Built on this phone with the qualified ARM64 profile.
        </Text>
        <Text accessibilityLabel={`Count ${count}`} style={styles.count}>
          {count}
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Increase count"
          onPress={() => setCount(value => value + 1)}
          style={({pressed}) => [styles.button, pressed && styles.buttonPressed]}>
          <Text style={styles.buttonLabel}>Tap to verify</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#07111f',
    justifyContent: 'center',
    padding: 24,
  },
  card: {
    borderRadius: 28,
    backgroundColor: '#10233c',
    padding: 28,
    alignItems: 'center',
  },
  eyebrow: {
    color: '#79b8ff',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.4,
  },
  title: {
    color: '#ffffff',
    fontSize: 34,
    fontWeight: '700',
    marginTop: 12,
    textAlign: 'center',
  },
  subtitle: {
    color: '#b7c8dc',
    fontSize: 16,
    lineHeight: 23,
    marginTop: 10,
    textAlign: 'center',
  },
  count: {
    color: '#ffffff',
    fontSize: 64,
    fontWeight: '300',
    marginTop: 28,
  },
  button: {
    minHeight: 52,
    minWidth: 180,
    borderRadius: 26,
    backgroundColor: '#4e9eff',
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 20,
    paddingHorizontal: 24,
  },
  buttonPressed: {
    opacity: 0.75,
  },
  buttonLabel: {
    color: '#06111f',
    fontSize: 17,
    fontWeight: '700',
  },
});
