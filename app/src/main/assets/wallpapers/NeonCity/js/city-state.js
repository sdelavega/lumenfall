// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

const CITY_QUALITY = {
  saver: { fps: 15, renderScale: .5, countScale: .4, eventScale: .5 },
  normal: { fps: 30, renderScale: .75, countScale: 1, eventScale: 1 }
};
function qualityTier(system) {
  return system.qualityMode === 'eco' || system.isLowPowerMode || system.onBattery ?
    CITY_QUALITY.saver : CITY_QUALITY.normal;
}

function adaptRenderScale(current, p95FrameMs, msUnderBudget) {
  const scales = [.5, .6, .75];
  const index = scales.indexOf(current);
  if (p95FrameMs > 20) return scales[Math.max(0, index - 1)];
  if (p95FrameMs < 12 && msUnderBudget >= 60000) return scales[Math.min(scales.length - 1, index + 1)];
  return current;
}
const CITY_PALETTES = {
  night: { sky: '#02050e', horizon: '#102342', fog: '#071122', light: '#89b8dd', ambient: 0.8 },
  dawn: { sky: '#092347', horizon: '#eaa259', fog: '#6d5039', light: '#f5b982', ambient: 1.4 },
  day: { sky: '#334b65', horizon: '#889897', fog: '#566c7c', light: '#c0d6df', ambient: 2.5 },
  dusk: { sky: '#07182f', horizon: '#d57c38', fog: '#67442d', light: '#eea073', ambient: 1.2 }
};

const CITY_WEATHER = {
  clear: { rain: 0, fog: 0.08 },
  drizzle: { rain: 0.2, fog: 0.22 },
  rain: { rain: 0.55, fog: 0.3 },
  downpour: { rain: 1, fog: 0.55 },
  fog: { rain: 0, fog: 0.9 },
  snow: { rain: 0, fog: 0.25 }
};
const CITY_DWELL = 30 * 60 * 1000;
const CITY_TRANSITION = 3 * 60 * 1000;
const cityClamp = value => Math.max(0, Math.min(1, Number(value) || 0));
const cityMix = (a, b, blend) => a + (b - a) * blend;

function cityWeatherAt(seed, slot) {
  // Hash each slot independently so seeking/reloading never changes the schedule.
  let value = (seed | 0) ^ Math.imul(slot | 0, 0x9e3779b1);
  value = Math.imul(value ^ (value >>> 16), 0x85ebca6b);
  value = Math.imul(value ^ (value >>> 13), 0xc2b2ae35);
  const draw = ((value ^ (value >>> 16)) >>> 0) / 4294967296;
  const winter = date => [11, 0, 1].includes(date.getMonth());
  // Require the entire dwell to be winter, including in quarter-hour time zones.
  const maySnow = winter(new Date(slot * CITY_DWELL)) &&
    winter(new Date((slot + 1) * CITY_DWELL - 1));
  if (maySnow && draw < 0.08) return 'snow';
  const ordinary = maySnow ? (draw - 0.08) / 0.92 : draw;
  return ['clear', 'drizzle', 'rain', 'downpour', 'fog'][Math.floor(ordinary * 5)];
}

function driftingWeather(seed, now) {
  const time = now.getTime();
  const slot = Math.floor(time / CITY_DWELL);
  const state = cityWeatherAt(seed, slot);
  const previous = CITY_WEATHER[cityWeatherAt(seed, slot - 1)];
  const target = CITY_WEATHER[state];
  const progress = cityClamp((time - slot * CITY_DWELL) / CITY_TRANSITION);
  const blend = progress * progress * (3 - 2 * progress);
  return { state, rain: cityMix(previous.rain, target.rain, blend),
    fog: cityMix(previous.fog, target.fog, blend) };
}

function deriveCityState(input) {
  const { now, timeMode, weatherMode, fixed, live, seed } = input;
  const hour = timeMode === 'night' ? 3 :
    now.getHours() + now.getMinutes() / 60 + now.getSeconds() / 3600 + now.getMilliseconds() / 3600000;
  let phase, phaseBlend;
  if (hour >= 21 || hour < 5) {
    phase = 'night'; phaseBlend = ((hour + 3) % 24) / 8;
  } else if (hour < 7) {
    phase = 'dawn'; phaseBlend = (hour - 5) / 2;
  } else if (hour < 18) {
    phase = 'day'; phaseBlend = (hour - 7) / 11;
  } else {
    phase = 'dusk'; phaseBlend = (hour - 18) / 3;
  }
  let weather;
  if (weatherMode === 'fixed') {
    const rain = cityClamp(fixed.rain / 120), fog = cityClamp(fixed.fog / 100);
    weather = { state: rain > 0.8 ? 'downpour' : rain > 0.3 ? 'rain' : rain > 0 ? 'drizzle' : fog > 0.5 ? 'fog' : 'clear', rain, fog };
  } else if (weatherMode === 'live' && live && CITY_WEATHER[live.state]) {
    const traits = CITY_WEATHER[live.state];
    weather = { state: live.state, rain: traits.rain ? cityClamp(live.intensity) : 0,
      fog: live.state === 'fog' ? cityClamp(live.intensity) : traits.fog };
  } else {
    weather = driftingWeather(seed, now);
  }
  const neon = phase === 'night' ? 1 : phase === 'day' ? 0.35 :
    phase === 'dawn' ? cityMix(1, 0.35, phaseBlend) : cityMix(0.35, 1, phaseBlend);
  const windowsLit = phase === 'night' ? cityMix(1, 0.3, phaseBlend) :
    phase === 'dawn' ? cityMix(0.3, 0.1, phaseBlend) : phase === 'day' ? 0.1 : cityMix(0.1, 1, phaseBlend);
  const trafficDensity = phase === 'night' ? 1 : phase === 'day' ? 0.55 :
    phase === 'dawn' ? cityMix(1, 0.55, phaseBlend) : cityMix(0.55, 1, phaseBlend);
  return { phase, phaseBlend, weather: weather.state, rain: weather.rain, fog: weather.fog,
    neon, windowsLit, trafficDensity,
    vehicleCount: Math.max(0, Math.min(20, Number(input.vehicleCount ?? 8))) };
}
