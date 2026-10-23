'use strict';

// ── renderUI() ───────────────────────────────────────────────────────────────
// Updates all DOM elements in the conditions card and forecast strip from
// S.weather. Converts units if the stored data's unit system differs from the
// current preference (allows unit switching without re-fetching). Safe to call
// with either fresh or cached data — always guards against missing fields.
function renderUI(){
  var d=S.weather;
  if(!d||!d.current){renderFallbackUI();return;}
  var cur=d.current,daily=d.daily;
  var du=d._units||S.props.units;
  var locEl=document.getElementById('wa-location');
  if(locEl)locEl.textContent=S.label;
  var tempEl=document.getElementById('wa-temp');
  if(tempEl)tempEl.textContent=Math.round(convertTemp(cur.temperature_2m,du,S.props.units))+tUnit();
  var descEl=document.getElementById('wa-desc');
  if(descEl)descEl.textContent=WMO_DESC[cur.weather_code]||'Unknown';
  var feelsEl=document.getElementById('wa-feels');
  if(feelsEl)feelsEl.textContent='Feels '+Math.round(convertTemp(cur.apparent_temperature,du,S.props.units))+tUnit();
  var humEl=document.getElementById('wa-humidity');
  if(humEl)humEl.textContent=cur.relative_humidity_2m+'% humidity';
  var windEl=document.getElementById('wa-wind');
  if(windEl)windEl.textContent=Math.round(convertWind(cur.wind_speed_10m,du,S.props.units))+' '+wUnit()+' '+windDir(cur.wind_direction_10m);
  var uvEl=document.getElementById('wa-uv');
  if(uvEl){
    var uvVal=(daily&&daily.uv_index_max&&daily.uv_index_max.length>0)?daily.uv_index_max[0]:null;
    uvEl.textContent=uvVal!=null?'UV '+(Math.round(uvVal*10)/10):'UV --';
  }
  var iconEl=document.getElementById('wa-icon');
  if(iconEl){iconEl.src=getIconCanvas(cur.weather_code,S.isDay).toDataURL();iconEl.style.display='block';}
  renderForecast(daily);
  applyAccentColor();
}


// ── renderForecast() ─────────────────────────────────────────────────────────
// Rebuilds the 5-day forecast card strip from the API's daily data arrays.
// Each card shows: day name (TODAY for index 0), weather icon, high/low temps,
// and precipitation probability. Cards are created as DOM nodes and appended
// to #wa-forecast.
function renderForecast(daily){
  var strip=document.getElementById('wa-forecast');
  if(!strip)return;
  strip.innerHTML='';
  strip.style.opacity=String(S.props.forecastOpacity);
  if(!daily||!daily.time||daily.time.length===0){strip.style.display='none';return;}
  strip.style.display='flex';
  var du=(S.weather&&S.weather._units)?S.weather._units:S.props.units;
  for(var i=0;i<daily.time.length&&i<5;i++){
    var card=document.createElement('div');card.className='wa-fc-card';
    var dayEl=document.createElement('div');dayEl.className='wa-fc-day';
    var dt=new Date(daily.time[i]+'T00:00:00');
    dayEl.textContent=i===0?'TODAY':DAY_NAMES[dt.getDay()];
    card.appendChild(dayEl);
    var iconWrap=document.createElement('div');iconWrap.className='wa-fc-icon';
    var code=daily.weather_code[i];
    var ic=getIconCanvas(code,true);
    var img=document.createElement('img');img.src=ic.toDataURL();
    iconWrap.appendChild(img);card.appendChild(iconWrap);
    var hi=convertTemp(daily.temperature_2m_max[i],du,S.props.units);
    var lo=convertTemp(daily.temperature_2m_min[i],du,S.props.units);
    var tempDiv=document.createElement('div');tempDiv.className='wa-fc-temps';
    var hiSpan=document.createElement('span');hiSpan.className='wa-fc-hi';hiSpan.textContent=Math.round(hi)+'\u00B0';
    var loSpan=document.createElement('span');loSpan.className='wa-fc-lo';loSpan.textContent=Math.round(lo)+'\u00B0';
    tempDiv.appendChild(hiSpan);tempDiv.appendChild(loSpan);card.appendChild(tempDiv);
    var precip=daily.precipitation_probability_max[i];
    var precipEl=document.createElement('div');precipEl.className='wa-fc-precip';
    precipEl.textContent=precip!=null?precip+'%':'';
    card.appendChild(precipEl);
    strip.appendChild(card);
  }
}


