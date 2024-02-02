/**
 * Metro configuration for React Native
 * https://github.com/facebook/react-native
 *
 * @format
 */
const blockList = require('metro-config/src/defaults/exclusionList');
module.exports = {
  resolver: {
    blockList: blockList([
      /android\/build\/.*/,
      /build\/js\/.*/,
      /build\/productionLibrary.*/,
      /build\/tmp.*/,
    ])
  },
  transformer: {
    getTransformOptions: async () => ({
      transform: {
        experimentalImportSupport: false,
        inlineRequires: true,
      },
    }),
  },
};
