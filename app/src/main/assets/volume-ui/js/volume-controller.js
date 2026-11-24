/*
 * UI behaviour: vertical slider, +/- buttons with press-and-hold, category tabs, mute toggle.
 * Direction of data:  user input -> AndroidBridge (Kotlin/AudioManager) -> actual state -> render.
 * Kotlin pushes external changes through VolumeUI.update(); that path only renders and never
 * calls back into Kotlin, so there are no update loops.
 */
(function (w, d) {
  'use strict';

  var B = w.AndroidBridge;
  var CATS = ['media', 'ring', 'alarm'];

  var root = d.getElementById('vc');
  var track = d.getElementById('track');
  var fill = d.getElementById('fill');
  var thumb = d.getElementById('thumb');
  var slider = d.getElementById('slider');
  var percentEl = d.getElementById('percent');
  var muteIcon = d.getElementById('mute-icon');
  var tabs = Array.prototype.slice.call(d.querySelectorAll('.vc-tab'));

  var state = { selected: 'media', cats: {} };
  var dragging = false;
  var pendingPercent = null;
  var rafId = 0;

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function current() { return state.cats[state.selected]; }

  // ---------- rendering ----------
  function drawLevel(percent) {
    fill.style.height = percent + '%';
    thumb.style.bottom = percent + '%';
  }

  function render() {
    var c = current();
    tabs.forEach(function (t) {
      var on = t.getAttribute('data-category') === state.selected;
      t.classList.toggle('active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    if (!c) { return; }
    percentEl.textContent = c.percent;
    slider.setAttribute('aria-valuenow', c.percent);
    muteIcon.src = c.muted ? 'icons/mute.svg' : 'icons/volume.svg';
    root.classList.toggle('is-muted', !!c.muted);
    if (!dragging) { drawLevel(c.percent); }   // never fight the finger while dragging
  }

  function mergeCategory(c) {
    if (c && c.category && CATS.indexOf(c.category) >= 0) {
      state.cats[c.category] = {
        category: c.category, volume: c.volume, max: c.max, percent: c.percent, muted: c.muted
      };
    }
  }

  function setState(s) {
    if (!s) { return; }
    if (s.categories) {
      CATS.forEach(function (k) { if (s.categories[k]) { state.cats[k] = s.categories[k]; } });
    }
    if (s.selected && CATS.indexOf(s.selected) >= 0) { state.selected = s.selected; }
    render();
  }

  function applyResult(result) {          // result of a bridge action = real Android state
    if (!result) { return; }
    mergeCategory(result);
    render();
  }

  // ---------- vertical slider ----------
  function percentFromEvent(e) {
    var r = track.getBoundingClientRect();
    if (r.height <= 0) { return 0; }
    return clamp((1 - (e.clientY - r.top) / r.height) * 100, 0, 100);   // up = more
  }

  function flush() {
    rafId = 0;
    if (pendingPercent === null) { return; }
    var p = pendingPercent;
    pendingPercent = null;
    var result = B.setVolume(state.selected, p);
    if (result) {                          // label shows the ACTUAL Android value
      mergeCategory(result);
      percentEl.textContent = result.percent;
      slider.setAttribute('aria-valuenow', result.percent);
    }
  }

  function moveTo(e) {
    var p = percentFromEvent(e);
    drawLevel(p);                          // fill follows the finger
    pendingPercent = p;
    if (!rafId) { rafId = w.requestAnimationFrame(flush); }
  }

  function endDrag() {
    if (!dragging) { return; }
    if (rafId) { w.cancelAnimationFrame(rafId); }
    flush();
    dragging = false;
    root.classList.remove('dragging');
    B.commitVolume(state.selected);        // lets Kotlin report a genuine failure once
    B.setInteracting(false);
    render();                              // snap to the real value
  }

  track.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    dragging = true;
    root.classList.add('dragging');
    B.setInteracting(true);
    try { track.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
    moveTo(e);
  });
  track.addEventListener('pointermove', function (e) { if (dragging) { e.preventDefault(); moveTo(e); } });
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (n) { track.addEventListener(n, endDrag); });

  // ---------- plus / minus (one real Android step, press-and-hold repeats) ----------
  function bindStepButton(id, direction) {
    var btn = d.getElementById(id);
    var delayTimer = 0, repeatTimer = 0, active = false;

    function step() {
      applyResult(direction > 0 ? B.increaseVolume(state.selected) : B.decreaseVolume(state.selected));
    }
    function stop() {
      if (!active) { return; }
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
      try { btn.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
      B.setInteracting(true);
      step();
      delayTimer = w.setTimeout(function () { repeatTimer = w.setInterval(step, 130); }, 420);
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (n) { btn.addEventListener(n, stop); });
  }
  bindStepButton('btn-plus', +1);
  bindStepButton('btn-minus', -1);

  // ---------- mute / restore ----------
  d.getElementById('btn-mute').addEventListener('click', function () {
    var c = current();
    if (!c) { return; }
    applyResult(c.muted ? B.restoreVolume(state.selected) : B.muteVolume(state.selected));
  });

  // ---------- categories ----------
  tabs.forEach(function (t) {
    t.addEventListener('click', function () {
      var key = t.getAttribute('data-category');
      if (key === state.selected) { return; }
      state.selected = key;
      B.setSelectedCategory(key);          // shared with the notification controller
      setState(B.getState());              // load that stream's actual volume
    });
  });

  // ---------- window controls ----------
  d.getElementById('btn-collapse').addEventListener('click', function () { B.collapseController(); });
  d.getElementById('btn-open').addEventListener('click', function () { B.openApp(); });
  d.getElementById('btn-close').addEventListener('click', function () { B.closeController(); });

  d.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  // ---------- swipe-to-collapse safe zones ----------
  // Kotlin collapses the panel when it is swiped toward its screen edge. The slider and the +/-
  // buttons are excluded so sideways finger drift while adjusting volume never collapses it.
  var zonesSent = '';
  function reportZones() {
    if (!B.available || !w.innerWidth) { return; }      // panel hidden: nothing is laid out yet
    var pad = 10;
    var zones = ['slider', 'btn-plus', 'btn-minus'].map(function (id) {
      var r = d.getElementById(id).getBoundingClientRect();
      return [r.left - pad, r.top - pad, r.right + pad, r.bottom + pad];
    });
    var json = JSON.stringify(zones);
    if (json === zonesSent) { return; }
    zonesSent = json;
    B.setNoSwipeZones(zones);
  }
  w.addEventListener('resize', function () { w.requestAnimationFrame(reportZones); });

  // ---------- API used by Kotlin ----------
  w.VolumeUI = {
    /** External change (hardware keys, notification, dashboard): render only, never call back. */
    update: function (s) { setState(s); w.requestAnimationFrame(reportZones); },
    setOpacity: function (v) {
      root.style.setProperty('--bg-alpha', String(clamp(Number(v) || 0.9, 0.3, 1)));
    },
    setEdge: function (edge) { root.setAttribute('data-edge', edge === 'left' ? 'left' : 'right'); }
  };

  setState(B.getState());
  reportZones();
  d.addEventListener('visibilitychange', function () { if (!d.hidden) { setState(B.getState()); } });
})(window, document);


const stage = document.querySelector(".vc-stage");

let transitionBusy = false;

function expandVolumePanel() {
    if (transitionBusy || stage.classList.contains("is-open")) return;

    transitionBusy = true;

    // Step 1: Transform handle into a glowing circle
    stage.classList.add("is-morphing");

    // Step 2: Expand the glass panel after circle animation
    setTimeout(() => {
        stage.classList.add("is-open");
    }, 320);

    // Allow interaction after transition
    setTimeout(() => {
        stage.classList.remove("is-morphing");
        transitionBusy = false;
    }, 750);
}

function collapseVolumePanel() {
    if (transitionBusy || !stage.classList.contains("is-open")) return;

    transitionBusy = true;

    // Step 1: Shrink panel toward its center
    stage.classList.remove("is-open");

    // Step 2: Restore edge handle
    setTimeout(() => {
        stage.classList.remove("is-morphing");
        transitionBusy = false;
    }, 450);
}
