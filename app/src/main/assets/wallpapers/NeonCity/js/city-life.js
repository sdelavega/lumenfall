// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

const CITY_VEHICLE_COUNT = 8;

function signSites(model) {
  const sites = [];
  model.towers.forEach((tower, towerIndex) => {
    if (tower.band === 'far' || (tower.band === 'mid' && towerIndex % 3)) return;
    for (let signIndex = 0; signIndex < (tower.band === 'near' ? 4 : 1); signIndex++) {
      sites.push({ towerIndex, signIndex });
    }
  });
  return sites;
}

function createLife(seed, model) {
  let randomSeed = (seed ^ 0x4c494645) >>> 0;
  const random = () => {
    randomSeed = (Math.imul(randomSeed, 1664525) + 1013904223) >>> 0;
    return randomSeed / 4294967296;
  };
  // Traffic stays in the open canyon. Opposing lanes carry distinct head/tail lights.
  const lanes = [
    { x: -10, y: .6, z0: 145, z1: -620, speed: .022 },
    { x: 10, y: .6, z0: -620, z1: 145, speed: .024 },
    { x: -22, y: 28, z0: 110, z1: -640, speed: .018 },
    { x: 22, y: 42, z0: -640, z1: 110, speed: .017 },
    { x: -29, y: 60, z0: 100, z1: -700, speed: .015 },
    { x: 29, y: 76, z0: -700, z1: 100, speed: .014 }
  ];
  const vehiclePool = [], vehicles = [], signs = [], events = [];
  for (let i = 0; i < 20; i++) {
    const lane = i % lanes.length, phase = random();
    const vehicle = { lane, phase, t: phase, speed: lanes[lane].speed * (.7 + random() * .6) };
    vehiclePool.push(vehicle);
    if (i < CITY_VEHICLE_COUNT) vehicles.push(vehicle);
  }
  for (const site of signSites(model)) {
    signs.push({ ...site, on: true, dead: false, flicker: random() < .12,
      offset: random() * 25, period: 8 + random() * 17 });
  }
  // Choose an exact, dispersed 3% rather than a Bernoulli draw that can miss the bound.
  const order = signs.map((_, i) => i);
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1)), value = order[i];
    order[i] = order[j]; order[j] = value;
  }
  for (let i = 0; i < Math.round(signs.length * .03); i++) {
    signs[order[i]].dead = true; signs[order[i]].on = false;
  }
  // Favor roofs visible from the canyon camera, so restrained events can be seen.
  const rooftop = model.towers.filter(tower => tower.band === 'near').sort((a, b) =>
    (a.h - 44) / (159 - a.z) - (b.h - 44) / (159 - b.z));
  function event(type, duration, interval, delay, tower) {
    events.push({ type, duration, interval, delay, tower, active: false, t: 0,
      starts: 0, lastStart: -Infinity, sinceStart: Infinity, wait: delay });
  }
  event('searchlight', 22, 75, 0, rooftop[0]);
  event('blimp', 100, 900, 0, null);
  for (let i = 0; i < Math.min(6, rooftop.length); i++) {
    event('steam', 14, 32 + random() * 18, i * 4, rooftop[(i * 3 + 1) % rooftop.length]);
  }
  let elapsed = 0, signClock = 0;
  const life = { lanes, vehicles, signs, events, brightness: 1, motionScale: 1, update };
  function update(dt, cityState) {
    dt = Number.isFinite(dt) ? Math.max(0, dt) : 0;
    const tier = (window.NeonCityStatus && window.NeonCityStatus.tier) || CITY_QUALITY.normal;
    const day = cityState.phase === 'day';
    const frequency = tier.eventScale * (day ? .25 : 1);
    life.brightness = cityState.neon;
    life.motionScale = day ? .7 : 1;
    elapsed += dt; signClock += dt * frequency;
    const count = Math.round(cityClamp(cityState.trafficDensity) * tier.countScale *
      (cityState.vehicleCount ?? CITY_VEHICLE_COUNT));
    vehicles.length = count;
    for (let i = 0; i < vehiclePool.length; i++) {
      const vehicle = vehiclePool[i];
      vehicle.phase = (vehicle.phase + dt * vehicle.speed * life.motionScale) % 1;
      // Smooth periodic easing has a quarter-speed tangent at either lane end.
      vehicle.t = vehicle.phase - .75 * Math.sin(vehicle.phase * Math.PI * 2) / (Math.PI * 2);
      if (i < count) vehicles[i] = vehicle;
    }
    for (let i = 0; i < signs.length; i++) {
      const sign = signs[i], cycle = (signClock + sign.offset) % sign.period;
      sign.on = !sign.dead && !(sign.flicker && cycle < .28 && Math.sin(cycle * 85) > -.15);
    }
    for (let i = 0; i < events.length; i++) {
      const e = events[i];
      e.sinceStart += dt; e.wait -= dt * frequency;
      if (e.wait <= 0 && e.sinceStart >= e.interval) {
        e.active = true; e.lastStart = elapsed; e.sinceStart = 0;
        e.starts++; e.wait = e.interval;
      }
      e.active = e.starts > 0 && e.sinceStart < e.duration;
      e.t = e.active ? e.sinceStart / e.duration : 0;
    }
  }
  return life;
}
