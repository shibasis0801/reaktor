const fs = require('fs');
const { performance } = require('perf_hooks');
const rawData = fs.readFileSync("./search.json", 'utf8');
function parse(rawData) {
    const startTime = performance.now();
    const data = JSON.parse(rawData);
    const endTime = performance.now();
    return endTime - startTime;
}

console.log(`readJsonFile took ${parse(rawData)} milliseconds`);
// 1ms on a M1 Max MacBook Pro
//https://230.jsondocs.prtest.cppalliance.org/libs/json/doc/html/json/benchmarks.html