/*
 * Thin wrapper around the Kotlin object injected as window.VolumeBridge.
 * JavaScript never touches Android audio itself: every call below is validated and executed
 * by Kotlin (AudioManager). Outside Android (desktop browser) a small mock is used for previewing.
 */
(function (w) {
  'use strict';

  var native = w.VolumeBridge;
  var hasNative = !!native;

  function parse(text) {
    try { return JSON.parse(text); } catch (e) { return null; }
  }

  function call(name) {
    if (!hasNative || native[name] === undefined) { return null; }
    var args = Array.prototype.slice.call(arguments, 1);
    try { return native[name].apply(native, args); } catch (e) { return null; }
  }

  // ---- desktop preview mock ----
  var mock = { selected: 'media', cats: {
    media: { category: 'media', volume: 8, max: 15, percent: 53, muted: false },
    ring:  { category: 'ring',  volume: 4, max: 7,  percent: 57, muted: false },
    alarm: { category: 'alarm', volume: 5, max: 7,  percent: 71, muted: false }
  } };
  function mockSet(cat, volume) {
    var c = mock.cats[cat]; if (!c) { return { ok: false }; }
    c.volume = Math.max(0, Math.min(c.max, volume));
    c.percent = Math.round(c.volume * 100 / c.max);
    c.muted = c.volume === 0;
    var r = JSON.parse(JSON.stringify(c)); r.ok = true; return r;
  }

  w.AndroidBridge = {
    available: hasNative,

    getState: function () {
      return hasNative ? parse(call('getState'))
                       : JSON.parse(JSON.stringify({ selected: mock.selected, categories: mock.cats }));
    },
    getVolume: function (c) { return hasNative ? call('getVolume', c) : (mock.cats[c] || {}).volume; },
    getMaxVolume: function (c) { return hasNative ? call('getMaxVolume', c) : (mock.cats[c] || {}).max; },

    /** percent: 0..100 (Kotlin converts to the real Android step). Returns the ACTUAL resulting state. */
    setVolume: function (c, percent) {
      if (hasNative) { return parse(call('setVolume', c, percent)); }
      var m = mock.cats[c]; return m ? mockSet(c, Math.round(percent * m.max / 100)) : null;
    },
    commitVolume: function (c) { call('commitVolume', c); },
    increaseVolume: function (c) {
      if (hasNative) { return parse(call('increaseVolume', c)); }
      var m = mock.cats[c]; return m ? mockSet(c, m.volume + 1) : null;
    },
    decreaseVolume: function (c) {
      if (hasNative) { return parse(call('decreaseVolume', c)); }
      var m = mock.cats[c]; return m ? mockSet(c, m.volume - 1) : null;
    },
    muteVolume: function (c) {
      if (hasNative) { return parse(call('muteVolume', c)); }
      var m = mock.cats[c]; if (!m) { return null; } m.prev = m.volume; return mockSet(c, 0);
    },
    restoreVolume: function (c) {
      if (hasNative) { return parse(call('restoreVolume', c)); }
      var m = mock.cats[c]; return m ? mockSet(c, m.prev || Math.round(m.max / 2)) : null;
    },
    setSelectedCategory: function (c) { if (hasNative) { call('setSelectedCategory', c); } else { mock.selected = c; } },
    setInteracting: function (active) { call('setInteracting', !!active); },
    collapseController: function () { call('collapseController'); },
    openApp: function () { call('openApp'); },
    closeController: function () { call('closeController'); },
    /** Rects (CSS px) where a sideways swipe must not collapse the panel: [[l, t, r, b], ...]. */
    setNoSwipeZones: function (zones) { call('setNoSwipeZones', JSON.stringify(zones)); }
  };
})(window);
