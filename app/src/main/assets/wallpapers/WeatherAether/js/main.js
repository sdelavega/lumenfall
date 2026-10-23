
// ─────────────────────────────────────────────────────────────────────────────
// Weather Aether — ÆtherDesk bundled wallpaper
//
// A live weather wallpaper: sky gradient, particle weather effects, current
// conditions, and a 5-day forecast — all driven by real-time data from the
// Open-Meteo API (free, no API key required).
//
// ── AetherDesk integration points used ───────────────────────────────────────
//
//   window.fetch (native bridge)
//     AetherDesk replaces window.fetch with a proxy that routes all HTTP/HTTPS
//     requests through URLSession on the host process. This bypasses WKWebView's
//     CORS restrictions so the wallpaper can reach external APIs (weather data,
//     IP geolocation) without a server-side proxy. All fetch() calls in this
//     file transparently use the native bridge — no special handling needed.
//     The bridge enforces a per-minute network budget (see LivelyProperties.json
//     and WallpaperValidator in the host app).
//
//   navigator.geolocation (native bridge)
//     AetherDesk replaces navigator.geolocation with a CoreLocation-backed
//     implementation. IMPORTANT: the {timeout} option passed to
//     getCurrentPosition() is silently ignored by the native bridge. A manual
//     5-second timeout guard is used in autoLocation() to work around this.
//
//   window.aetherDesk.properties.onUpdate(callback)
//     Receives property changes from the sidebar. Also fires once on load with
//     all initial values from LivelyProperties.json.
//
//   window.livelyPropertyListener(name, value) [legacy compat]
//     Single-property alternative. Registered below for hosts that call this
//     global instead of onUpdate.
//
// ── Properties (defined in LivelyProperties.json) ────────────────────────────
//
//   locationMode     ['auto'|'manual']  Use device location or a typed city
//   manualLocation   [string]           City/region name for geocoding
//   units            ['imperial'|'metric'] °F/mph vs °C/km·h⁻¹
//   forecastOpacity  [number, 0–1]      Forecast strip opacity
//   animationSpeed   [number]           Particle animation speed multiplier
//   showParticles    [boolean]          Enable rain/snow/cloud particles
//   accentColor      [color string]     Precipitation probability text colour
//
// ── Data flow ─────────────────────────────────────────────────────────────────
//
//   init()
//     ↓ load cached location from localStorage → set S.lat/lon
//     ↓ load cached weather from localStorage → applyWeather() [stale display]
//     ↓ start rAF render loop
//     ↓ if cached location exists: immediately call refreshWeather() [fixes stale]
//     ↓ resolveLocation() → [CoreLocation or IP geolocation] → refreshWeather()
//
//   refreshWeather()
//     → Open-Meteo forecast API (cache-busted with _t timestamp param)
//     → on success: cacheWeather() + applyWeather() [live display]
//     → on failure: re-apply last cached data with "stale as of" pill
//     → scheduleRefresh() → retry in 10–11 minutes
//
// ── localStorage caching ──────────────────────────────────────────────────────
//
//   The wallpaper caches weather data and last-known location in localStorage
//   so it can display something meaningful immediately on load before the API
//   call completes. Cache keys are derived from the lat/lon so each location
//   has its own entry. The host WKWebView uses a persistent WKWebsiteDataStore,
//   so cache survives app restarts.
//
//   Key format:  wa_<lat.2f>_<lon.2f>   (weather data JSON)
//                wa.lastLoc              (last location {lat, lon, label})
// ─────────────────────────────────────────────────────────────────────────────

// WMO weather interpretation codes → human-readable descriptions.
// Source: https://open-meteo.com/en/docs — "WMO Weather interpretation codes"
// Used to populate the condition label in renderUI().
var COMPASS=['N','NNE','NE','ENE','E','ESE','SE','SSE','S','SSW','SW','WSW','W','WNW','NW','NNW'];
var DAY_NAMES=['SUN','MON','TUE','WED','THU','FRI','SAT'];


