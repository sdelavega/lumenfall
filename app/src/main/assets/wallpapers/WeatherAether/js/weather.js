'use strict';

// ── fetchWithTimeout() ───────────────────────────────────────────────────────
// Wraps fetch() with an abort timeout. Prefers AbortSignal.timeout() (Safari
// 15.4+) for a single-expression path; falls back to AbortController for older
// WebKit versions bundled with macOS 12.
//
// IMPORTANT (AetherDesk-specific): the native fetch bridge that routes HTTP
// requests through URLSession does NOT propagate the abort signal — it ignores
// the {signal} option. This means the timeout does not abort an in-flight
// URLSession task, but it also does not cause the Promise to hang: the native
// bridge resolves its Promise independently when the network call completes.
// The effective timeout for network calls is the URLSession task timeout (30s).
function fetchWithTimeout(url,ms){
  if(typeof AbortSignal!=='undefined'&&typeof AbortSignal.timeout==='function'){
    return fetch(url,{signal:AbortSignal.timeout(ms)});
  }
  var ctrl=new AbortController();
  setTimeout(function(){ctrl.abort();},ms);
  return fetch(url,{signal:ctrl.signal});
}


// ── localStorage cache helpers ────────────────────────────────────────────────
// Weather data and location are cached in localStorage so the wallpaper can
// display something immediately on load before any API calls complete. The
// weather cache key includes lat/lon so each location has an independent entry.
//
// cKey()            → storage key for current location's weather data, or null
// cacheWeather(d)   → serialise and store the API response
// loadCachedWeather()→ parse and return cached data, or null
// cacheLocation()   → store {lat, lon, label} for use on the next startup
// loadCachedLocation()→ restore last location on startup before geolocation runs
function cKey(){
  if(S.lat!=null&&S.lon!=null)return'wa_'+S.lat.toFixed(2)+'_'+S.lon.toFixed(2);
  return null;
}
function cacheWeather(d){
  var k=cKey();if(k){d._units=S.props.units;try{localStorage.setItem(k,JSON.stringify(d));}catch(e){}}
}
function loadCachedWeather(){
  var k=cKey();if(!k)return null;
  try{var d=localStorage.getItem(k);return d?JSON.parse(d):null;}catch(e){return null;}
}
function cacheLocation(){
  if(S.lat!=null&&S.lon!=null)try{localStorage.setItem('wa.lastLoc',JSON.stringify({lat:S.lat,lon:S.lon,label:S.label}));}catch(e){}
}
function loadCachedLocation(){
  try{var d=localStorage.getItem('wa.lastLoc');return d?JSON.parse(d):null;}catch(e){return null;}
}


// ── resolveLocation() ────────────────────────────────────────────────────────
// Entry point for location resolution. Dispatches to geocodeManual() for a
// typed location or autoLocation() for device/IP-based detection.
// Returns a Promise<boolean> — true if location was resolved successfully.
//
// Sequence guard: init() starts resolving with the default (auto) properties
// before the host injects saved ones, so an earlier resolution can still be in
// flight — e.g. a CoreLocation fix arriving after the injected manual location
// has geocoded. Every step checks `seq` and drops its result once a newer
// resolution has started, so a stale result never overwrites S.lat/S.lon/S.label.
function resolveLocation(){
  var seq=++S.locSeq;
  if(S.props.locationMode==='manual')return geocodeManual(S.props.manualLocation,seq);
  return autoLocation(seq);
}