// ── renderFallbackUI() ───────────────────────────────────────────────────────
// Clears all UI text fields and hides the icon and forecast strip. Called when
// there is no weather data to display (first load before any fetch completes,
// or after a complete data loss). Leaves the sky animation running.
function renderFallbackUI(){
  var locEl=document.getElementById('wa-location');if(locEl)locEl.textContent=S.label||'Unknown';
  var tempEl=document.getElementById('wa-temp');if(tempEl)tempEl.textContent='--'+tUnit();
  var descEl=document.getElementById('wa-desc');if(descEl)descEl.textContent='';
  var feelsEl=document.getElementById('wa-feels');if(feelsEl)feelsEl.textContent='';
  var humEl=document.getElementById('wa-humidity');if(humEl)humEl.textContent='';
  var windEl=document.getElementById('wa-wind');if(windEl)windEl.textContent='';
  var uvEl=document.getElementById('wa-uv');if(uvEl)uvEl.textContent='';
  var iconEl=document.getElementById('wa-icon');if(iconEl)iconEl.style.display='none';
  var strip=document.getElementById('wa-forecast');if(strip)strip.style.display='none';
}

function applyAccentColor(){
  document.documentElement.style.setProperty('--accent',S.props.accentColor);
  var cards=document.querySelectorAll('.wa-fc-card');
  for(var i=0;i<cards.length;i++){
    cards[i].style.borderColor=S.props.accentColor+'22';
  }
}

// ── setupAttribution() ────────────────────────────────────────────────────
// Shows a small, non-interactive data-source credit in the bottom-right
// corner of the current-conditions card: "Apple Weather" or "Open-Meteo"
// depending on window.__weatherKitActive. This is plain text, not a link —
// wallpapers aren't supposed to be click-interactive. The legal attribution
// link required by Apple's WeatherKit terms lives in the app's Settings >
// About tab instead, not in the wallpaper itself.
//
// window.__weatherKitActive isn't a static build flag: the native host seeds
// it with an initial guess at document-start, then overwrites it after every
// forecast fetch with the *actual* source used for that response (WeatherKit,
// or the silent Open-Meteo fallback when WeatherKit is unavailable). That's
// why this is called again from applyWeather() on every refresh, not just
// once at init — the App Store build genuinely can and does serve Open-Meteo
// data, and the credit line needs to reflect that truthfully each time.
function setupAttribution(){
  var el=document.getElementById('wa-attribution');
  if(!el)return;
  el.textContent=window.__weatherKitActive?' Weather':'Open-Meteo';
}

function showPill(msg){
  var pill=document.getElementById('wa-pill');
  if(pill){pill.textContent=msg;pill.classList.add('wa-pill-visible');}
}

function hidePill(){
  var pill=document.getElementById('wa-pill');
  if(pill)pill.classList.remove('wa-pill-visible');
}


// ── applyProps() ─────────────────────────────────────────────────────────────
// Applies a partial property update from the AetherDesk property bridge or
// livelyPropertyListener. Compares each incoming value to the current state and
// only triggers side effects when something actually changed:
//   locationMode/manualLocation change → re-resolve location + re-fetch weather
//   units change → re-render UI (unit conversion only, no new fetch)
//   forecastOpacity/animationSpeed/accentColor → immediate visual update
//   showParticles change → rebuild particle pool
function applyProps(props){
  var needLoc=false,needRefresh=false;
  if(props.locationMode!==undefined&&props.locationMode!==S.props.locationMode){
    S.props.locationMode=props.locationMode;needLoc=true;
  }
  if(props.manualLocation!==undefined&&props.manualLocation!==S.props.manualLocation){
    S.props.manualLocation=props.manualLocation;
    if(S.props.locationMode==='manual')needLoc=true;
  }
  if(props.units!==undefined&&props.units!==S.props.units){
    S.props.units=props.units;needRefresh=true;
    if(S.weather)renderUI();
  }
  if(props.forecastOpacity!==undefined){
    S.props.forecastOpacity=props.forecastOpacity;
    var strip=document.getElementById('wa-forecast');
    if(strip)strip.style.opacity=String(S.props.forecastOpacity);
  }
  if(props.animationSpeed!==undefined){
    S.props.animationSpeed=props.animationSpeed;
  }
  if(props.showParticles!==undefined&&props.showParticles!==S.props.showParticles){
    S.props.showParticles=props.showParticles;
    rebuildParticles();
  }
  if(props.accentColor!==undefined){
    S.props.accentColor=props.accentColor;
    applyAccentColor();
  }
  if(needLoc){
    resolveLocation().then(function(ok){if(ok)refreshWeather();});
  }else if(needRefresh){
    refreshWeather();
  }
}


// ── setupBridge() ────────────────────────────────────────────────────────────
// Registers both property bridge hooks so this wallpaper works with any version
// of the ÆtherDesk host:
//   onUpdate — modern callback-based bridge (preferred)
//   livelyPropertyListener — legacy global function hook (some older hosts)
// Both delegate to applyProps() so the handling logic stays in one place.
function setupBridge(){
  if(window.aetherDesk&&window.aetherDesk.properties&&typeof window.aetherDesk.properties.onUpdate==='function'){
    window.aetherDesk.properties.onUpdate(function(props){applyProps(props);});
  }
  window.livelyPropertyListener=function(name,value){
    var o={};o[name]=value;applyProps(o);
  };
}
