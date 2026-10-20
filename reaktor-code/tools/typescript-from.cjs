const Module = require('node:module');

const compiler = process.env.REAKTOR_SCIP_TYPESCRIPT;
const resolve = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
  return request === 'typescript' && compiler ? compiler : resolve.call(this, request, ...rest);
};
