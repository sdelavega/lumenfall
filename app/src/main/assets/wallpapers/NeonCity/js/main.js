// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

const cityProperties = {
  timeMode: 'live', weatherMode: 'drifting', renderer: 'auto',
  rainIntensity: 60, fogDensity: 30, neonBrightness: 100, vehicleCount: 8,
  locationMode: 'auto', manualLocation: ''
};
const cityChoices = {
  timeMode: ['live', 'night'], weatherMode: ['drifting', 'live', 'fixed'],
  renderer: ['auto', 'classic'], locationMode: ['auto', 'manual']
};
const citySliderBounds = {
  rainIntensity: [0, 120], fogDensity: [0, 100],
  neonBrightness: [20, 120], vehicleCount: [0, 20]
};
const cityPreview = new URLSearchParams(window.location.search);
const cityWeatherSeed = (Math.imul(screen.width, 9301) + Math.imul(screen.height, 49297)) >>> 0;
let cityLayoutSeed = cityWeatherSeed;
try {
  const stored = window.localStorage.getItem('neoncity-v3-seed');
  cityLayoutSeed = stored === null ? window.crypto.getRandomValues(new Uint32Array(1))[0] : Number(stored) >>> 0;
  window.localStorage.setItem('neoncity-v3-seed', String(cityLayoutSeed));
} catch (_) { /* Storage or crypto can be unavailable in imported wallpaper hosts. */ }
if (cityPreview.has('seed')) cityLayoutSeed = Number(cityPreview.get('seed')) >>> 0;
if (['classic', 'canvas'].includes(cityPreview.get('renderer'))) cityProperties.renderer = 'classic';
let cityLiveWeather = null, currentCityState;
let cityRenderer = 'static', cityThreeFailed = false, cityCanvasFailed = false;
let cityThreeInitialized = false, cityDisposePending = false, cityLastFrame = -Infinity;
let citySampleStart = null, citySamples = [], cityMsUnderBudget = 0;
window.NeonCityStatus = { renderer: 'static', lastPaint: 0, failure: null,
  tier: qualityTier({}), renderScale: .75, neonBrightness: 100 };

function refreshCityState() {
  let now = new Date();
  if (cityPreview.has('hour')) {
    const hour = Number(cityPreview.get('hour'));
    if (Number.isFinite(hour)) { now = new Date(now); now.setHours(0, 0, 0, 0); now.setTime(now.getTime() + hour * 3600000); }
  }
  const state = deriveCityState({ now, timeMode: cityProperties.timeMode,
    weatherMode: cityProperties.weatherMode,
    fixed: { rain: cityProperties.rainIntensity, fog: cityProperties.fogDensity },
    live: cityLiveWeather, seed: cityWeatherSeed, vehicleCount: cityProperties.vehicleCount });
  if (CITY_WEATHER[cityPreview.get('weather')]) {
    state.weather = cityPreview.get('weather'); Object.assign(state, CITY_WEATHER[state.weather]);
  }
  if (CITY_PALETTES[cityPreview.get('phase')]) state.phase = cityPreview.get('phase');
  currentCityState = state;
  if (cityRenderer === 'static') applyStaticAtmosphere(state);
  return state;
}

function livelyPropertyListener(name, val) {
  if (!Object.prototype.hasOwnProperty.call(cityProperties, name)) return;
  if (cityChoices[name]) {
    const choices = cityChoices[name], text = String(val).toLowerCase();
    const value = choices.includes(text) ? text : choices[Number(val)];
    cityProperties[name] = name === 'timeMode' && text === 'always night' ? 'night' : value ?? cityProperties[name];
  } else if (citySliderBounds[name]) {
    const number = Number(val);
    if (!Number.isFinite(number)) return;
    const [min, max] = citySliderBounds[name];
    cityProperties[name] = Math.max(min, Math.min(max, number));
  } else {
    cityProperties[name] = String(val);
  }
  if (['weatherMode', 'locationMode', 'manualLocation'].includes(name)) cityWeather.configure(cityProperties);
  refreshCityState();
  if (name === 'renderer') applyRendererPreference();
}

window.setCityLiveWeather = function(weather) { cityLiveWeather = weather; refreshCityState(); };
const cityWeather = createNeonCityWeather(window.setCityLiveWeather);

const cityFrameSystem = {};
function cityAnimationFrame(t) {
  requestAnimationFrame(cityAnimationFrame);
  const status = window.NeonCityStatus;
  const system = window.aetherDesk && window.aetherDesk.system;
  cityFrameSystem.qualityMode = cityPreview.has('quality') ? cityPreview.get('quality') : system && system.qualityMode;
  cityFrameSystem.onBattery = system && system.onBattery;
  cityFrameSystem.isLowPowerMode = system && system.isLowPowerMode;
  const tier = qualityTier(cityFrameSystem);
  if (tier.fps !== status.tier.fps) {
    // A new power tier starts a fresh measurement window.
    citySampleStart = null; citySamples = [];
    cityMsUnderBudget = 0; status.p95FrameMs = undefined;
    status.renderScale = tier.renderScale;
  }
  status.tier = tier;
  status.renderScale = Math.min(status.renderScale, tier.renderScale);
  status.neonBrightness = cityProperties.neonBrightness;
  const interval = 1000 / tier.fps, frameElapsed = t - cityLastFrame, tolerance = 2;
  if (frameElapsed < interval - tolerance) return;
  // WebKit timestamps can be quantized; preserve the deadline even on an early callback.
  cityLastFrame = Number.isFinite(frameElapsed) ? cityLastFrame + Math.floor((frameElapsed + tolerance) / interval) * interval : t;
  if (cityDisposePending) {
    if (typeof disposeThreeCity === 'function') disposeThreeCity();
    cityThreeInitialized = false; cityDisposePending = false;
  }
  if (cityRenderer === 'static') return;
  const start = performance.now(), origin = cityRenderer;
  try {
    if (origin === 'three') renderThreeCity(t, currentCityState);
    else window.ClassicCityRenderer.draw(t, currentCityState, cityProperties);
    status.lastPaint = t;
  } catch (error) {
    demoteRenderer(origin, String(error.message || error));
  }
  if (origin !== 'three' || cityRenderer !== 'three') return;
  const elapsed = Math.max(0, performance.now() - start);
  if (citySampleStart === null) citySampleStart = t;
  citySamples.push(elapsed);
  if (t - citySampleStart >= 10000) {
    citySamples.sort((a, b) => a - b);
    const p95 = citySamples[Math.max(0, Math.ceil(citySamples.length * .95) - 1)];
    status.p95FrameMs = p95;
    cityMsUnderBudget = status.p95FrameMs < 12 ? cityMsUnderBudget + t - citySampleStart : 0;
    const scale = Math.min(tier.renderScale,
      adaptRenderScale(status.renderScale, status.p95FrameMs, cityMsUnderBudget));
    if (scale !== status.renderScale) cityMsUnderBudget = 0;
    status.renderScale = scale;
    citySampleStart = t; citySamples = [];
  }
}

refreshCityState();
applyRendererPreference();
requestAnimationFrame(cityAnimationFrame);
setInterval(refreshCityState, 1000);
