// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ÆtherDesk-compatible bridge shim. Injected at document start by
// BundleWebView, prefixed by ShimSource.compose with
// `window.__lumenfallInit={properties,fpsCap,suspended}`, which is read once
// and deleted below. Syntax-check with scripts/shim-check.sh.
(function () {
  var _init = window.__lumenfallInit || { properties: {}, fpsCap: 30, suspended: false };
  delete window.__lumenfallInit;
  if (window.aetherDesk) return;
  var native = window.aetherDeskNative;
  var suspended = !!_init.suspended;
  var _visibilityCb = null;
  var _propUpdateCb = null;
    // Properties: the bundle reads initial values via properties[key]
    // or properties.get(), and registers for live updates via
    // properties.onUpdate(cb). ÆtherDesk wallpapers use onUpdate;
    // Lively wallpapers use livelyPropertyListener. Both are supported.
    // The raw values live in _props so onUpdate/get see the latest.
    var _props = _init.properties;
    var _propsObj = {
      get: function() { return _props; },
      onUpdate: function(cb) { _propUpdateCb = cb; }
    };
    // Proxy so `properties[key]` reads through to _props. Wallpaper JS
    // that accesses properties.speed (not .get()) still works.
    _propsObj = new Proxy(_propsObj, {
      get: function(target, prop) {
        if (prop in target) return target[prop];
        return _props[prop];
      },
      ownKeys: function() { return Object.keys(_props); }
    });
    window.aetherDesk = {
    properties: _propsObj,
    system: {
      fpsCap: _init.fpsCap,
      suspend: function () {
        suspended = true;
        _notifyVisibility(false);
      },
      resume: function () {
        suspended = false;
        // A frame dropped while suspended broke the bundle's rAF chain
        // (its callback never ran, so it never re-scheduled). Replay the
        // parked callback so animation resumes.
        if (_parked) { var p = _parked; _parked = null; window.requestAnimationFrame(p); }
        _notifyVisibility(true);
      },
      heartbeat: function () {
        if (native) native.postMessage('{"heartbeat":true}');
      }
    },
    display: {
      width: window.innerWidth,
      height: window.innerHeight,
      dpr: window.devicePixelRatio || 1
    },
    postMessage: function (m) {
      if (native) native.postMessage(typeof m === 'string' ? m : JSON.stringify(m));
    },
    onPropertyChange: function () {},
    onVisibility: function (cb) { _visibilityCb = cb; },
    _setProperty: function (key, value) {
      _props[key] = value;
      try { this.onPropertyChange(key, value); } catch (e) {}
      if (typeof window.livelyPropertyListener === 'function') {
        try { window.livelyPropertyListener(key, value); } catch (e) {}
      }
      if (_propUpdateCb) {
        try { _propUpdateCb(_props); } catch (e) {}
      }
    }
  };

  var _initialReplayDone = false;
  function _replayInitialProperties() {
    if (_initialReplayDone || typeof window.livelyPropertyListener !== 'function') return false;
    _initialReplayDone = true;
    Object.keys(_props).forEach(function (key) {
      try { window.livelyPropertyListener(key, _props[key]); } catch (e) {}
    });
    if (_propUpdateCb) {
      try { _propUpdateCb(_props); } catch (e) {}
    }
    return true;
  }
  function _scheduleInitialReplay() {
    setTimeout(_replayInitialProperties, 0);
    setTimeout(_replayInitialProperties, 100);
    setTimeout(_replayInitialProperties, 500);
  }
  document.addEventListener('DOMContentLoaded', _scheduleInitialReplay);
  window.addEventListener('load', _scheduleInitialReplay);
  var _replayAttempts = 0;
  var _replayTimer = setInterval(function () {
    if (_replayInitialProperties() || ++_replayAttempts >= 50) clearInterval(_replayTimer);
  }, 100);

  function _notifyVisibility(visible) {
    if (_visibilityCb) {
      try { _visibilityCb(visible); } catch (e) {}
    }
  }
  // Fire the initial visibility state so a bundle that registers
  // onVisibility early sees the correct starting state.
  _notifyVisibility(!_init.suspended);

  // Liveness + performance telemetry: report how many rAF frames were
  // delivered since start, every 2 s — frequent enough for
  // PerformanceGovernor to compute an achieved-fps window and react
  // within a few seconds. Also proves the frame loop isn't stalled
  // (setInterval proves timers tick; _frames proves rAF dispatches).
  var _frames = 0;
  setInterval(function () {
    if (suspended) return;
    if (native) native.postMessage('{"tick":true,"frames":' + _frames + '}');
  }, 2000);

  var _origRAF = window.requestAnimationFrame.bind(window);
  var _lastFrame = 0;
  var _parked = null;
  window.requestAnimationFrame = function (cb) {
    var cap = window.aetherDesk.system.fpsCap || _init.fpsCap;
    var interval = 1000.0 / cap;
    // Pace with performance.now() ONLY. The rAF callback timestamp can
    // sit on a different timebase than performance.now() in some WebView
    // hosts (seen on an Activity WebView, Android 17 beta); mixing the
    // two poisons `elapsed` and parks the chain in a years-long
    // setTimeout. Clamp so the wait is never negative or > one interval.
    var delay = interval - (performance.now() - _lastFrame);
    if (!(delay > 0)) delay = 0;
    if (delay > interval) delay = interval;
    function fire() {
      _origRAF(function (ts) {
        if (suspended) { _parked = cb; return; }
        _lastFrame = performance.now();
        _frames++;
        cb(ts);
      });
    }
    if (delay <= 4) { fire(); return 0; }
    setTimeout(fire, delay - 4);
    return 0;
  };

  // --- WebSocket: raw-IP block (shim) + DNS-rebinding validation (native) ---
  // Android's shouldInterceptRequest doesn't cover WebSocket, so the
  // network policy can't reach sockets. The shim rejects raw-IP literals
  // synchronously (no DNS round-trip needed); FQDN sockets go through a
  // BridgeWebSocket proxy that asks the native guard to resolve the
  // host and reject any resolved IP that's private/loopback/metadata
  // (DNS rebinding). The real socket is created only after validation
  // passes; sends are queued until then.
  var _OrigWS = window.WebSocket;
  if (typeof _OrigWS === 'function') {
    var RAW_IP = /^wss?:\/\/(\[|\d{1,3}(\.\d{1,3}){3}([\/:]|$))/i;
    var _pendingWS = {};
    var _wsId = 0;

    function _wsFail(ws, msg) {
      ws.readyState = 3; // CLOSED
      try { if (ws.onerror) ws.onerror({ message: msg, type: 'error' }); } catch(e) {}
    }

    function BridgeWebSocket(url, protocols) {
      this._url = url;
      this._protocols = protocols;
      this._real = null;
      this._sendQueue = [];
      this.url = url;
      this.readyState = 0; // CONNECTING
      this.bufferedAmount = 0;
      this.extensions = '';
      this.protocol = '';
      this.onopen = null;
      this.onmessage = null;
      this.onclose = null;
      this.onerror = null;
      this.binaryType = 'blob';

      if (RAW_IP.test(String(url))) {
        var self = this;
        setTimeout(function() { _wsFail(self, 'WebSocket to raw IP blocked'); }, 0);
        return;
      }

      var id = ++_wsId;
      this._id = id;
      _pendingWS[id] = this;
      try {
        window.aetherDeskWebSocket.validate(String(id), String(url));
      } catch(e) {
        delete _pendingWS[id];
        var s = this;
        setTimeout(function() { _wsFail(s, 'WebSocket bridge unavailable'); }, 0);
      }
    }

    BridgeWebSocket.CONNECTING = 0;
    BridgeWebSocket.OPEN = 1;
    BridgeWebSocket.CLOSING = 2;
    BridgeWebSocket.CLOSED = 3;

    BridgeWebSocket.prototype.send = function(data) {
      if (this._real) {
        this._real.send(data);
      } else if (this.readyState === 0) {
        this._sendQueue.push(data);
      } else {
        throw new Error('InvalidStateError: WebSocket is not open');
      }
    };

    BridgeWebSocket.prototype.close = function(code, reason) {
      if (this._real) this._real.close(code, reason);
      this.readyState = 2; // CLOSING
    };

    window.aetherDesk._wsValidated = function(id, allowed) {
      var ws = _pendingWS[id];
      if (!ws) return;
      delete _pendingWS[id];
      if (!allowed) {
        _wsFail(ws, 'WebSocket rejected by policy (DNS rebinding blocked)');
        return;
      }
      var real;
      try {
        real = (ws._protocols !== undefined)
          ? new _OrigWS(ws._url, ws._protocols)
          : new _OrigWS(ws._url);
      } catch(e) {
        _wsFail(ws, String(e && e.message || e));
        return;
      }
      ws._real = real;
      real.onopen = function(e) {
        ws.readyState = 1;
        ws.protocol = real.protocol;
        if (ws.onopen) ws.onopen(e);
      };
      real.onmessage = function(e) { if (ws.onmessage) ws.onmessage(e); };
      real.onclose = function(e) {
        ws.readyState = 3;
        if (ws.onclose) ws.onclose(e);
      };
      real.onerror = function(e) {
        ws.readyState = 3;
        if (ws.onerror) ws.onerror(e);
      };
      for (var i = 0; i < ws._sendQueue.length; i++) real.send(ws._sendQueue[i]);
      ws._sendQueue.length = 0;
    };

    window.WebSocket = BridgeWebSocket;
    window.WebSocket.prototype = BridgeWebSocket.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function(k) {
      window.WebSocket[k] = BridgeWebSocket[k];
    });
  }

  // --- Native fetch/XHR proxy -------------------------------------------
  // Routes external http(s) fetch() and XMLHttpRequest through native
  // HttpURLConnection, bypassing the WebView's CORS enforcement. The
  // bundle origin (appassets.androidplatform.net) can't read cross-origin
  // responses from external APIs without this — the WebView blocks them
  // even though shouldInterceptRequest let the request through. Same
  // network policy (blocklist + SSRF + budget) applies on the native
  // side; a blocked fetch resolves with status 0 (rejected as a
  // TypeError). Same-origin (appassets) requests go through the original
  // fetch (they're bundle assets served by WebViewAssetLoader).
  var _pendingFetches = {};
  var _fetchId = 0;
  window.aetherDesk._nativeFetchResponse = function(resp) {
    var p = _pendingFetches[resp.id];
    if (!p) return;
    delete _pendingFetches[resp.id];
    if (p.signal && p.abortListener) {
      try { p.signal.removeEventListener('abort', p.abortListener); } catch(e) {}
    }
    if (resp.status === 0) {
      p.reject(new TypeError('Network request failed'));
      return;
    }
    try {
      p.resolve(new Response(resp.body, { status: resp.status }));
    } catch(e) {
      p.reject(e);
    }
  };

  if (typeof window.fetch === 'function') {
    var _origFetch = window.fetch.bind(window);
    window.fetch = function(resource, options) {
      var urlStr = (typeof resource === 'string') ? resource
        : (resource && resource.url) ? resource.url
        : (resource ? String(resource) : '');
      // Only proxy external http(s); same-origin (appassets) and other
      // schemes go through the original fetch.
      if (urlStr.indexOf('http://') === 0 || urlStr.indexOf('https://') === 0) {
        return new Promise(function(resolve, reject) {
          var id = String(++_fetchId);
          _pendingFetches[id] = { resolve: resolve, reject: reject };
          if (options && options.signal) {
            if (options.signal.aborted) {
              delete _pendingFetches[id];
              reject(new DOMException('The operation was aborted.', 'AbortError'));
              return;
            }
            var abortListener = function() {
              delete _pendingFetches[id];
              reject(new DOMException('The operation was aborted.', 'AbortError'));
            };
            options.signal.addEventListener('abort', abortListener, { once: true });
            _pendingFetches[id].abortListener = abortListener;
            _pendingFetches[id].signal = options.signal;
          }
          try {
            var hdrs = null;
            if (options && options.headers) {
              var h = options.headers;
              if (h instanceof Headers) {
                hdrs = {};
                h.forEach(function(v, k) { hdrs[k] = v; });
              } else if (typeof h === 'object') {
                hdrs = JSON.stringify(h);
              }
            }
            window.aetherDeskFetch.request(
              id,
              urlStr,
              (options && options.method) || 'GET',
              (options && options.body != null) ? String(options.body) : null,
              hdrs
            );
          } catch(e) {
            var pending = _pendingFetches[id];
            if (pending && pending.signal && pending.abortListener) {
              try { pending.signal.removeEventListener('abort', pending.abortListener); } catch(ignore) {}
            }
            delete _pendingFetches[id];
            reject(new TypeError('Native fetch bridge unavailable'));
          }
        });
      }
      return _origFetch.apply(window, arguments);
    };
  }

  if (typeof window.XMLHttpRequest === 'function') {
    var _OrigXHR = window.XMLHttpRequest;
    function NativeXHR() {
      this.readyState = 0;
      this.status = 0;
      this.statusText = '';
      this.responseText = '';
      this.response = '';
      this.responseURL = '';
      this.responseType = '';
      this.timeout = 0;
      this.withCredentials = false;
      this._method = 'GET';
      this._url = '';
      this._headers = {};
    }
    NativeXHR.UNSENT = 0; NativeXHR.OPENED = 1;
    NativeXHR.HEADERS_RECEIVED = 2; NativeXHR.LOADING = 3; NativeXHR.DONE = 4;
    NativeXHR.prototype.open = function(method, url, async) {
      this._method = method;
      this._url = url;
      this.readyState = NativeXHR.OPENED;
      if (this.onreadystatechange) { try { this.onreadystatechange(); } catch(e) {} }
    };
    NativeXHR.prototype.setRequestHeader = function(h, v) { this._headers[h] = v; };
    NativeXHR.prototype.send = function(body) {
      var self = this;
      fetch(this._url, {
        method: this._method,
        body: body != null ? String(body) : null,
        headers: this._headers
      }).then(function(resp) {
        return resp.text().then(function(text) {
          return { resp: resp, text: text };
        });
      }).then(function(result) {
        self.status = result.resp.status;
        self.statusText = result.resp.statusText;
        self.responseText = result.text;
        self.response = result.text;
        self.responseURL = result.resp.url || self._url;
        self.readyState = NativeXHR.DONE;
        if (self.onload) { try { self.onload(); } catch(e) {} }
        if (self.onreadystatechange) { try { self.onreadystatechange(); } catch(e) {} }
      }).catch(function(err) {
        self.readyState = NativeXHR.DONE;
        if (self.onerror) { try { self.onerror(err); } catch(e) {} }
        if (self.onreadystatechange) { try { self.onreadystatechange(); } catch(e) {} }
      });
    };
    NativeXHR.prototype.abort = function() {};
    NativeXHR.prototype.getResponseHeader = function() { return null; };
    NativeXHR.prototype.getAllResponseHeaders = function() { return ''; };
    NativeXHR.prototype.overrideMimeType = function() {};
    window.XMLHttpRequest = NativeXHR;
  }

  // --- navigator.geolocation compat ------------------------------------
  // WeatherAether and other location-aware wallpapers use the standard
  // navigator.geolocation API. Proxy it to the bridge's geolocation
  // surface so a wallpaper doesn't need to know about aetherDesk.geo.
  // When geolocation isn't enabled (no toggle / screening-gated), the
  // proxy rejects with POSITION_UNAVAILABLE (code 2), matching the
  // standard browser behavior for "permission denied or unavailable."
  if (typeof navigator !== 'undefined' && !navigator._aetherDeskGeoPatched) {
    var _geoWatchId = 0;
    var _geoWatches = {};
    try {
      Object.defineProperty(navigator, 'geolocation', {
        configurable: true,
        get: function() {
          return {
            getCurrentPosition: function(success, error, opts) {
              var bridge = window.aetherDesk && window.aetherDesk.geolocation;
              if (!bridge) {
                if (error) error({ code: 2, message: 'Geolocation not available' });
                return;
              }
              opts = opts || {};
              bridge.getCurrentPosition({
                enableHighAccuracy: !!opts.enableHighAccuracy,
                timeoutMs: (opts.timeout | 0) || 10000
              }).then(function(pos) {
                success({ coords: {
                  latitude: pos.latitude, longitude: pos.longitude,
                  accuracy: pos.accuracy, altitude: pos.altitude || null,
                  altitudeAccuracy: pos.altitudeAccuracy || null,
                  label: pos.label || null,
                  heading: pos.bearing || null, speed: pos.speed || null
                }, label: pos.label || null, timestamp: pos.timestamp || Date.now() });
              }, function(err) {
                if (error) error({ code: 2, message: err.message || 'unavailable' });
              });
            },
            watchPosition: function(success, error, opts) {
              var id = ++_geoWatchId;
              _geoWatches[id] = setInterval(function() {
                var bridge = window.aetherDesk && window.aetherDesk.geolocation;
                if (!bridge) return;
                bridge.getCurrentPosition({
                  enableHighAccuracy: !!(opts && opts.enableHighAccuracy),
                  timeoutMs: ((opts && opts.timeout) | 0) || 10000
                }).then(function(pos) {
                  success({ coords: {
                    latitude: pos.latitude, longitude: pos.longitude,
                    accuracy: pos.accuracy,
                    label: pos.label || null
                  }, label: pos.label || null, timestamp: pos.timestamp || Date.now() });
                });
              }, 30000);
              return id;
            },
            clearWatch: function(id) {
              if (_geoWatches[id]) { clearInterval(_geoWatches[id]); delete _geoWatches[id]; }
            }
          };
        }
      });
      navigator._aetherDeskGeoPatched = true;
    } catch(e) {}
  }

  // --- Geolocation bridge ---------------------------------------------
  // Promise-based; resolves/rejects via the native aetherDeskGeo
  // surface (registered only when GeolocationPolicy passed). When that
  // surface isn't present (bundle not opted in, or screening-gated),
  // getCurrentPosition rejects 'disabled' — a consistent API that just
  // doesn't work, rather than `undefined`.
  //
  // _geoResolve/_geoReject are hoisted locals so the disabled-path
  // call below can reference them bare (a bare reference to
  // `window.aetherDesk._geoReject` would throw a ReferenceError inside
  // the Promise executor, rejecting with an Error object that
  // JSON.stringifies to {} — hiding the code/message). They're also
  // exposed on window.aetherDesk so the native bridge can call back in
  // via evaluateJavascript.
  var _geoSeq = 0;
  var _geoPending = {};
  function _geoResolve(callId, json) {
    var p = _geoPending[callId]; if (!p) return;
    delete _geoPending[callId];
    try { p.resolve(JSON.parse(json)); }
    catch (e) { p.reject({ code: 'unavailable', message: 'bad payload' }); }
  }
  function _geoReject(callId, code, message) {
    var p = _geoPending[callId]; if (!p) return;
    delete _geoPending[callId];
    p.reject({ code: code, message: message });
  }
  window.aetherDesk.geolocation = {
    getCurrentPosition: function (opts) {
      opts = opts || {};
      var callId = ++_geoSeq;
      return new Promise(function (resolve, reject) {
        _geoPending[callId] = { resolve: resolve, reject: reject };
        var native = window.aetherDeskGeo;
        if (!native) {
          _geoReject(callId, 'disabled', 'geolocation not enabled for this bundle');
          return;
        }
        try {
          native.request(
            String(callId),
            !!opts.enableHighAccuracy,
            (opts.timeoutMs | 0) || 10000
          );
        } catch (e) {
          _geoReject(callId, 'unavailable', String(e && e.message || e));
        }
      });
    }
  };
  window.aetherDesk._geoResolve = _geoResolve;
  window.aetherDesk._geoReject = _geoReject;

  // --- Host-only calls are no-ops --------------------------------------------
  // Bundles written for the macOS ÆtherDesk host may call host methods this
  // host doesn't implement (e.g. reportWeatherRendererStatus), either as
  // window.aetherDesk.host.<name>(...) or window.webkit.messageHandlers.<name>
  // .postMessage(...). Unknown ones resolve to no-ops instead of throwing;
  // anything already defined is left alone.
  var _noop = function () {};
  _noop.postMessage = _noop;
  function _noopTable(base, known) {
    known.forEach(function (k) { if (!(k in base)) base[k] = _noop; });
    return new Proxy(base, {
      get: function (target, prop) {
        if (prop in target) return target[prop];
        if (typeof prop !== 'string' || prop === 'then') return undefined;
        return _noop;
      }
    });
  }
  var _hostMethods = ['reportWeatherRendererStatus'];
  if (!window.aetherDesk.host) window.aetherDesk.host = _noopTable({}, _hostMethods);
  try {
    var _wk = window.webkit || (window.webkit = {});
    _wk.messageHandlers = _noopTable(_wk.messageHandlers || {}, _hostMethods);
  } catch (e) {}

  window.aetherDesk.system.heartbeat();
})();