// ── autoLocation() ───────────────────────────────────────────────────────────
// Attempts device geolocation via the native CoreLocation bridge, then falls
// back to IP-based geolocation (ipapi.co) if CoreLocation fails or is denied.
//
// IMPORTANT: the native geolocation bridge ignores the {timeout:4000} option
// passed to getCurrentPosition(). A manual 5-second timer (geoTimer) is used
// to guarantee the Promise always settles — without it, a hanging CoreLocation
// request would prevent refreshWeather() from ever being called, leaving stale
// localStorage data on screen indefinitely.
//
// Fallback chain:
//   CoreLocation (geoTimer 5s) → reverseGeocode (BigDataCloud) → cache + resolve
//                 ↓ (on CoreLocation failure)
//   fetchIP (ipapi.co, 6s) → loadCachedLocation()
function autoLocation(seq){
  return new Promise(function(resolve){
    var done=false;
    var geoTimer=setTimeout(function(){
      if(done)return;done=true;
      fetchIP(seq).then(function(ok){resolve(ok);});
    },5000);
    if(navigator.geolocation){
      navigator.geolocation.getCurrentPosition(
        function(p){
          // Accept CoreLocation coordinates even if geoTimer already fired
          // (e.g., the system auth dialog took longer than 5 seconds). When
          // the Promise is already settled (alreadyResolved=true), skip
          // resolve() and trigger a weather refresh directly instead.
          var alreadyResolved=done;
          done=true;clearTimeout(geoTimer);
          if(seq!==S.locSeq){if(!alreadyResolved)resolve(false);return;}
          S.lat=p.coords.latitude;S.lon=p.coords.longitude;
          reverseGeocode(S.lat,S.lon).then(function(label){
            if(seq!==S.locSeq){if(!alreadyResolved)resolve(false);return;}
            S.label=label;cacheLocation();
            if(!alreadyResolved){resolve(true);}
            else{refreshWeather();}
          });
        },
        function(){if(done)return;done=true;clearTimeout(geoTimer);fetchIP(seq).then(function(ok){resolve(ok);});},
        {timeout:4000}
      );
    }else{clearTimeout(geoTimer);fetchIP(seq).then(function(ok){resolve(ok);});}
  });
}



// ── Host location capabilities ───────────────────────────────────────────────
// Standalone/Lively hosts use device coordinates and cache only. The macOS
// bridge explicitly enables IP lookup and BigDataCloud place labels.
function hasHostCapability(name){
  var system=window.aetherDesk&&window.aetherDesk.system;
  return !!(system&&system.capabilities&&system.capabilities[name]===true);
}

function coordinateLabel(lat,lon){
  return typeof lat==='number'&&isFinite(lat)&&typeof lon==='number'&&isFinite(lon)
    ?lat.toFixed(2)+', '+lon.toFixed(2):'Current location';
}

function reverseGeocode(lat,lon){
  if(!hasHostCapability('ipGeolocation'))return Promise.resolve(coordinateLabel(lat,lon));
  var url='https://api.bigdatacloud.net/data/reverse-geocode-client'
        +'?latitude='+lat+'&longitude='+lon+'&localityLanguage=en';
  return fetchWithTimeout(url,6000)
    .then(function(r){return r.ok?r.json():null;})
    .then(function(d){
      if(!d)return'Current location';
      // 'city' is the preferred field; 'locality' is a finer-grained fallback
      // (neighbourhood/suburb level) used when city is absent.
      var city=d.city||d.locality||'';
      // principalSubdivisionCode is the short state/province code (e.g. "CA").
      var state=d.principalSubdivisionCode||'';
      var label=(city+(state?', '+state:'')).trim();
      return label||'Current location';
    })
    .catch(function(){return'Current location';});
}


// ── fetchIP() ────────────────────────────────────────────────────────────────
// Resolves location from the client's public IP via ipapi.co. Used when
// CoreLocation is unavailable or denied. Constructs a human-readable label
// from city + region + country fields. Falls through to fallbackLoc() (last
// cached location) if the request fails or returns incomplete data.
function fetchIP(seq){
  if(!hasHostCapability('ipGeolocation'))return Promise.resolve(fallbackLoc(seq));
  return fetchWithTimeout('https://ipapi.co/json/',6000)
    .then(function(r){return r.json();})
    .then(function(d){
      if(seq!==S.locSeq)return false;
      if(d.latitude!=null&&d.longitude!=null){
        S.lat=d.latitude;S.lon=d.longitude;
        S.label=((d.city||'Unknown')+(d.region_code?', '+d.region_code:'')).trim();
        if(!S.label)S.label=d.city||'Unknown';
        cacheLocation();return true;
      }
      return fallbackLoc(seq);
    })
    .catch(function(){return fallbackLoc(seq);});
}


