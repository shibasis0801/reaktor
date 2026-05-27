export function installReaktorWebVitals() {
  if (globalThis.ReaktorPerf && globalThis.ReaktorPerf.__source === 'reaktor-performance') {
    return globalThis.ReaktorPerf;
  }

  const previous = globalThis.ReaktorPerf;
  const state = {
    marks: { ...(previous?.state?.marks || {}) },
    vitals: {
      cls: 0,
      fid: null,
      fcp: null,
      firstPaint: null,
      inp: 0,
      lcp: 0,
      ttfb: null,
      ...(previous?.state?.vitals || {}),
    },
    longTasks: [...(previous?.state?.longTasks || [])],
    frames: {
      count: 0,
      totalDuration: 0,
      maxDuration: 0,
      dropped: 0,
      frozen: 0,
      lastTimestamp: null,
      ...(previous?.state?.frames || {}),
    },
    memory: previous?.state?.memory || null,
    metrics: [...(previous?.state?.metrics || [])],
    frameMonitor: null,
    frameMonitorActive: false,
  };

  const round = (value) => (Number.isFinite(value) ? Math.round(value * 10) / 10 : null);

  function entryName(entry) {
    try {
      return new URL(entry.name, globalThis.location?.href).pathname;
    } catch (_) {
      return String(entry.name || '');
    }
  }

  function mark(name) {
    const value = performance.now();
    state.marks[name] = value;
    try {
      performance.mark(`reaktor:${name}`);
    } catch (_) {}
    globalThis.dispatchEvent(new CustomEvent('reaktor:perfmark', { detail: { name, value } }));
    return value;
  }

  function recordFrame(duration) {
    if (!Number.isFinite(duration) || duration < 0) return;
    const value = round(duration);
    state.frames.count += 1;
    state.frames.totalDuration += value;
    state.frames.maxDuration = Math.max(state.frames.maxDuration, value);
    if (value > 16.7) state.frames.dropped += 1;
    if (value > 700) state.frames.frozen += 1;
  }

  function startFrameMonitor() {
    if (state.frameMonitorActive) return state.frameMonitor;
    state.frameMonitorActive = true;
    const onFrame = (timestamp) => {
      if (!state.frameMonitorActive) return;
      if (state.frames.lastTimestamp != null && !document.hidden) {
        recordFrame(timestamp - state.frames.lastTimestamp);
      }
      state.frames.lastTimestamp = timestamp;
      state.frameMonitor = requestAnimationFrame(onFrame);
    };
    state.frameMonitor = requestAnimationFrame(onFrame);
    return {
      stop: stopFrameMonitor,
    };
  }

  function stopFrameMonitor() {
    state.frameMonitorActive = false;
    if (state.frameMonitor != null) cancelAnimationFrame(state.frameMonitor);
    state.frameMonitor = null;
  }

  function setMemory(memory) {
    state.memory = memory || null;
  }

  function metric(name, value, unit = 'ms') {
    if (!Number.isFinite(value)) return null;
    const item = { name, value: round(value), unit };
    state.metrics.push(item);
    return item;
  }

  function observe(type, options, callback) {
    if (!('PerformanceObserver' in globalThis)) return;
    try {
      const observer = new PerformanceObserver((list) => {
        list.getEntries().forEach(callback);
      });
      observer.observe({ type, buffered: true, ...(options || {}) });
    } catch (_) {}
  }

  observe('paint', null, (entry) => {
    if (entry.name === 'first-paint') state.vitals.firstPaint = entry.startTime;
    if (entry.name === 'first-contentful-paint') state.vitals.fcp = entry.startTime;
  });

  observe('largest-contentful-paint', null, (entry) => {
    state.vitals.lcp = entry.startTime;
  });

  observe('layout-shift', null, (entry) => {
    if (!entry.hadRecentInput) state.vitals.cls += entry.value || 0;
  });

  observe('first-input', null, (entry) => {
    state.vitals.fid = entry.processingStart - entry.startTime;
    state.vitals.inp = Math.max(state.vitals.inp, entry.processingEnd - entry.startTime);
  });

  observe('event', { durationThreshold: 16 }, (entry) => {
    if (entry.interactionId) state.vitals.inp = Math.max(state.vitals.inp, entry.duration || 0);
  });

  observe('longtask', null, (entry) => {
    state.longTasks.push({
      name: entry.name,
      startTime: entry.startTime,
      duration: entry.duration,
    });
  });

  function navTiming() {
    const nav = performance.getEntriesByType('navigation')[0];
    if (!nav) return null;
    const ttfb = nav.responseStart - nav.requestStart;
    state.vitals.ttfb = Number.isFinite(ttfb) ? ttfb : state.vitals.ttfb;
    return {
      ttfb: round(ttfb),
      domInteractive: round(nav.domInteractive),
      domContentLoaded: round(nav.domContentLoadedEventEnd),
      loadEventEnd: round(nav.loadEventEnd),
      transferSize: nav.transferSize || 0,
      encodedBodySize: nav.encodedBodySize || 0,
      decodedBodySize: nav.decodedBodySize || 0,
    };
  }

  function resourceReport() {
    const entries = performance.getEntriesByType('resource').map((entry) => ({
      name: entryName(entry),
      initiatorType: entry.initiatorType,
      startTime: entry.startTime,
      duration: entry.duration,
      transferSize: entry.transferSize || 0,
      encodedBodySize: entry.encodedBodySize || 0,
      decodedBodySize: entry.decodedBodySize || 0,
    }));
    const assets = entries.filter((entry) => /\/assets\//.test(entry.name));
    const scripts = assets.filter((entry) => /\.(js|mjs)$/.test(entry.name));
    const styles = assets.filter((entry) => /\.css$/.test(entry.name));
    const wasm = assets.filter((entry) => /\.wasm$/.test(entry.name));
    const graphBoot = scripts.find((entry) => /graph-boot/.test(entry.name));
    const reactMounted = state.marks.reactMounted || Number.POSITIVE_INFINITY;
    const sum = (items, field) => items.reduce((total, item) => total + (item[field] || 0), 0);

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
      largestAssets: assets
        .slice()
        .sort((a, b) => (b.transferSize || b.encodedBodySize) - (a.transferSize || a.encodedBodySize))
        .slice(0, 8)
        .map((entry) => ({
          name: entry.name,
          transferSize: entry.transferSize,
          encodedBodySize: entry.encodedBodySize,
          duration: round(entry.duration),
          startTime: round(entry.startTime),
        })),
    };
  }

  function report() {
    const navigation = navTiming();
    const longTaskTotal = state.longTasks.reduce((total, task) => total + task.duration, 0);
    const longTaskMax = state.longTasks.reduce((max, task) => Math.max(max, task.duration), 0);
    const marks = Object.fromEntries(Object.entries(state.marks).map(([name, value]) => [name, round(value)]));
    const duration = (from, to) => {
      const start = state.marks[from];
      const end = state.marks[to];
      return Number.isFinite(start) && Number.isFinite(end) ? round(end - start) : null;
    };

    return {
      url: globalThis.location?.href || '',
      generatedAt: new Date().toISOString(),
      navigation,
      vitals: {
        cls: round(state.vitals.cls),
        fid: round(state.vitals.fid),
        fcp: round(state.vitals.fcp),
        firstPaint: round(state.vitals.firstPaint),
        inp: round(state.vitals.inp),
        lcp: round(state.vitals.lcp),
        ttfb: round(state.vitals.ttfb),
      },
      marks,
      durations: {
        entryToReactMounted: duration('entry', 'reactMounted'),
        reactMountedToGraphBootStart: duration('reactMounted', 'graphBootImportStart'),
        graphBootImport: duration('graphBootImportStart', 'graphBootModuleLoaded'),
        graphBootRuntime: duration('graphBootStart', 'graphBootReady'),
        entryToGraphBootReady: duration('entry', 'graphBootReady'),
        entryToBestBudsFlowReady: duration('entry', 'bestBudsFlowReady'),
      },
      longTasks: {
        count: state.longTasks.length,
        totalDuration: round(longTaskTotal),
        maxDuration: round(longTaskMax),
        tasks: state.longTasks.slice(-12).map((task) => ({
          name: task.name,
          startTime: round(task.startTime),
          duration: round(task.duration),
        })),
      },
      appVitals: {
        appStartMs: duration('processStart', 'appStarted') ?? marks.appStarted ?? null,
        firstFrameMs: marks.firstFrame ?? null,
        firstInteractiveMs: marks.firstInteractive ?? marks.reactMounted ?? null,
        firstUsefulContentMs: marks.firstUsefulContent ?? marks.bestBudsFlowReady ?? null,
        graphReadyMs: marks.graphReady ?? marks.graphBootReady ?? null,
        routeReadyMs: marks.routeReady ?? null,
        dataReadyMs: marks.dataReady ?? null,
        droppedFrames: state.frames.dropped,
        frozenFrames: state.frames.frozen,
        frames: {
          frameCount: state.frames.count,
          averageFrameMs: state.frames.count ? round(state.frames.totalDuration / state.frames.count) : null,
          maxFrameMs: round(state.frames.maxDuration),
          droppedFrames: state.frames.dropped,
          frozenFrames: state.frames.frozen,
        },
        longTasks: {
          count: state.longTasks.length,
          totalDurationMs: round(longTaskTotal),
          maxDurationMs: round(longTaskMax),
        },
        memory: state.memory || performance.memory && {
          usedBytes: performance.memory.usedJSHeapSize || null,
          totalBytes: performance.memory.totalJSHeapSize || null,
          maxBytes: performance.memory.jsHeapSizeLimit || null,
        } || null,
        metrics: state.metrics.slice(),
      },
      resources: resourceReport(),
    };
  }

  const api = {
    mark,
    metric,
    recordFrame,
    report,
    setMemory,
    startFrameMonitor,
    state,
    stopFrameMonitor,
    __source: 'reaktor-performance',
  };

  globalThis.ReaktorPerf = api;
  if (!state.marks.entry) mark('entry');
  return api;
}
