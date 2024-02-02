import React, {useEffect, useState} from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  useColorScheme,
  View,
  Pressable,
  ToastAndroid, Platform,
  AppRegistry
} from 'react-native';
import {Colors} from "react-native/Libraries/NewAppScreen";
// import { StateFlow } from './src/types/Flow';

// import { NativeModules } from "react-native";
// import { getModule } from './src/modules/getModule';
// const { JSIManager } = NativeModules;

// JSIManager.install();
//
// const network = global.NetworkModule
// console.log(global)
// console.log(network)

// const flow = new StateFlow<number>(1);
// flow.collect(data => {})
// flow.emit(1);


// const nativeFlow: Flow<number> = network.getFlow();
// nativeFlow.collect(data => {})
//
// network.get().collect(data => {})

function measure(fn) {
  const start = performance.now();
  const result = fn();
  const end = performance.now();
  return {
    result, 
    time: end - start
  };
}


// const { result, time } = measure(() => network.getObject());
// console.log("Shibasis" + JSON.stringify(result))
const backgroundStyle = {
  backgroundColor: Colors.darker
};

const App = () => {
  const message = "not working jsi"

  return (
    <SafeAreaView style={backgroundStyle}>
      <View>
          <Text>
            {message}
          </Text>
      </View>
    </SafeAreaView>
  );
};


export default App;

AppRegistry.registerComponent("BatTester", () => App);