// ── fallbackLoc() ────────────────────────────────────────────────────────────
// Restores the cached location after device/IP lookup fails. Without the
// host capability, auto mode stops here and asks the user to set a city.
// The macOS host retains its configured manual-city fallback. Returns a
// boolean or Promise<boolean>; both are safe in a Promise .catch() handler.
function fallbackLoc(seq){
  if(seq!==S.locSeq)return false;
  var loc=loadCachedLocation();
  if(loc){
    S.lat=loc.lat;S.lon=loc.lon;
    S.label=hasHostCapability('ipGeolocation')?loc.label:coordinateLabel(loc.lat,loc.lon);
    return true;
  }
  if(!hasHostCapability('ipGeolocation')){
    showPill("Set a city in this wallpaper's settings");
    return false;
  }
  if(S.props.manualLocation&&S.props.manualLocation.trim()){
    return geocodeManual(S.props.manualLocation,seq);
  }
  return false;
}


// ── geocodeManual() ──────────────────────────────────────────────────────────
// Resolves a human-typed location string (e.g. "Charlotte, NC") to lat/lon
// using the Open-Meteo geocoding API. Free, no key required. Shows an error
// pill if the location string isn't recognised. Called when locationMode is
// 'manual' or when the manualLocation property changes.
function geocodeManual(q,seq){
  if(!q||!q.trim()){showPill("couldn't find ''");return Promise.resolve(false);}
  var url=new URL('https://geocoding-api.open-meteo.com/v1/search');
  url.searchParams.set('name',q.trim());
  url.searchParams.set('count','1');
  url.searchParams.set('language','en');
  return fetchWithTimeout(url.toString(),6000)
    .then(function(r){return r.json();})
    .then(function(d){
      if(seq!==S.locSeq)return false;
      if(!d.results||d.results.length===0){showPill("couldn't find '"+q+"'");return false;}
      var r0=d.results[0];
      S.lat=r0.latitude;S.lon=r0.longitude;
      S.label=r0.name+(r0.admin1?', '+r0.admin1:'');
      cacheLocation();hidePill();return true;
    })
    .catch(function(){if(seq===S.locSeq)showPill("couldn't find '"+q+"'");return false;});
}


// ── buildForecastUrl() ───────────────────────────────────────────────────────
// Constructs the Open-Meteo forecast API URL for the current location and unit
// system. Requests 5 days of daily data plus current conditions.
//
// The _t parameter (current minute epoch) busts any CDN or network-level cache
// so each call fetches truly fresh data. Without it, a caching intermediary
// could serve yesterday's response for hours.
function buildForecastUrl(){
  var url=new URL('https://api.open-meteo.com/v1/forecast');
  url.searchParams.set('latitude',String(S.lat));
  url.searchParams.set('longitude',String(S.lon));
  url.searchParams.set('current','temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day');
  url.searchParams.set('daily','temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,precipitation_probability_max,uv_index_max');
  url.searchParams.set('forecast_days','5');
  url.searchParams.set('timezone','auto');
  url.searchParams.set('_t',String(Math.floor(Date.now()/60000)));
  if(S.props.units==='imperial'){
    url.searchParams.set('temperature_unit','fahrenheit');
    url.searchParams.set('wind_speed_unit','mph');
  }
  return url.toString();
}


