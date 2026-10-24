// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

function selectRenderer(env) {
  if (!env.canvas2d) return 'static';
  return env.rendererPref !== 'classic' && env.webgl ? 'three' : 'canvas';
}

function applyStaticAtmosphere(state) {
  const palette = CITY_PALETTES[state.phase] || CITY_PALETTES.night;
  document.body.style.background = `linear-gradient(to bottom,${palette.sky} 0%,${palette.horizon} 55%,${palette.fog} 100%)`;
}

function showCityRenderer(next) {
  cityRenderer = next;
  window.NeonCityStatus.renderer = next;
  document.getElementById('city-webgl').style.display = next === 'three' ? 'block' : 'none';
  document.getElementById('c').style.display = next === 'canvas' ? 'block' : 'none';
  if (next === 'static') applyStaticAtmosphere(currentCityState);
}

function applyRendererPreference() {
  const next = selectRenderer({ rendererPref: cityProperties.renderer,
    webgl: !!window.NeonCityThree && typeof initializeThreeCity === 'function' && !cityThreeFailed,
    canvas2d: !!window.ClassicCityRenderer && !cityCanvasFailed });
  if (next === 'three' && !cityThreeInitialized) {
    cityThreeInitialized = initializeThreeCity(document.getElementById('city-webgl'), buildCity(cityLayoutSeed));
    if (!cityThreeInitialized) { demoteRenderer('three', 'WebGL initialization failed'); return; }
  }
  showCityRenderer(next);
}

function demoteRenderer(origin, reason) {
  if (origin === 'three') {
    cityThreeFailed = true;
    cityDisposePending = true;
    showCityRenderer(window.ClassicCityRenderer && !cityCanvasFailed ? 'canvas' : 'static');
  } else {
    cityCanvasFailed = true;
    showCityRenderer('static');
  }
  window.NeonCityStatus.failure = reason;
  // A failed frame never delays the first frame from the next renderer.
  cityLastFrame = -Infinity;
}
