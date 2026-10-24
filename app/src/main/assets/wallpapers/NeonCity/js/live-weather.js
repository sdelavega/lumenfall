// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

function mapWmoToCityWeather(code, precipMm) {
  let state = 'clear';
  if ([4, 5, 6, 7, 45, 48].includes(code)) state = 'fog';
  else if (code >= 51 && code <= 55) state = 'drizzle';
  else if ([56, 57, 61, 62, 63, 66, 67, 80, 81].includes(code)) state = 'rain';
  else if ([65, 82].includes(code) || (code >= 95 && code <= 99)) state = 'downpour';
  else if ((code >= 71 && code <= 77) || [85, 86].includes(code)) state = 'snow';
  const defaults = { clear: 0, fog: 0.9, drizzle: 0.2, rain: 0.55, downpour: 1, snow: 0.5 };
  const precipitation = Number(precipMm);
  const intensity = ['drizzle', 'rain', 'downpour', 'snow'].includes(state) &&
    Number.isFinite(precipitation) && precipitation > 0
    ? Math.max(0.05, Math.min(1, precipitation / 7.5)) : defaults[state];
  return { state, intensity };
}

function createNeonCityWeather(onWeather) {
  const state = { props: {}, lat: null, lon: null, locSeq: 0 };
  let generation = 0, fetchSequence = 0, timer = null, enabled = false;
  function notice(message) {
    if (!enabled) return;
    let element = document.getElementById('city-weather-notice');
    if (!element) {
      element = document.createElement('div');
      element.id = 'city-weather-notice';
      element.setAttribute('role', 'status');
      element.style.cssText = 'position:fixed;bottom:18px;left:18px;padding:8px 12px;border-radius:8px;background:#101021cc;color:#e0e3ff;font:13px system-ui;pointer-events:none';
      document.body.appendChild(element);
    }
    element.textContent = message;
    element.hidden = false;
  }
  function hideNotice() {
    const element = document.getElementById('city-weather-notice');
    if (element) element.hidden = true;
  }
  const shared = window.createSharedWeather(state, notice, hideNotice, refreshWeather);
  function schedule(token) {
    if (!enabled || token !== generation) return;
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => refreshWeather(), 10 * 60 * 1000 + Math.random() * 60000);
  }
  function fallback(message) {
    onWeather(null);
    notice(message + ' — using Drifting');
  }
  async function refreshWeather() {
    if (!enabled) return;
    const token = generation, sequence = ++fetchSequence;
    try {
      if (state.lat == null || state.lon == null) {
        const resolved = await shared.resolveLocation();
        if (!enabled || token !== generation || sequence !== fetchSequence) return;
        if (!resolved) {
          const element = document.getElementById('city-weather-notice');
          fallback(element?.textContent || 'Location unavailable');
          return;
        }
      }
      // Extend the copied URL in city glue so shared functions stay verbatim.
      // Fetch uses the host bridge and its network policy when present.
      const url = new URL(shared.buildForecastUrl());
      url.searchParams.set('current', url.searchParams.get('current') + ',precipitation');
      const response = await shared.fetchWithTimeout(url.toString(), 6000);
      if (!response.ok) throw new Error('Weather unavailable');
      const data = await response.json();
      if (!enabled || token !== generation || sequence !== fetchSequence) return;
      if (!data.current || !Number.isFinite(data.current.weather_code)) throw new Error('Missing weather');
      onWeather(mapWmoToCityWeather(data.current.weather_code, data.current.precipitation));
      hideNotice();
    } catch (_) {
      if (enabled && token === generation && sequence === fetchSequence) fallback('Live weather unavailable');
    } finally {
      if (sequence === fetchSequence) schedule(token);
    }
  }
  return {
    configure(props) {
      generation++;
      fetchSequence++;
      state.locSeq++;
      if (timer !== null) clearTimeout(timer);
      timer = null;
      enabled = props.weatherMode === 'live';
      state.props = { locationMode: props.locationMode, manualLocation: props.manualLocation, units: 'metric' };
      state.lat = null;
      state.lon = null;
      onWeather(null);
      hideNotice();
      if (enabled) refreshWeather();
    }
  };
}