// ── refreshWeather() ─────────────────────────────────────────────────────────
// Fetches fresh weather data and updates the display. The main data pipeline:
//   1. Bail early if location isn't resolved yet.
//   2. Snapshot the current cached data (used as fallback in the catch block).
//   3. Fetch from Open-Meteo (cache-busted URL, 6s timeout).
//   4. On success: stamp with _ts, write to localStorage, call applyWeather().
//   5. On failure: re-apply cached data with a "stale as of" pill, or show
//      the "waiting for weather" pill if there's no cache at all.
//   6. Schedule the next refresh in 10–11 minutes (jitter avoids thundering
//      herd on multi-monitor setups where each display runs its own runtime).
function refreshWeather(){
  if(S.lat==null||S.lon==null){
    // Location not yet resolved; retry resolution before scheduling a long
    // 10-minute wait. This handles the case where CoreLocation auth was
    // denied the first time but may be granted now, or where IP geolocation
    // failed transiently.
    resolveLocation().then(function(ok){if(ok){refreshWeather();}else{scheduleRefresh();}});
    return;
  }
  var cached=loadCachedWeather();
  // Sequence guard: only the most-recently-issued forecast may update the
  // display. Without this, a slower earlier fetch (e.g. the startup
  // cached-coordinates request) can resolve last and overwrite the data -- and
  // the source credit -- of the location the user actually selected.
  var mySeq=++S.fetchSeq;
  fetchWithTimeout(buildForecastUrl(),6000)
    .then(function(r){
      // The native bridge tags each forecast response with the source that
      // actually produced it (WeatherKit or the Open-Meteo fallback). Capture
      // it here so the credit line reflects THIS response, not a global flag
      // that a concurrent fetch may have changed.
      var src=r.headers.get('X-AetherDesk-Weather-Source');
      return r.json().then(function(d){d._src=src||null;return d;});
    })
    .then(function(d){
      if(mySeq!==S.fetchSeq)return;
      d._ts=Date.now();
      cacheWeather(d);
      S.staleTime=null;
      hidePill();
      applyWeather(d);
    })
    .catch(function(){
      if(mySeq!==S.fetchSeq)return;
      if(cached){
        S.staleTime=cached._ts||Date.now();
        applyWeather(cached);
        showPill('using cached weather from '+new Date(S.staleTime).toLocaleString([],{month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',timeZoneName:'short'}));
      }else{
        S.staleTime=Date.now();
        showPill('waiting for weather\u2026');
      }
    })
    .finally(function(){scheduleRefresh();});
}


// ── scheduleRefresh() ────────────────────────────────────────────────────────
// Queues the next refreshWeather() call. The 10-minute base interval plus
// up to 60 seconds of jitter prevents all wallpaper instances from hitting the
// Open-Meteo API simultaneously when the user has multiple displays.
function scheduleRefresh(){
  if(S.refreshTimeout)clearTimeout(S.refreshTimeout);
  var jitter=Math.random()*60000;
  S.refreshTimeout=setTimeout(function(){refreshWeather();},10*60*1000+jitter);
}


// ── parseApiTime() ───────────────────────────────────────────────────────────
// Open-Meteo (timezone=auto) returns times local to the forecast location with
// no offset, e.g. "2026-10-06T07:12", plus a top-level utc_offset_seconds.
// new Date() would read such a string in the Mac's zone, misplacing the sun
// whenever the location is in another time zone (a manual location abroad).
// Strings with an explicit offset (the WeatherKit bridge emits "...Z") and
// responses without utc_offset_seconds (preview data) parse as before.
function parseApiTime(s,offsetSec){
  if(typeof offsetSec!=='number'||/(Z|[+-]\d\d:?\d\d)$/.test(s))return new Date(s);
  var p=String(s).match(/^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d))?$/);
  if(!p)return new Date(s);
  return new Date(Date.UTC(+p[1],p[2]-1,+p[3],+p[4],+p[5],+(p[6]||0))-offsetSec*1000);
}


// ── applyWeather() ───────────────────────────────────────────────────────────
// Applies a weather API response object to the global state and re-renders the
// UI. Called both with fresh data (from refreshWeather) and stale data (from
// localStorage on startup). Also triggers a particle rebuild if the weather
// type changed (e.g., was clear, now rainy).
function applyWeather(d){
  S.weather=d;
  // Align the data-source credit with the data actually being shown. `_src` is
  // attached per-response in refreshWeather() and persisted into the localStorage
  // cache, so fresh, stale, and on-launch cached displays all credit the source
  // that genuinely produced the numbers on screen. Absent (older cache / OSS
  // build with no WeatherKit), leave the existing flag untouched.
  if(d&&d._src==='weatherkit')window.__weatherKitActive=true;
  else if(d&&d._src==='open-meteo')window.__weatherKitActive=false;
  var cur=d.current;
  var daily=d.daily;
  if(cur.is_day!==undefined)S.isDay=cur.is_day===1;
  if(daily&&daily.sunrise&&daily.sunrise.length>0){
    S.sunrise=parseApiTime(daily.sunrise[0],d.utc_offset_seconds);
    S.sunset=parseApiTime(daily.sunset[0],d.utc_offset_seconds);
  }
  var nb=wmoBucket(cur.weather_code);
  S.atmosphere=deriveAtmosphericState(d);
  if(S.renderer==='static')applyStaticAtmosphere(S.atmosphere);
  S.skyPaintCache=null;
  var particleProfileKey=String(cur.weather_code);
  if(nb!==S.weatherBucket||S.particleType===null||S.particleProfileKey!==particleProfileKey){
    S.weatherBucket=nb;
    S.particleProfileKey=particleProfileKey;
    rebuildParticles();
  }
  setupAttribution();
  renderUI();
}
