const lib = require('./build/compileSync/js/main/developmentLibrary/kotlin/flatInvoker-suspend-js-test.js');

lib.fetchStaticDataAsync().then(data => {
  console.log('message:' + data.message);
}).catch(err => {
  console.error('error', err);
});
