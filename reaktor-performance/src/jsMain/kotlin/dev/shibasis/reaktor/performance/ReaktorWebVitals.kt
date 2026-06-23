@file:Suppress("NON_EXPORTABLE_TYPE")

package dev.shibasis.reaktor.performance

import kotlin.js.JsExport

@JsExport
object ReaktorWebVitals {
    fun install(): dynamic = js(
        """
        (function () {
          if (globalThis.ReaktorPerf && globalThis.ReaktorPerf.__source === 'reaktor-performance') {
            return globalThis.ReaktorPerf;
          }

          var PERF_PREFIX = 'reaktor:';
          var state = {
            marks: {},
            vitals: {
              cls: 0,
              fcp: null,
              firstPaint: null,
              inp: 0,
              lcp: 0,
              ttfb: null
            },
            longTasks: []
          };

          function round(value) {
            return Number.isFinite(value) ? Math.round(value * 10) / 10 : null;
          }

          function entryName(entry) {
            try {
              return new URL(entry.name, globalThis.location && globalThis.location.href || undefined).pathname;
            } catch (_) {
              return String(entry.name || '');
            }
          }

          function mark(name) {
            var value = performance.now();
            state.marks[name] = value;
            try {
              performance.mark(PERF_PREFIX + name);
            } catch (_) {}
            globalThis.dispatchEvent(new CustomEvent('reaktor:perfmark', { detail: { name: name, value: value } }));
            return value;
          }

          function observe(type, options, callback) {
            if (!('PerformanceObserver' in globalThis)) return;
            try {
              var observer = new PerformanceObserver(function (list) {
                list.getEntries().forEach(callback);
              });
              observer.observe(Object.assign({ type: type, buffered: true }, options || {}));
            } catch (_) {}
          }

          observe('paint', null, function (entry) {
            if (entry.name === 'first-paint') state.vitals.firstPaint = entry.startTime;
            if (entry.name === 'first-contentful-paint') state.vitals.fcp = entry.startTime;
          });

          observe('largest-contentful-paint', null, function (entry) {
            state.vitals.lcp = entry.startTime;
          });

          observe('layout-shift', null, function (entry) {
            if (!entry.hadRecentInput) state.vitals.cls += entry.value || 0;
          });

          observe('first-input', null, function (entry) {
            state.vitals.inp = Math.max(state.vitals.inp, entry.processingEnd - entry.startTime);
          });

          observe('event', { durationThreshold: 16 }, function (entry) {
            if (entry.interactionId) state.vitals.inp = Math.max(state.vitals.inp, entry.duration || 0);
          });

          observe('longtask', null, function (entry) {
            state.longTasks.push({
              name: entry.name,
              startTime: entry.startTime,
              duration: entry.duration
            });
          });

          function navTiming() {
            var nav = performance.getEntriesByType('navigation')[0];
            if (!nav) return null;
            var ttfb = nav.responseStart - nav.requestStart;
            state.vitals.ttfb = Number.isFinite(ttfb) ? ttfb : state.vitals.ttfb;
            return {
              ttfb: round(ttfb),
              domInteractive: round(nav.domInteractive),
              domContentLoaded: round(nav.domContentLoadedEventEnd),
              loadEventEnd: round(nav.loadEventEnd),
              transferSize: nav.transferSize || 0,
              encodedBodySize: nav.encodedBodySize || 0,
              decodedBodySize: nav.decodedBodySize || 0
            };
          }

          function resourceReport() {
            var entries = performance.getEntriesByType('resource').map(function (entry) {
              return {
                name: entryName(entry),
                initiatorType: entry.initiatorType,
                startTime: entry.startTime,
                duration: entry.duration,
                transferSize: entry.transferSize || 0,
                encodedBodySize: entry.encodedBodySize || 0,
                decodedBodySize: entry.decodedBodySize || 0
              };
            });
            var assets = entries.filter(function (entry) { return /\/assets\//.test(entry.name); });
            var scripts = assets.filter(function (entry) { return /\.(js|mjs)$/.test(entry.name); });
            var styles = assets.filter(function (entry) { return /\.css$/.test(entry.name); });
            var wasm = assets.filter(function (entry) { return /\.wasm$/.test(entry.name); });
            var graphBoot = scripts.find(function (entry) { return /graph-boot/.test(entry.name); });
            var reactMounted = state.marks.reactMounted || Number.POSITIVE_INFINITY;
            var sum = function (items, field) {
              return items.reduce(function (total, item) { return total + (item[field] || 0); }, 0);
            };
            return {
              totalAssets: assets.length,
              totalTransferSize: sum(assets, 'transferSize'),
              totalEncodedBodySize: sum(assets, 'encodedBodySize'),
              scriptCount: scripts.length,
              scriptTransferSize: sum(scripts, 'transferSize'),
              styleTransferSize: sum(styles, 'transferSize'),
              wasmTransferSize: sum(wasm, 'transferSize'),
              graphBootStart: graphBoot ? round(graphBoot.startTime) : null,
              graphBootDuration: graphBoot ? round(graphBoot.duration) : null,
              graphBootRequestedBeforeReactMounted: graphBoot ? graphBoot.startTime < reactMounted : false,
              largestAssets: assets.slice().sort(function (a, b) {
                return (b.transferSize || b.encodedBodySize) - (a.transferSize || a.encodedBodySize);
              }).slice(0, 8).map(function (entry) {
                return {
                  name: entry.name,
                  transferSize: entry.transferSize,
                  encodedBodySize: entry.encodedBodySize,
                  duration: round(entry.duration),
                  startTime: round(entry.startTime)
                };
              })
            };
          }

          function report() {
            var navigation = navTiming();
            var longTaskTotal = state.longTasks.reduce(function (total, task) { return total + task.duration; }, 0);
            var longTaskMax = state.longTasks.reduce(function (max, task) { return Math.max(max, task.duration); }, 0);
            var marks = Object.fromEntries(Object.entries(state.marks).map(function (entry) {
              return [entry[0], round(entry[1])];
            }));
            var duration = function (from, to) {
              var start = state.marks[from];
              var end = state.marks[to];
              return Number.isFinite(start) && Number.isFinite(end) ? round(end - start) : null;
            };
            return {
              url: globalThis.location && globalThis.location.href || '',
              generatedAt: new Date().toISOString(),
              navigation: navigation,
              vitals: {
                cls: round(state.vitals.cls),
                fcp: round(state.vitals.fcp),
                firstPaint: round(state.vitals.firstPaint),
                inp: round(state.vitals.inp),
                lcp: round(state.vitals.lcp),
                ttfb: round(state.vitals.ttfb)
              },
              marks: marks,
              durations: {
                entryToReactMounted: duration('entry', 'reactMounted'),
                reactMountedToGraphBootStart: duration('reactMounted', 'graphBootImportStart'),
                graphBootImport: duration('graphBootImportStart', 'graphBootModuleLoaded'),
                graphBootRuntime: duration('graphBootStart', 'graphBootReady'),
                entryToGraphBootReady: duration('entry', 'graphBootReady'),
                entryToFlowReady: duration('entry', 'flowReady')
              },
              longTasks: {
                count: state.longTasks.length,
                totalDuration: round(longTaskTotal),
                maxDuration: round(longTaskMax),
                tasks: state.longTasks.slice(-12).map(function (task) {
                  return {
                    name: task.name,
                    startTime: round(task.startTime),
                    duration: round(task.duration)
                  };
                })
              },
              resources: resourceReport()
            };
          }

          var api = {
            mark: mark,
            report: report,
            state: state,
            __source: 'reaktor-performance'
          };
          globalThis.ReaktorPerf = api;
          mark('entry');
          return api;
        })()
        """,
    )
}
