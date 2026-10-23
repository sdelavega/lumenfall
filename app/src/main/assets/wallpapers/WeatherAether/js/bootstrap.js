'use strict';

var PREVIEW_CODES={clear:0,partly:2,overcast:3,fog:45,rain:63,sleet:66,snow:73,thunder:95};

function readPreviewConfig(){
  var params=new URLSearchParams(window.location.search);
  var condition=params.get('preview');
  if(!Object.prototype.hasOwnProperty.call(PREVIEW_CODES,condition))return false;
  var hour=Number(params.get('hour'));
  S.previewCondition=condition;
  S.previewHour=isFinite(hour)?Math.max(0,Math.min(23.99,hour)):12;
  if(params.get('minimal')==='1')document.body.classList.add('wa-preview-minimal');
  return true;
}

function buildPreviewWeather(){
  var now=wallpaperNow();
  var code=PREVIEW_CODES[S.previewCondition];
  var isDay=S.previewHour>=6&&S.previewHour<18;
  var date=now.toISOString().slice(0,10);
  var days=[],codes=[],highs=[],lows=[],precip=[];
  for(var i=0;i<5;i++){
    var day=new Date(now.getTime()+i*86400000);
    days.push(day.toISOString().slice(0,10));
    codes.push(code);highs.push(72-i);lows.push(54-i);precip.push(Math.round((ATMOSPHERIC_PROFILES[S.previewCondition].precipitation||0)*100));
  }
  return{
    _units:'imperial',_src:'open-meteo',
    current:{temperature_2m:68,apparent_temperature:67,relative_humidity_2m:64,weather_code:code,wind_speed_10m:12,wind_direction_10m:235,is_day:isDay?1:0},
    daily:{time:days,weather_code:codes,temperature_2m_max:highs,temperature_2m_min:lows,precipitation_probability_max:precip,uv_index_max:[4,4,4,4,4],sunrise:[date+'T06:00'],sunset:[date+'T18:00']}
  };
}

// ── init() — boot sequence ────────────────────────────────────────────────────
// Entry point. Runs once when the DOM is ready.
//
//   1. Bind canvas and set up sizing.
//   2. Register bridge, visibility, and resize handlers.
//   3. Load last-known location from localStorage → pre-populate S.lat/lon.
//   4. Load last-known weather from localStorage → applyWeather() for instant
//      display (even if stale from a previous session).
//   5. Start the rAF render loop — sky animation begins immediately.
//   6. If cached location exists, call refreshWeather() right away so fresh
//      data arrives without waiting for geolocation to complete. This is the
//      key fix for the stale-data bug: we no longer depend on resolveLocation()
//      settling before the first network fetch.
//   7. Kick off resolveLocation() in parallel — updates S.lat/lon with the
//      current device location, then calls refreshWeather() again with the
//      fresh coordinates. The second call overwrites step 6's result if the
//      location has moved.
function init(){
  S.canvas=document.getElementById('wa-sky');
  S.ctx=S.canvas.getContext('2d');
  var preview=readPreviewConfig();
  var now=wallpaperNow();
  var hr=now.getHours();
  S.isDay=hr>=6&&hr<18;
  resizeCanvas();
  window.addEventListener('resize',resizeCanvas);
  setupBridge();
  setupAttribution();
  setupVisibility();
  if(preview){
    S.label='Preview — '+S.previewCondition.charAt(0).toUpperCase()+S.previewCondition.slice(1);
    applyWeather(buildPreviewWeather());
    S.animationFrameId=requestAnimationFrame(render);
    return;
  }
  var cachedLoc=loadCachedLocation();
  if(cachedLoc){S.lat=cachedLoc.lat;S.lon=cachedLoc.lon;S.label=cachedLoc.label;}
  var cachedW=loadCachedWeather();
  if(cachedW){applyWeather(cachedW);}
  else{renderFallbackUI();showPill('waiting for weather\u2026');}
  S.animationFrameId=requestAnimationFrame(render);
  // Eagerly refresh the cached coordinates ONLY in auto mode, where the cached
  // location is the user's last real location and showing it fast is the point.
  // In manual mode the cached coordinates may belong to a different place, and
  // firing this forecast would race the manual-location forecast below — the
  // race that made the current-conditions credit flip sources. Manual mode
  // instead waits for resolveLocation() (geocode) to produce the one forecast
  // for the location the user actually chose.
  if(S.props.locationMode!=='manual'&&S.lat!=null&&S.lon!=null){
    setTimeout(function(){refreshWeather();},100);
  }
  resolveLocation().then(function(){
    refreshWeather();
  });
}

if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init);}
else{init();}