// ── Global state object (S) ───────────────────────────────────────────────────
// All mutable runtime state lives here. Keeping it in one place makes it easy
// to understand what's shared between functions and to reset state on reload.
//
//   lat, lon       Current location (decimal degrees). null until resolved.
//   label          Human-readable location name shown in the UI.
//   weather        Last-received weather API response object.
//   staleTime      Timestamp of cached data currently displayed (null = live).
//   sunrise/sunset Date objects from the API's daily data. Used to anchor the
//                  sky gradient phase transitions to actual solar times.
//   isDay          Whether the current time is between sunrise and sunset.
//   props          Live property values — updated by applyProps().
//   particles      Active precipitation objects (rain drops, snowflakes…).
//   clouds         Depth-separated cloud forms, independent of precipitation.
//   stars          Star objects — visible on clear nights.
//   particleType   The weatherBucket active when particles were last built.
//                  Used to detect changes that require a full particle rebuild.
//   weatherBucket  Current weather type ('clear', 'rain', etc.).
//   animationFrameId / refreshTimeout — handles for cleanup on reload.
//   lightningAlpha  Current flash intensity (0 when no storm).
//   lightningCooldown  Seconds until next lightning is allowed.
//   fogAlpha        Current rendered fog opacity (animated toward target).
//   canvas, ctx     The main rendering surface.
//   iconCache       Keyed off-screen canvases for weather icons (code_d/n).
var S={
  lat:null,lon:null,label:'Unknown',
  weather:null,staleTime:null,
  sunrise:null,sunset:null,isDay:true,
  previewCondition:null,previewHour:null,
  props:{
    locationMode:'auto',manualLocation:'',
    units:'imperial',forecastOpacity:0.9,
    animationSpeed:1.0,showParticles:true,accentColor:'#6fb8ff'
  },
  particles:[],stars:[],clouds:[],cloudSprites:[],cloudLightingKey:null,particleType:null,weatherBucket:'clear',
  atmosphere:null,
  animationFrameId:null,refreshTimeout:null,lastFrameTime:0,fetchSeq:0,locSeq:0,
  lightningAlpha:0,lightningCooldown:5,fogAlpha:0,motionTime:0,windGust:1,
  canvas:null,ctx:null,iconCache:{},skyPaintCache:null,moonTexture:null
};

function lerp(a,b,t){return a+(b-a)*t}
function lerpC(a,b,t){return[lerp(a[0],b[0],t),lerp(a[1],b[1],t),lerp(a[2],b[2],t)]}
function rgb(c){return'rgb('+Math.round(c[0])+','+Math.round(c[1])+','+Math.round(c[2])+')'}
function rgba(c,a){return'rgba('+Math.round(c[0])+','+Math.round(c[1])+','+Math.round(c[2])+','+a+')'}
function windDir(d){return COMPASS[Math.round(d/22.5)%16]}
function tUnit(){return S.props.units==='imperial'?'\u00B0F':'\u00B0C'}
function wUnit(){return S.props.units==='imperial'?'mph':'km/h'}
function cssW(){return window.innerWidth}
function cssH(){return window.innerHeight}
function wallpaperNow(){
  var now=new Date();
  if(S.previewHour!=null){
    var whole=Math.floor(S.previewHour);
    now.setHours(whole,Math.round((S.previewHour-whole)*60),0,0);
  }
  return now;
}
function convertTemp(v,from,to){
  if(from===to)return v;
  if(from==='metric'&&to==='imperial')return v*9/5+32;
  if(from==='imperial'&&to==='metric')return(v-32)*5/9;
  return v;
}
function convertWind(v,from,to){
  if(from===to)return v;
  if(from==='metric'&&to==='imperial')return v*0.621371;
  if(from==='imperial'&&to==='metric')return v/0.621371;
  return v;
}
