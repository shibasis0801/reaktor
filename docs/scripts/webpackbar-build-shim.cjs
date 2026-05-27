'use strict';

// Docusaurus 3.10 passes webpackbar options through Webpack's ProgressPlugin
// validation path with the current dependency tree. The progress bar is only
// cosmetic, so replace it with a no-op plugin for deterministic builds.
const Module = require('module');

const originalLoad = Module._load;

class DisabledWebpackBarPlugin {
  apply() {}
}

Module._load = function patchedLoad(request, parent, isMain) {
  if (request === 'webpackbar') {
    return DisabledWebpackBarPlugin;
  }

  return originalLoad.apply(this, arguments);
};
