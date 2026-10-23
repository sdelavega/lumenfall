// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Rainfall: rain running down a window over a soft background.
//
// Rendering: a small 2D "drop height" canvas is simulated and drawn on the CPU
// every frame, uploaded as a texture, and a WebGL1 fragment shader turns its
// height gradient into refraction (sharp image inside each drop, blurred image
// elsewhere). If WebGL is unavailable a plain Canvas 2D streak renderer is used.
//
// Host contract: reads window.aetherDesk.properties for initial values and
// listens through onUpdate / onPropertyChange / livelyPropertyListener. Frame
// pacing (fpsCap) and suspend/resume are handled by the host's shim, which
// wraps requestAnimationFrame. With no host the wallpaper runs from defaults.
(function () {
  'use strict';

  var DEFAULTS = {
    rainIntensity: 0.6,
    rainSpeed: 1,
    dropSize: 1,
    blur: 8,
    tint: '#8fb7ff',
    lightning: false,
    background: 'dusk-bokeh.webp'
  };
  var RANGES = {
    rainIntensity: [0, 1],
    rainSpeed: [0.2, 3],
    dropSize: [0.5, 2],
    blur: [0, 20]
  };

  // ---------------------------------------------------------------- values

  function clamp(x, lo, hi) { return x < lo ? lo : x > hi ? hi : x; }

  function toNumber(key, value) {
    var n = Number(value);
    if (value === null || value === '' || !isFinite(n)) return DEFAULTS[key];
    return clamp(n, RANGES[key][0], RANGES[key][1]);
  }

  function toBool(value) {
    return value === true || value === 1 || value === 'true' || value === '1';
  }

  // Returns [r, g, b] in 0..1, or null when the text is not a colour.
  function parseColor(value) {
    if (typeof value !== 'string') return null;
    var s = value.trim();
    var m = /^#([0-9a-f]{3})$/i.exec(s);
    if (m) {
      var h = m[1];
      return [parseInt(h[0] + h[0], 16) / 255, parseInt(h[1] + h[1], 16) / 255,
              parseInt(h[2] + h[2], 16) / 255];
    }
    m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(s);
    if (m) {
      var v = m[1];
      return [parseInt(v.slice(0, 2), 16) / 255, parseInt(v.slice(2, 4), 16) / 255,
              parseInt(v.slice(4, 6), 16) / 255];
    }
    m = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/i.exec(s);
    if (m) {
      return [clamp(+m[1], 0, 255) / 255, clamp(+m[2], 0, 255) / 255, clamp(+m[3], 0, 255) / 255];
    }
    return null;
  }

  // The FolderDropdown value arrives either as a bare file name or already
  // prefixed with its folder ("backgrounds/x.webp"). Only the last segment is
  // used, so a value can never point outside the backgrounds folder.
  function backgroundUrl(value) {
    if (typeof value !== 'string') return null;
    var parts = value.split(/[\\/]/);
    var name = parts[parts.length - 1].trim();
    if (!name || name === '.' || name === '..') return null;
    return 'backgrounds/' + encodeURIComponent(name);
  }

  var cfg = {
    rainIntensity: DEFAULTS.rainIntensity,
    rainSpeed: DEFAULTS.rainSpeed,
    dropSize: DEFAULTS.dropSize,
    blur: DEFAULTS.blur,
    tint: parseColor(DEFAULTS.tint),
    lightning: DEFAULTS.lightning,
    background: backgroundUrl(DEFAULTS.background)
  };

  // Applies one property; returns true when the stored value changed.
  function applyProperty(key, value) {
    switch (key) {
      case 'rainIntensity':
      case 'rainSpeed':
      case 'dropSize':
      case 'blur':
        var n = toNumber(key, value);
        if (n === cfg[key]) return false;
        cfg[key] = n;
        if (key === 'blur') onBlurChanged();
        return true;
      case 'tint':
        var c = parseColor(value) || parseColor(DEFAULTS.tint);
        if (c[0] === cfg.tint[0] && c[1] === cfg.tint[1] && c[2] === cfg.tint[2]) return false;
        cfg.tint = c;
        return true;
      case 'lightning':
        var b = toBool(value);
        if (b === cfg.lightning) return false;
        cfg.lightning = b;
        scheduleLightning();
        return true;
      case 'background':
        var url = backgroundUrl(value) || backgroundUrl(DEFAULTS.background);
        if (url === cfg.background) return false;
        cfg.background = url;
        loadBackground();
        return true;
      default:
        return false;
    }
  }

  // ------------------------------------------------------------ background

  var bgSource = null;   // image or canvas currently used as the background
  var bgToken = 0;       // discards results of superseded loads
  var loadedUrl = '';

  function fallbackBackground() {
    var c = document.createElement('canvas');
    c.width = 64;
    c.height = 128;
    var g = c.getContext('2d');
    var grad = g.createLinearGradient(0, 0, 0, 128);
    grad.addColorStop(0, '#181640');
    grad.addColorStop(0.6, '#c45c76');
    grad.addColorStop(1, '#3c2446');
    g.fillStyle = grad;
    g.fillRect(0, 0, 64, 128);
    return c;
  }

  function loadBackground() {
    var raw = cfg.background;
    var url = raw.indexOf('backgrounds/') === 0 ? raw : backgroundUrl(raw);
    if (!url || url === loadedUrl) return;
    loadedUrl = url;
    var token = ++bgToken;
    var img = new Image();
    img.onload = function () {
      if (token !== bgToken) return;
      setBackground(img);
    };
    img.onerror = function () {
      if (token !== bgToken) return;
      setBackground(fallbackBackground());
    };
    img.src = url;
  }

  function setBackground(source) {
    bgSource = source;
    if (renderer) renderer.setBackground(source);
  }

  // Cheap blur: repeated halving with smoothing, then upscaled by the GPU's
  // (or canvas') bilinear filter. Returns src itself when no blur is wanted.
  function blurredCopy(src, blurPx) {
    if (blurPx <= 0) return src;
    var sw = src.naturalWidth || src.width;
    var sh = src.naturalHeight || src.height;
    var factor = 1 + blurPx * 1.6;
    var tw = Math.max(4, Math.round(sw / factor));
    var th = Math.max(4, Math.round(sh / factor));
    var cur = src;
    var cw = sw;
    var ch = sh;
    while (cw > tw * 2 || ch > th * 2) {
      cw = Math.max(tw, Math.round(cw / 2));
      ch = Math.max(th, Math.round(ch / 2));
      cur = drawScaled(cur, cw, ch);
    }
    return drawScaled(cur, tw, th);
  }

  function drawScaled(src, w, h) {
    var c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    var g = c.getContext('2d');
    g.imageSmoothingEnabled = true;
    g.imageSmoothingQuality = 'high';
    g.drawImage(src, 0, 0, w, h);
    return c;
  }

  function onBlurChanged() {
    if (renderer && bgSource) renderer.setBackground(bgSource);
  }

  // Cover mapping: the part of the image shown so it fills the screen.
  function coverScale(srcW, srcH, dstW, dstH) {
    var sa = srcW / srcH;
    var da = dstW / dstH;
    return sa > da ? [da / sa, 1] : [1, sa / da];
  }

  // -------------------------------------------------------------- lightning

  var flashes = [];       // {t, v}: start time in seconds and strength
  var nextStrike = Infinity;

  function nowSeconds() { return performance.now() / 1000; }

  function scheduleLightning() {
    nextStrike = cfg.lightning ? nowSeconds() + 4 + Math.random() * 10 : Infinity;
    if (!cfg.lightning) flashes.length = 0;
  }

  function flashLevel(t) {
    if (t >= nextStrike) {
      flashes = [{ t: t, v: 1 }, { t: t + 0.14, v: 0.65 }];
      if (Math.random() < 0.4) flashes.push({ t: t + 0.4, v: 0.45 });
      nextStrike = t + 7 + Math.random() * 14;
    }
    var level = 0;
    for (var i = 0; i < flashes.length; i++) {
      var f = flashes[i];
      if (t >= f.t) level = Math.max(level, f.v * Math.exp(-(t - f.t) * 9));
    }
    if (flashes.length && t - flashes[flashes.length - 1].t > 2) flashes.length = 0;
    return level;
  }

  // ------------------------------------------------------- drop simulation

  var MAP_W = 224;          // drop-map width in map pixels (heights are drawn here)
  var MAX_DROPS = 520;

  function DropSim() {
    this.w = MAP_W;
    this.h = 480;
    this.drops = [];
    this.spawnCarry = 0;
    this.bigCarry = 0;
    this.sprite = makeDropSprite();
    this.canvas = document.createElement('canvas');
    this.ctx = this.canvas.getContext('2d');
    this.resize(MAP_W, 480);
  }

  DropSim.prototype.resize = function (w, h) {
    this.w = w;
    this.h = h;
    this.canvas.width = w;
    this.canvas.height = h;
  };

  // Height profile of a water cap: sqrt(1 - t^2), drawn as grey levels.
  function makeDropSprite() {
    var n = 48;
    var c = document.createElement('canvas');
    c.width = n;
    c.height = n;
    var g = c.getContext('2d');
    var img = g.createImageData(n, n);
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        var dx = (x + 0.5) / n * 2 - 1;
        var dy = (y + 0.5) / n * 2 - 1;
        var t2 = dx * dx + dy * dy;
        var h = t2 >= 1 ? 0 : Math.sqrt(1 - t2);
        var v = Math.round(h * 255);
        var i = (y * n + x) * 4;
        img.data[i] = v;
        img.data[i + 1] = v;
        img.data[i + 2] = v;
        img.data[i + 3] = 255;
      }
    }
    g.putImageData(img, 0, 0);
    return c;
  }

  DropSim.prototype.addStatic = function (x, y, r, life, strength) {
    if (this.drops.length >= MAX_DROPS) return;
    this.drops.push({ x: x, y: y, r: r, life: life, maxLife: life, a: strength,
                      slide: false, vy: 0, wob: Math.random() * 6.28,
                      phase: 0, timer: 0, trail: 0 });
  };

  DropSim.prototype.addSlider = function (x, y, r) {
    if (this.drops.length >= MAX_DROPS) return;
    this.drops.push({ x: x, y: y, r: r, life: 1e9, maxLife: 1e9, a: 1,
                      slide: true, vy: 0, wob: Math.random() * 6.28,
                      phase: 0, timer: 0, trail: 0 });
  };

  DropSim.prototype.update = function (dt, intensity, speed, size) {
    var drops = this.drops;
    var w = this.w;
    var h = this.h;
    var i;

    // Keep the number of resting droplets proportional to intensity. They live
    // roughly 8 s, so the spawn rate is target / lifetime.
    var area = (w * h) / (MAP_W * 480);
    var target = intensity * 300 * area;
    this.spawnCarry += dt * speed * (target / 8);
    while (this.spawnCarry >= 1) {
      this.spawnCarry -= 1;
      var r = (1.2 + Math.random() * Math.random() * 2.6) * size;
      this.addStatic(Math.random() * w, Math.random() * h, r, 4 + Math.random() * 8, 1);
    }
    // Occasional large drops that run down the glass.
    this.bigCarry += dt * speed * intensity * 1.1;
    while (this.bigCarry >= 1) {
      this.bigCarry -= 1;
      this.addSlider(Math.random() * w, Math.random() * h * 0.7,
                     (3.6 + Math.random() * 2.8) * size);
    }

    var count = drops.length;
    for (i = 0; i < count; i++) {
      var d = drops[i];
      if (!d.slide) {
        d.life -= dt;
        continue;
      }
      // Stick-slip motion: alternate between running and holding.
      d.timer -= dt;
      if (d.timer <= 0) {
        d.phase = d.phase ? 0 : 1;
        d.timer = d.phase ? 0.15 + Math.random() * 0.5 : 0.08 + Math.random() * 0.6;
      }
      var v = d.phase ? (14 + d.r * 9) * speed : 0;
      d.vy += (v - d.vy) * Math.min(1, dt * 10);
      var step = d.vy * dt;
      d.y += step;
      d.wob += dt * 3;
      d.x += Math.sin(d.wob) * 0.6 * dt * (d.vy / 20);
      // Leave a trail of small beads behind, thinning the drop as it goes.
      d.trail += step;
      while (d.trail >= d.r * 0.9) {
        d.trail -= d.r * 0.9;
        this.addStatic(d.x + (Math.random() - 0.5) * 0.4, d.y - d.r * 0.9,
                       Math.max(0.8, d.r * 0.32), 2.5 + Math.random() * 2.5, 0.55);
        d.r *= 0.992;
      }
      if (d.y - d.r > h || d.r < 1.4) { d.life = 0; d.slide = false; continue; }
      // Absorb resting droplets the slider runs into.
      for (var j = 0; j < count; j++) {
        var o = drops[j];
        if (o.slide || o.life <= 0 || o.r < 1.0) continue;
        var dx = o.x - d.x;
        var dy = o.y - d.y;
        var reach = d.r * 0.8 + o.r * 0.5;
        if (dx * dx + dy * dy < reach * reach) {
          d.r = Math.min(8 * size, Math.sqrt(d.r * d.r + 0.4 * o.r * o.r));
          o.life = 0;
        }
      }
    }
    // Compact away everything that has expired.
    var keep = 0;
    for (i = 0; i < drops.length; i++) {
      if (drops[i].slide || drops[i].life > 0) drops[keep++] = drops[i];
    }
    drops.length = keep;
  };

  DropSim.prototype.draw = function () {
    var g = this.ctx;
    g.globalCompositeOperation = 'source-over';
    g.globalAlpha = 1;
    g.fillStyle = '#000';
    g.fillRect(0, 0, this.w, this.h);
    g.globalCompositeOperation = 'lighter';
    var sprite = this.sprite;
    var drops = this.drops;
    for (var i = 0; i < drops.length; i++) {
      var d = drops[i];
      var life = d.slide ? 1 : clamp(d.life, 0, 1);   // shrink away over the last second
      var r = d.r * (d.slide ? 1 : 0.35 + 0.65 * life);
      g.globalAlpha = d.a;
      if (d.slide) {
        g.drawImage(sprite, d.x - r, d.y - r * 1.2, r * 2, r * 2.4);
      } else {
        g.drawImage(sprite, d.x - r, d.y - r, r * 2, r * 2);
      }
    }
    g.globalAlpha = 1;
  };

  // ------------------------------------------------------------ GL renderer

  var VERT = [
    'attribute vec2 aPos;',
    'varying vec2 vUv;',
    'void main() {',
    '  vUv = aPos * 0.5 + 0.5;',
    '  gl_Position = vec4(aPos, 0.0, 1.0);',
    '}'
  ].join('\n');

  var FRAG = [
    '#ifdef GL_FRAGMENT_PRECISION_HIGH',
    'precision highp float;',
    '#else',
    'precision mediump float;',
    '#endif',
    'varying vec2 vUv;',
    'uniform sampler2D uBg;',
    'uniform sampler2D uBlur;',
    'uniform sampler2D uDrops;',
    'uniform vec2 uCover;',
    'uniform vec2 uTexel;',
    'uniform vec2 uBlurTexel;',
    'uniform vec3 uTint;',
    'uniform float uFlash;',
    '',
    'vec3 softBg(vec2 uv) {',
    '  vec3 c = texture2D(uBlur, uv).rgb * 0.4;',
    '  c += texture2D(uBlur, uv + uBlurTexel).rgb * 0.15;',
    '  c += texture2D(uBlur, uv - uBlurTexel).rgb * 0.15;',
    '  c += texture2D(uBlur, uv + vec2(uBlurTexel.x, -uBlurTexel.y)).rgb * 0.15;',
    '  c += texture2D(uBlur, uv + vec2(-uBlurTexel.x, uBlurTexel.y)).rgb * 0.15;',
    '  return c;',
    '}',
    '',
    'void main() {',
    '  float h = texture2D(uDrops, vUv).r;',
    '  float hx = texture2D(uDrops, vUv + vec2(uTexel.x, 0.0)).r',
    '           - texture2D(uDrops, vUv - vec2(uTexel.x, 0.0)).r;',
    '  float hy = texture2D(uDrops, vUv + vec2(0.0, uTexel.y)).r',
    '           - texture2D(uDrops, vUv - vec2(0.0, uTexel.y)).r;',
    '  vec2 n = vec2(hx, hy);',
    '  float nl = length(n);',
    '  if (nl > 1.0) n /= nl;',
    '  float mask = smoothstep(0.03, 0.22, h);',
    '',
    '  vec2 bgUv = (vUv - 0.5) * uCover + 0.5;',
    '  // A water drop acts as a small lens: the image inside is sharp and flipped.',
    '  vec2 look = bgUv - n * 0.18 * uCover * mask;',
    '  vec3 sharp = texture2D(uBg, clamp(look, 0.0, 1.0)).rgb;',
    '  vec3 col = mix(softBg(bgUv), sharp, mask);',
    '',
    '  // Bright glint on the upper-left of each drop and a darker rim.',
    '  vec3 N = normalize(vec3(-n * 2.5, 1.0));',
    '  float spec = pow(max(dot(N, normalize(vec3(-0.45, 0.55, 0.7))), 0.0), 36.0);',
    '  col += mask * spec * (0.55 + uFlash) * mix(vec3(1.0), uTint, 0.35);',
    '  col *= 1.0 - mask * smoothstep(0.25, 1.0, nl) * 0.3;',
    '',
    '  col = mix(col, col * uTint * 1.35, 0.2);',
    '  col = col * (1.0 + uFlash * 0.9) + uFlash * 0.1 * vec3(0.7, 0.8, 1.0);',
    '  gl_FragColor = vec4(col, 1.0);',
    '}'
  ].join('\n');

  function compile(gl, type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      var log = gl.getShaderInfoLog(s);
      gl.deleteShader(s);
      throw new Error('shader: ' + log);
    }
    return s;
  }

  function GLRenderer(canvas) {
    var opts = { alpha: false, antialias: false, depth: false, stencil: false,
                 powerPreference: 'low-power' };
    var gl = canvas.getContext('webgl', opts) || canvas.getContext('experimental-webgl', opts);
    if (!gl) throw new Error('no webgl');
    this.gl = gl;
    this.canvas = canvas;
    this.sim = new DropSim();

    var prog = gl.createProgram();
    gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('link: ' + gl.getProgramInfoLog(prog));
    }
    gl.useProgram(prog);
    this.prog = prog;

    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var loc = gl.getAttribLocation(prog, 'aPos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

    var names = ['uBg', 'uBlur', 'uDrops', 'uCover', 'uTexel', 'uBlurTexel', 'uTint', 'uFlash'];
    this.u = {};
    for (var i = 0; i < names.length; i++) this.u[names[i]] = gl.getUniformLocation(prog, names[i]);
    gl.uniform1i(this.u.uBg, 0);
    gl.uniform1i(this.u.uBlur, 1);
    gl.uniform1i(this.u.uDrops, 2);

    this.texBg = this.makeTexture();
    this.texBlur = this.makeTexture();
    this.texDrops = this.makeTexture();
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);

    this.hasBg = false;
    this.srcW = 1;
    this.srcH = 1;
    this.blurW = 1;
    this.blurH = 1;
    this.cover = [1, 1];
    this.resize();
  }

  GLRenderer.prototype.makeTexture = function () {
    var gl = this.gl;
    var t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return t;
  };

  GLRenderer.prototype.upload = function (unit, tex, source) {
    var gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
  };

  GLRenderer.prototype.setBackground = function (source) {
    var blurred = blurredCopy(source, cfg.blur);
    try {
      this.upload(0, this.texBg, source);
      this.upload(1, this.texBlur, blurred);
    } catch (e) {
      // e.g. a tainted image when opened from file://: use the built-in gradient.
      if (source !== bgSource || source._isFallback) return;
      var fb = fallbackBackground();
      fb._isFallback = true;
      bgSource = fb;
      this.setBackground(fb);
      return;
    }
    this.srcW = source.naturalWidth || source.width;
    this.srcH = source.naturalHeight || source.height;
    this.blurW = blurred.naturalWidth || blurred.width;
    this.blurH = blurred.naturalHeight || blurred.height;
    this.hasBg = true;
    this.updateCover();
  };

  GLRenderer.prototype.updateCover = function () {
    this.cover = coverScale(this.srcW, this.srcH, this.canvas.width, this.canvas.height);
  };

  GLRenderer.prototype.resize = function () {
    var gl = this.gl;
    var w = this.canvas.width;
    var h = this.canvas.height;
    gl.viewport(0, 0, w, h);
    var mh = clamp(Math.round(MAP_W * h / w), 128, 720);
    this.sim.resize(MAP_W, mh);
    this.updateCover();
  };

  GLRenderer.prototype.draw = function (dt, t) {
    var gl = this.gl;
    if (gl.isContextLost() || !this.hasBg) return;
    this.sim.update(dt, cfg.rainIntensity, cfg.rainSpeed, cfg.dropSize);
    this.sim.draw();
    this.upload(2, this.texDrops, this.sim.canvas);
    var u = this.u;
    gl.uniform2f(u.uCover, this.cover[0], this.cover[1]);
    gl.uniform2f(u.uTexel, 1 / this.sim.w, 1 / this.sim.h);
    gl.uniform2f(u.uBlurTexel, 1.5 / this.blurW, 1.5 / this.blurH);
    gl.uniform3f(u.uTint, cfg.tint[0], cfg.tint[1], cfg.tint[2]);
    gl.uniform1f(u.uFlash, flashLevel(t));
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  };

  // ------------------------------------------------------- 2D fallback

  function CanvasRenderer(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    if (!this.ctx) throw new Error('no 2d context');
    this.bg = null;
    this.streaks = [];
    this.carry = 0;
  }

  CanvasRenderer.prototype.setBackground = function (source) {
    this.bg = blurredCopy(source, cfg.blur);
  };

  CanvasRenderer.prototype.resize = function () {};

  CanvasRenderer.prototype.draw = function (dt, t) {
    var g = this.ctx;
    var w = this.canvas.width;
    var h = this.canvas.height;
    if (!this.bg) return;
    var bw = this.bg.naturalWidth || this.bg.width;
    var bh = this.bg.naturalHeight || this.bg.height;
    var cv = coverScale(bw, bh, w, h);
    var sw = bw * cv[0];
    var sh = bh * cv[1];
    g.imageSmoothingEnabled = true;
    g.drawImage(this.bg, (bw - sw) / 2, (bh - sh) / 2, sw, sh, 0, 0, w, h);
    var tint = cfg.tint;
    var rgb = Math.round(tint[0] * 255) + ',' + Math.round(tint[1] * 255) + ',' +
              Math.round(tint[2] * 255);
    g.fillStyle = 'rgba(' + rgb + ',0.08)';
    g.fillRect(0, 0, w, h);

    var unit = h / 800;
    var count = Math.round(cfg.rainIntensity * 140);
    this.carry += dt * cfg.rainSpeed * count * 1.4;
    while (this.carry >= 1 && this.streaks.length < 400) {
      this.carry -= 1;
      this.streaks.push({ x: Math.random() * w, y: -20 * unit,
                          len: (14 + Math.random() * 30) * unit,
                          v: (500 + Math.random() * 500) * unit });
    }
    this.carry = Math.min(this.carry, 1);
    g.strokeStyle = 'rgba(' + rgb + ',0.4)';
    g.lineWidth = Math.max(1, 1.2 * cfg.dropSize * unit);
    g.lineCap = 'round';
    g.beginPath();
    var list = this.streaks;
    for (var i = list.length - 1; i >= 0; i--) {
      var s = list[i];
      s.y += s.v * dt * cfg.rainSpeed;
      s.x -= s.v * dt * cfg.rainSpeed * 0.12;
      if (s.y - s.len > h) { list[i] = list[list.length - 1]; list.pop(); continue; }
      g.moveTo(s.x, s.y);
      g.lineTo(s.x + s.len * 0.12, s.y - s.len);
    }
    g.stroke();

    var f = flashLevel(t);
    if (f > 0.01) {
      g.fillStyle = 'rgba(230,236,255,' + (f * 0.45).toFixed(3) + ')';
      g.fillRect(0, 0, w, h);
    }
  };

  // ----------------------------------------------------------------- loop

  var stage = document.getElementById('stage');
  var renderer = null;
  var visible = true;
  var scheduled = false;
  var lastTime = 0;

  function sizeCanvas() {
    var dpr = Math.min(window.devicePixelRatio || 1, 1.25);
    var w = Math.max(1, Math.round((window.innerWidth || 360) * dpr));
    var h = Math.max(1, Math.round((window.innerHeight || 640) * dpr));
    if (stage.width !== w) stage.width = w;
    if (stage.height !== h) stage.height = h;
  }

  function freshCanvas() {
    var c = document.createElement('canvas');
    c.id = 'stage';
    stage.parentNode.replaceChild(c, stage);
    stage = c;
    stage.addEventListener('webglcontextlost', onContextLost, false);
    stage.addEventListener('webglcontextrestored', onContextRestored, false);
    return c;
  }

  function createRenderer() {
    sizeCanvas();
    try {
      renderer = new GLRenderer(stage);
    } catch (e) {
      // A canvas that failed WebGL setup cannot give a 2D context reliably.
      renderer = new CanvasRenderer(freshCanvas());
      sizeCanvas();
    }
    if (bgSource) renderer.setBackground(bgSource);
  }

  function onContextLost(ev) {
    ev.preventDefault();
    renderer = null;
  }

  function onContextRestored() {
    createRenderer();
  }

  function frame() {
    scheduled = false;
    if (!visible) return;
    var now = nowSeconds();
    var dt = lastTime ? Math.min(0.1, now - lastTime) : 1 / 30;
    lastTime = now;
    if (renderer) {
      try {
        renderer.draw(dt, now);
      } catch (e) {
        // Keep the loop alive; one bad frame must not stop the wallpaper.
      }
    }
    kick();
  }

  function kick() {
    if (scheduled) return;
    scheduled = true;
    window.requestAnimationFrame(frame);
  }

  function setVisible(v) {
    visible = !!v;
    if (visible) {
      lastTime = 0;
      kick();
    }
  }

  // ----------------------------------------------------------------- host

  var host = window.aetherDesk;

  function applyAll(props) {
    if (!props) return;
    for (var key in DEFAULTS) {
      if (Object.prototype.hasOwnProperty.call(DEFAULTS, key) && props[key] !== undefined) {
        applyProperty(key, props[key]);
      }
    }
  }

  function readInitial() {
    if (!host || !host.properties) return;
    var p = host.properties;
    var src = typeof p.get === 'function' ? p.get() : p;
    applyAll(src);
  }

  function connect() {
    if (!host) return;
    var p = host.properties;
    if (p && typeof p.onUpdate === 'function') p.onUpdate(applyAll);
    host.onPropertyChange = function (key, value) { applyProperty(key, value); };
    if (typeof host.onVisibility === 'function') host.onVisibility(setVisible);
  }

  window.livelyPropertyListener = function (name, value) { applyProperty(name, value); };

  document.addEventListener('visibilitychange', function () {
    setVisible(!document.hidden);
  });
  window.addEventListener('resize', function () {
    sizeCanvas();
    if (renderer) renderer.resize();
  });

  readInitial();
  connect();
  scheduleLightning();
  createRenderer();
  loadBackground();
  kick();
})();
