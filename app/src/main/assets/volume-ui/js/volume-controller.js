
/*
 * VOLUME MASTER - FLOATING CONTROLLER
 *
 * Features:
 * - Vertical volume slider
 * - Plus/minus with press-and-hold
 * - Media, ringtone and alarm
 * - Mute/restore
 * - Morphing circle transition
 * - Expand/collapse animation
 * - Kotlin AndroidBridge synchronization
 *
 * Data flow:
 * User input -> AndroidBridge -> AudioManager -> Actual state -> UI
 */

(function (w, d) {
  'use strict';

  var B = w.AndroidBridge;
  var CATS = ['media', 'ring', 'alarm'];

  // ---------- DOM ELEMENTS ----------

  var root = d.getElementById('vc');
  var track = d.getElementById('track');
  var fill = d.getElementById('fill');
  var thumb = d.getElementById('thumb');
  var slider = d.getElementById('slider');
  var percentEl = d.getElementById('percent');
  var muteIcon = d.getElementById('mute-icon');

  var stage = d.querySelector('.vc-stage');
  var handle = d.querySelector('.vc-handle');

  var tabs = Array.prototype.slice.call(
    d.querySelectorAll('.vc-tab')
  );

  if (!B || !root || !track || !stage) {
    console.error('Volume Master: Required elements or AndroidBridge missing.');
    return;
  }

  // ---------- STATE ----------

  var state = {
    selected: 'media',
    cats: {}
  };

  var dragging = false;
  var pendingPercent = null;
  var rafId = 0;

  var transitionBusy = false;
  var transitionTimers = [];

  var expanded = false;

  function clamp(v, lo, hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  function current() {
    return state.cats[state.selected];
  }

  function clearTransitionTimers() {
    transitionTimers.forEach(function (id) {
      w.clearTimeout(id);
    });

    transitionTimers = [];
  }

  function later(callback, delay) {
    var id = w.setTimeout(function () {
      transitionTimers = transitionTimers.filter(function (t) {
        return t !== id;
      });

      callback();
    }, delay);

    transitionTimers.push(id);
  }

  // =====================================================
  // 1. MORPHING CIRCLE TRANSITION
  // =====================================================

  function expandVolumePanel() {
    if (transitionBusy || expanded) return;

    transitionBusy = true;
    expanded = true;

    clearTransitionTimers();

    // Step 1: Handle becomes a glowing circle.
    stage.classList.add('is-morphing');

    // Step 2: Circle expands into the glass panel.
    later(function () {
      stage.classList.add('is-open');
    }, 320);

    // Step 3: Finish animation.
    later(function () {
      stage.classList.remove('is-morphing');
      transitionBusy = false;

      reportZones();
    }, 750);
  }

  function collapseVolumePanel() {
    if (transitionBusy || !expanded) return;

    transitionBusy = true;
    expanded = false;

    clearTransitionTimers();

    // Collapse the panel toward the circle.
    stage.classList.remove('is-open');

    // Restore the narrow edge handle.
    later(function () {
      stage.classList.remove('is-morphing');
      transitionBusy = false;

      zonesSent = '';
    }, 450);
  }

  function toggleVolumePanel() {
    if (expanded) {
      collapseVolumePanel();
    } else {
      expandVolumePanel();
    }
  }

  // Handle tap as an alternative to swipe.
  if (handle) {
    handle.addEventListener('click', function () {
      toggleVolumePanel();
    });
  }

  // =====================================================
  // 2. VOLUME RENDERING
  // =====================================================

  function drawLevel(percent) {
    percent = clamp(Number(percent) || 0, 0, 100);

    fill.style.height = percent + '%';
    thumb.style.bottom = percent + '%';
  }

  function render() {
    var c = current();

    tabs.forEach(function (t) {
      var on =
        t.getAttribute('data-category') === state.selected;

      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });

    if (!c) return;

    percentEl.textContent = c.percent;

    slider.setAttribute('aria-valuenow', c.percent);

    muteIcon.src = c.muted
      ? 'icons/mute.svg'
      : 'icons/volume.svg';

    root.classList.toggle('is-muted', !!c.muted);

    if (!dragging) {
      drawLevel(c.percent);
    }
  }

  function mergeCategory(c) {
    if (c && c.category && CATS.indexOf(c.category) >= 0) {
      state.cats[c.category] = {
        category: c.category,
        volume: c.volume,
        max: c.max,
        percent: c.percent,
        muted: c.muted
      };
    }
  }

  function setState(s) {
    if (!s) return;

    if (s.categories) {
      CATS.forEach(function (k) {
        if (s.categories[k]) {
          state.cats[k] = s.categories[k];
        }
      });
    }

    if (s.selected && CATS.indexOf(s.selected) >= 0) {
      state.selected = s.selected;
    }

    render();
  }

  function applyResult(result) {
    if (!result) return;

    mergeCategory(result);
    render();
  }

  // =====================================================
  // 3. VERTICAL VOLUME SLIDER
  // =====================================================

  function percentFromEvent(e) {
    var r = track.getBoundingClientRect();

    if (r.height <= 0) return 0;

    return clamp(
      (1 - (e.clientY - r.top) / r.height) * 100,
      0,
      100
    );
  }

  function flush() {
    rafId = 0;

    if (pendingPercent === null) return;

    var p = pendingPercent;
    pendingPercent = null;

    var result = B.setVolume(state.selected, p);

    if (result) {
      mergeCategory(result);

      percentEl.textContent = result.percent;

      slider.setAttribute(
        'aria-valuenow',
        result.percent
      );
    }
  }

  function moveTo(e) {
    var p = percentFromEvent(e);

    drawLevel(p);

    pendingPercent = p;

    if (!rafId) {
      rafId = w.requestAnimationFrame(flush);
    }
  }

  function endDrag() {
    if (!dragging) return;

    if (rafId) {
      w.cancelAnimationFrame(rafId);
      rafId = 0;
    }

    flush();

    dragging = false;

    root.classList.remove('dragging');

    B.commitVolume(state.selected);
    B.setInteracting(false);

    render();
    reportZones();
  }

  track.addEventListener('pointerdown', function (e) {
    e.preventDefault();

    dragging = true;

    root.classList.add('dragging');

    B.setInteracting(true);

    try {
      track.setPointerCapture(e.pointerId);
    } catch (err) {}

    moveTo(e);
  });

  track.addEventListener('pointermove', function (e) {
    if (dragging) {
      e.preventDefault();
      moveTo(e);
    }
  });

  [
    'pointerup',
    'pointercancel',
    'lostpointercapture'
  ].forEach(function (eventName) {
    track.addEventListener(eventName, endDrag);
  });

  // =====================================================
  // 4. PLUS / MINUS BUTTONS
  // =====================================================

  function bindStepButton(id, direction) {
    var btn = d.getElementById(id);

    if (!btn) return;

    var delayTimer = 0;
    var repeatTimer = 0;
    var active = false;

    function step() {
      applyResult(
        direction > 0
          ? B.increaseVolume(state.selected)
          : B.decreaseVolume(state.selected)
      );
    }

    function stop() {
      if (!active) return;

      active = false;

      w.clearTimeout(delayTimer);
      w.clearInterval(repeatTimer);

      btn.classList.remove('pressed');

      B.setInteracting(false);
    }

    btn.addEventListener('pointerdown', function (e) {
      e.preventDefault();

      active = true;

      btn.classList.add('pressed');

      try {
        btn.setPointerCapture(e.pointerId);
      } catch (err) {}

      B.setInteracting(true);

      step();

      delayTimer = w.setTimeout(function () {
        repeatTimer = w.setInterval(step, 130);
      }, 420);
    });

    [
      'pointerup',
      'pointercancel',
      'lostpointercapture'
    ].forEach(function (eventName) {
      btn.addEventListener(eventName, stop);
    });
  }

  bindStepButton('btn-plus', +1);
  bindStepButton('btn-minus', -1);

  // =====================================================
  // 5. MUTE / RESTORE
  // =====================================================

  var muteButton = d.getElementById('btn-mute');

  muteButton.addEventListener('click', function () {
    var c = current();

    if (!c) return;

    applyResult(
      c.muted
        ? B.restoreVolume(state.selected)
        : B.muteVolume(state.selected)
    );
  });

  // =====================================================
  // 6. CATEGORY TABS
  // =====================================================

  tabs.forEach(function (t) {
    t.addEventListener('click', function () {
      var key = t.getAttribute('data-category');

      if (CATS.indexOf(key) < 0) return;
      if (key === state.selected) return;

      state.selected = key;

      B.setSelectedCategory(key);

      setState(B.getState());
    });
  });

  // =====================================================
  // 7. WINDOW CONTROLS
  // =====================================================

  var collapseButton = d.getElementById('btn-collapse');
  var openButton = d.getElementById('btn-open');
  var closeButton = d.getElementById('btn-close');

  if (collapseButton) {
    collapseButton.addEventListener('click', function () {
      collapseVolumePanel();
      B.collapseController();
    });
  }

  if (openButton) {
    openButton.addEventListener('click', function () {
      B.openApp();
    });
  }

  if (closeButton) {
    closeButton.addEventListener('click', function () {
      B.closeController();
    });
  }

  d.addEventListener('contextmenu', function (e) {
    e.preventDefault();
  });

  // =====================================================
  // 8. SWIPE-SAFE ZONES
  // =====================================================

  var zonesSent = '';

  function reportZones() {
    if (!B.available || !w.innerWidth || !expanded) return;

    var pad = 10;

    var zones = [
      'slider',
      'btn-plus',
      'btn-minus'
    ].map(function (id) {
      var el = d.getElementById(id);
      if (!el) return null;

      var r = el.getBoundingClientRect();

      return [
        r.left - pad,
        r.top - pad,
        r.right + pad,
        r.bottom + pad
      ];
    }).filter(Boolean);

    var json = JSON.stringify(zones);

    if (json === zonesSent) return;

    zonesSent = json;

    B.setNoSwipeZones(zones);
  }

  w.addEventListener('resize', function () {
    w.requestAnimationFrame(reportZones);
  });

  // =====================================================
  // 9. PUBLIC API FOR KOTLIN
  // =====================================================

  w.VolumeUI = {

    // External volume updates: render only.
    update: function (s) {
      setState(s);

      w.requestAnimationFrame(reportZones);
    },

    // Opacity setting.
    setOpacity: function (v) {
      root.style.setProperty(
        '--bg-alpha',
        String(clamp(Number(v) || 0.9, 0.3, 1))
      );
    },

    // Edge setting.
    setEdge: function (edge) {
      var value = edge === 'left' ? 'left' : 'right';

      root.setAttribute('data-edge', value);
      stage.setAttribute('data-edge', value);
    },

    // Expand from Kotlin.
    expand: function () {
      expandVolumePanel();
    },

    // Collapse from Kotlin.
    collapse: function () {
      collapseVolumePanel();
    },

    // Toggle from Kotlin.
    toggle: function () {
      toggleVolumePanel();
    },

    // Current expanded state.
    isExpanded: function () {
      return expanded;
    }
  };

  // =====================================================
  // 10. INITIALIZATION
  // =====================================================

  setState(B.getState());

  if (d.hidden === false) {
    reportZones();
  }

  d.addEventListener('visibilitychange', function () {
    if (!d.hidden) {
      setState(B.getState());
      reportZones();
    }
  });

})(window, document);
