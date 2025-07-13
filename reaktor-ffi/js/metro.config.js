module.exports = {
  transformer: {
    babelTransformerPath: require.resolve('metro-react-native-babel-transformer'),
  },
  resolver: {
    sourceExts: ['ts', 'tsx', 'js', 'json'],
  },
};