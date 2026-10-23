'use strict';

var WMO_DESC={0:'Clear sky',1:'Mainly clear',2:'Partly cloudy',3:'Overcast',4:'Smoke',5:'Haze',6:'Dust',7:'Blowing dust',45:'Fog',48:'Depositing rime fog',51:'Light drizzle',53:'Moderate drizzle',55:'Dense drizzle',56:'Light freezing drizzle',57:'Dense freezing drizzle',61:'Slight rain',63:'Moderate rain',65:'Heavy rain',66:'Light freezing rain',67:'Heavy freezing rain',71:'Slight snowfall',73:'Moderate snowfall',75:'Heavy snowfall',77:'Snow grains',80:'Slight rain showers',81:'Moderate rain showers',82:'Violent rain showers',85:'Slight snow showers',86:'Heavy snow showers',95:'Thunderstorm',96:'Thunderstorm with slight hail',99:'Thunderstorm with heavy hail'};


// ── wmoBucket() ──────────────────────────────────────────────────────────────
// Maps a WMO weather code to one of nine visual buckets used throughout the
// renderer: 'clear', 'partly', 'overcast', 'fog', 'rain', 'freezing', 'snow',
// 'thunder', 'hail'. The bucket drives the sky tint (WEATHER_TINT), the particle type
// (rebuildParticles), and the canvas icon drawn in renderUI().
// Every bucket is intentionally reachable from both Open-Meteo and WeatherKit.
// Provider-only distinctions collapse into the nearest shared visual state.
function wmoBucket(code){
  if(code<=1)return'clear';
  if(code===2)return'partly';
  if(code===3)return'overcast';
  // 4–7 (smoke, haze, dust) never come from open-meteo; the WeatherKit bridge
  // emits them. They render as reduced visibility, like fog.
  if(code>=4&&code<=7)return'fog';
  if(code===45||code===48)return'fog';
  if(code===56||code===57||code===66||code===67)return'freezing';
  if(code>=51&&code<=57)return'rain';
  if(code>=61&&code<=67)return'rain';
  if(code>=71&&code<=77)return'snow';
  if(code>=80&&code<=82)return'rain';
  if(code>=85&&code<=86)return'snow';
  if(code===96||code===99)return'hail';
  if(code===95)return'thunder';
  return'clear';
}


// ── Sky colour definitions ────────────────────────────────────────────────────
// PHASE_C maps named time-of-day phases to three-stop vertical gradient colours
// (top, mid, bot) in RGB. The render loop interpolates between adjacent stops
// based on the current hour to produce a smooth 24-hour sky cycle.
//
// WEATHER_TINT defines how much each weather condition desaturates and greys
// the sky. `factor` 0 = no tint (clear sky), 0.35 = heavy overcast/thunder.
// The tint colour is blended toward c[R,G,B] by the factor amount.
var PHASE_C={
  night:   {top:[8,12,30],  mid:[12,18,45],  bot:[18,24,58]},
  dawn:    {top:[50,35,90], mid:[150,80,110], bot:[220,130,70]},
  morning: {top:[80,155,230],mid:[135,195,245],bot:[185,218,242]},
  midday:  {top:[45,130,210],mid:[100,180,240],bot:[170,215,250]},
  afternoon:{top:[60,140,220],mid:[130,190,238],bot:[200,220,230]},
  dusk:    {top:[60,40,100], mid:[160,75,85],  bot:[230,130,55]}
};

var WEATHER_TINT={
  clear:   {factor:0,   c:[0,0,0]},
  partly:  {factor:0.12,c:[140,150,170]},
  overcast:{factor:0.30,c:[110,120,140]},
  rain:    {factor:0.25,c:[35,45,75]},
  freezing:{factor:0.22,c:[90,110,140]},
  snow:    {factor:0.20,c:[200,210,230]},
  thunder: {factor:0.35,c:[25,15,55]},
  hail:    {factor:0.37,c:[25,30,58]},
  fog:     {factor:0.25,c:[155,165,180]}
};

// Translates weather observations into renderer-independent visual meaning.
// Values are normalized to 0...1 so future Canvas, WebGL, or remote renderers
// can interpret the same atmosphere without owning weather policy themselves.
var ATMOSPHERIC_PROFILES={
  clear:   {cloudCover:0.03,cloudDensity:0.04,precipitation:0.00,horizonHaze:0.08,lightDiffusion:0.06,airClarity:0.96,motionEnergy:0.12,celestialVisibility:1.00},
  partly:  {cloudCover:0.42,cloudDensity:0.32,precipitation:0.00,horizonHaze:0.18,lightDiffusion:0.28,airClarity:0.82,motionEnergy:0.30,celestialVisibility:0.58},
  overcast:{cloudCover:0.94,cloudDensity:0.78,precipitation:0.00,horizonHaze:0.34,lightDiffusion:0.76,airClarity:0.58,motionEnergy:0.22,celestialVisibility:0.06},
  fog:     {cloudCover:0.75,cloudDensity:0.86,precipitation:0.00,horizonHaze:0.92,lightDiffusion:0.96,airClarity:0.12,motionEnergy:0.06,celestialVisibility:0.04},
  rain:    {cloudCover:0.96,cloudDensity:0.84,precipitation:0.68,horizonHaze:0.54,lightDiffusion:0.82,airClarity:0.42,motionEnergy:0.62,celestialVisibility:0.03},
  freezing:{cloudCover:0.98,cloudDensity:0.88,precipitation:0.72,horizonHaze:0.60,lightDiffusion:0.86,airClarity:0.36,motionEnergy:0.58,celestialVisibility:0.02},
  snow:    {cloudCover:0.95,cloudDensity:0.76,precipitation:0.58,horizonHaze:0.66,lightDiffusion:0.90,airClarity:0.34,motionEnergy:0.28,celestialVisibility:0.04},
  thunder: {cloudCover:1.00,cloudDensity:0.96,precipitation:0.92,horizonHaze:0.62,lightDiffusion:0.88,airClarity:0.28,motionEnergy:0.92,celestialVisibility:0.00},
  hail:    {cloudCover:1.00,cloudDensity:0.98,precipitation:1.00,horizonHaze:0.64,lightDiffusion:0.90,airClarity:0.24,motionEnergy:1.00,celestialVisibility:0.00}
};

// Converts provider-normalized WMO codes into continuous renderer parameters.
// Conditions choose the visual system; these traits describe how that system
// behaves. This keeps drizzle, rain, and showers in one renderer without making
// them visually interchangeable.
function derivePrecipitationTraits(code,bucket,base){
  var traits={
    intensity:base.precipitation||0,
    intermittency:0,
    dropletScale:1,
    flutter:bucket==='snow'?0.72:0,
    grain:0,
    iceFraction:bucket==='freezing'?0.12:bucket==='snow'||bucket==='hail'?1:0,
    stormEnergy:bucket==='thunder'||bucket==='hail'?0.72:0
  };
  var intensityByCode={
    51:0.16,53:0.28,55:0.42,56:0.22,57:0.48,
    61:0.38,63:0.66,65:1.00,66:0.52,67:0.92,
    71:0.32,73:0.60,75:1.00,77:0.28,
    80:0.44,81:0.70,82:1.00,85:0.40,86:0.88,
    95:0.82,96:0.62,99:1.00
  };
  if(intensityByCode[code]!=null)traits.intensity=intensityByCode[code];
  if(code>=51&&code<=55)traits.dropletScale=0.52+traits.intensity*0.34;
  if(code===56)traits.dropletScale=0.55;
  if(code===57)traits.dropletScale=0.68;
  if(code===66)traits.dropletScale=0.78;
  if(code===67)traits.dropletScale=1.05;
  if(code===71)traits.dropletScale=0.78;
  if(code===73)traits.dropletScale=1.00;
  if(code===75)traits.dropletScale=1.20;
  if(code===77){traits.dropletScale=0.48;traits.flutter=0.12;traits.grain=1;}
  if(code===85){traits.dropletScale=0.82;traits.flutter=0.92;}
  if(code===86){traits.dropletScale=1.14;traits.flutter=1.00;}
  if(code===96)traits.dropletScale=0.72;
  if(code===99)traits.dropletScale=1.18;
  if(code>=80&&code<=82)traits.intermittency=0.52+traits.intensity*0.34;
  if(code===85||code===86)traits.intermittency=0.48+traits.intensity*0.28;
  if(code>=95)traits.intermittency=0.30+traits.intensity*0.20;
  if(bucket==='freezing')traits.iceFraction=code===56||code===57?0.05:0.10;
  if(bucket==='thunder'||bucket==='hail')traits.stormEnergy=traits.intensity;
  return traits;
}

function getPrecipitationActivity(atmosphere,time){
  var intermittency=atmosphere.precipitationIntermittency||0;
  if(intermittency<=0)return 1;
  var broad=0.5+0.5*Math.sin(time*0.23+0.8);
  var detail=0.5+0.5*Math.sin(time*0.67+2.4);
  var pulse=0.28+broad*0.50+detail*0.22;
  return lerp(1,pulse,intermittency);
}

function deriveAtmosphericState(weather){
  var cur=weather&&weather.current?weather.current:{};
  var code=cur.weather_code==null?0:Number(cur.weather_code);
  var bucket=wmoBucket(code);
  var base=ATMOSPHERIC_PROFILES[bucket]||ATMOSPHERIC_PROFILES.clear;
  var precipitation=derivePrecipitationTraits(code,bucket,base);
  var wind=Number(cur.wind_speed_10m)||0;
  var sourceUnits=(weather&&weather._units)||S.props.units;
  var windScale=sourceUnits==='imperial'?35:56;
  var horizonHaze=base.horizonHaze;
  var lightDiffusion=base.lightDiffusion;
  var airClarity=base.airClarity;
  var cloudCover=base.cloudCover;
  var cloudDensity=base.cloudDensity;
  if(bucket==='rain'){
    cloudCover=lerp(0.86,1.00,precipitation.intensity);
    cloudDensity=lerp(0.72,0.94,precipitation.intensity);
    horizonHaze=lerp(0.38,0.70,precipitation.intensity)+(code<=55?0.06:0);
    lightDiffusion=lerp(0.68,0.91,precipitation.intensity);
    airClarity=lerp(0.62,0.25,precipitation.intensity)-(code<=55?0.04:0);
  }else if(bucket==='freezing'){
    cloudDensity=lerp(0.80,0.96,precipitation.intensity);
    horizonHaze=lerp(0.48,0.72,precipitation.intensity);
    lightDiffusion=lerp(0.78,0.93,precipitation.intensity);
    airClarity=lerp(0.50,0.24,precipitation.intensity);
  }else if(bucket==='snow'){
    cloudDensity=lerp(0.68,0.92,precipitation.intensity);
    horizonHaze=lerp(0.48,0.82,precipitation.intensity);
    lightDiffusion=lerp(0.80,0.96,precipitation.intensity);
    airClarity=lerp(0.52,0.18,precipitation.intensity);
  }else if(bucket==='hail'){
    horizonHaze=lerp(0.54,0.72,precipitation.intensity);
    airClarity=lerp(0.34,0.16,precipitation.intensity);
  }
  return{
    condition:bucket,
    cloudCover:Math.max(0,Math.min(1,cloudCover)),
    cloudDensity:Math.max(0,Math.min(1,cloudDensity)),
    precipitation:precipitation.intensity,
    precipitationIntensity:precipitation.intensity,
    precipitationIntermittency:precipitation.intermittency,
    precipitationScale:precipitation.dropletScale,
    precipitationFlutter:precipitation.flutter,
    precipitationGrain:precipitation.grain,
    iceFraction:precipitation.iceFraction,
    stormEnergy:precipitation.stormEnergy,
    horizonHaze:Math.max(0,Math.min(1,horizonHaze)),
    lightDiffusion:Math.max(0,Math.min(1,lightDiffusion)),
    airClarity:Math.max(0,Math.min(1,airClarity)),
    motionEnergy:Math.min(1,base.motionEnergy+(wind/windScale)*0.22+precipitation.intensity*0.08),
    celestialVisibility:base.celestialVisibility,
    windDirection:Number(cur.wind_direction_10m)||0,
    isDay:cur.is_day===undefined?S.isDay:cur.is_day===1
  };
}

// Returns a normalized path for the dominant celestial light. The sun follows
// the actual local sunrise/sunset window supplied by the weather response.
// Night uses the inverse arc as a deliberately quiet moon approximation until
// a full astronomical position model earns its complexity.
function getCelestialTrack(now,isDay){
  now=now||wallpaperNow();
  var hour=now.getHours()+now.getMinutes()/60+now.getSeconds()/3600;
  var sunrise=6,sunset=18;
  if(S.sunrise&&S.sunset&&!isNaN(S.sunrise.getTime())&&!isNaN(S.sunset.getTime())){
    sunrise=S.sunrise.getHours()+S.sunrise.getMinutes()/60;
    sunset=S.sunset.getHours()+S.sunset.getMinutes()/60;
  }
  var progress;
  if(isDay){
    progress=(hour-sunrise)/Math.max(1,sunset-sunrise);
  }else{
    var nightHour=hour>=sunset?hour-sunset:hour+24-sunset;
    progress=nightHour/Math.max(1,24-sunset+sunrise);
  }
  progress=Math.max(0,Math.min(1,progress));
  var elevation=Math.sin(progress*Math.PI);
  return{
    progress:progress,
    elevation:elevation,
    x:0.12+progress*0.76,
    y:0.76-elevation*0.60,
    horizonWarmth:isDay?Math.pow(1-elevation,2):0
  };
}

// Converts celestial position and cloud density into a cloud palette. This is
// atmospheric policy rather than renderer policy: every future renderer should
// agree that midnight clouds are dim and cool, while low sunlight warms their
// illuminated edges.
function getCloudLighting(atmosphere,now){
  now=now||wallpaperNow();
  var isDay=atmosphere.isDay;
  var track=getCelestialTrack(now,isDay);
  var density=atmosphere.cloudDensity;
  var highlight,middle,shadow;
  if(isDay){
    var warmth=track.horizonWarmth*0.78;
    highlight=lerpC([246,248,251],[255,207,164],warmth);
    middle=lerpC([222,228,236],[223,164,132],warmth);
    shadow=lerpC([164,174,190],[152,108,105],warmth);
    highlight=lerpC(highlight,[128,138,153],density*0.48);
    middle=lerpC(middle,[98,108,125],density*0.52);
    shadow=lerpC(shadow,[55,63,79],density*0.58);
  }else{
    highlight=lerpC([78,88,108],[46,52,68],density*0.58);
    middle=lerpC([52,61,80],[31,37,52],density*0.64);
    shadow=lerpC([29,35,51],[16,20,32],density*0.72);
  }
  return{
    highlight:highlight,middle:middle,shadow:shadow,
    sourceX:0.18+track.x*0.64,
    highlightAlpha:isDay?0.24-density*0.08:0.10-density*0.035
  };
}

function getCloudLightingKey(atmosphere,now){
  now=now||wallpaperNow();
  var quarter=Math.floor((now.getHours()*60+now.getMinutes())/15);
  return[atmosphere.condition,atmosphere.isDay?1:0,quarter].join(':');
}

function getPrecipitationLighting(atmosphere,now){
  var cloudLight=getCloudLighting(atmosphere,now);
  if(atmosphere.isDay){
    return{
      rain:lerpC(cloudLight.middle,[190,215,240],0.56),
      freezing:lerpC(cloudLight.highlight,[210,226,242],0.62),
      snow:lerpC(cloudLight.highlight,[238,242,248],0.76),
      hail:lerpC(cloudLight.highlight,[228,240,252],0.72)
    };
  }
  return{
    rain:lerpC(cloudLight.middle,[88,112,148],0.48),
    freezing:lerpC(cloudLight.highlight,[118,139,170],0.52),
    snow:lerpC(cloudLight.highlight,[155,169,194],0.62),
    hail:lerpC(cloudLight.highlight,[142,164,196],0.62)
  };
}

// ── buildTimeline() ──────────────────────────────────────────────────────────
// Builds the colour keyframe array for today's sky gradient, anchored to the
// actual sunrise and sunset times from the API. Each keyframe has a clock hour
// and top/mid/bot RGB values. getPhaseColors() interpolates between adjacent
// keyframes based on the current real-world time.
//
// Falls back to 06:00 / 18:00 if sunrise/sunset data isn't available yet.
function buildTimeline(){
  var sr,ss;
  if(S.sunrise&&S.sunset&&!isNaN(S.sunrise.getTime())&&!isNaN(S.sunset.getTime())){
    sr=S.sunrise.getHours()+S.sunrise.getMinutes()/60;
    ss=S.sunset.getHours()+S.sunset.getMinutes()/60;
  }else{sr=6;ss=18;}
  var dawnS=Math.max(0,sr-1.5);
  var mornE=Math.min(sr+2,(sr+ss)/2);
  var mid=(sr+ss)/2;
  var aftE=Math.max(ss-2,mid+1);
  var duskE=Math.min(24,ss+1);
  var N=PHASE_C.night,D=PHASE_C.dawn,Mo=PHASE_C.morning,Mi=PHASE_C.midday,A=PHASE_C.afternoon,Du=PHASE_C.dusk;
  return[
    {h:0,   t:N.top, m:N.mid, b:N.bot, p:'night'},
    {h:dawnS,t:lerpC(N.top,D.top,0.4),m:lerpC(N.mid,D.mid,0.4),b:lerpC(N.bot,D.bot,0.4),p:'dawn'},
    {h:sr,  t:D.top, m:D.mid, b:D.bot, p:'dawn'},
    {h:mornE,t:Mo.top,m:Mo.mid,b:Mo.bot,p:'morning'},
    {h:mid, t:Mi.top,m:Mi.mid,b:Mi.bot,p:'midday'},
    {h:aftE,t:A.top, m:A.mid, b:A.bot, p:'afternoon'},
    {h:ss,  t:Du.top,m:Du.mid,b:Du.bot,p:'dusk'},
    {h:duskE,t:lerpC(Du.top,N.top,0.6),m:lerpC(Du.mid,N.mid,0.6),b:lerpC(Du.bot,N.bot,0.6),p:'night'},
    {h:24,  t:N.top, m:N.mid, b:N.bot, p:'night'}
  ];
}


// ── getPhaseColors() ─────────────────────────────────────────────────────────
// Returns the sky gradient colours for the current moment. Walks the keyframe
// timeline built by buildTimeline(), finds the two keyframes bracketing the
// current hour, and lerps between them. Then applies the weather tint for the
// current condition (e.g., grey desaturation for overcast/rain).
//
// Called every frame from render() — must be cheap. buildTimeline() is the
// expensive part; getPhaseColors() itself is ~10 lerps and a lookup.
function getPhaseColors(){
  var tl=buildTimeline();
  var now=wallpaperNow();
  var h=now.getHours()+now.getMinutes()/60+now.getSeconds()/3600;
  var i=0;
  while(i<tl.length-1&&tl[i+1].h<=h)i++;
  if(i>=tl.length-1)i=tl.length-2;
  var a=tl[i],b=tl[i+1];
  var range=b.h-a.h;
  var t=range>0?(h-a.h)/range:0;
  t=Math.max(0,Math.min(1,t));
  var top=lerpC(a.t,b.t,t);
  var mid=lerpC(a.m,b.m,t);
  var bot=lerpC(a.b,b.b,t);
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var tint=WEATHER_TINT[atmosphere.condition]||WEATHER_TINT.clear;
  // Diffused air compresses contrast across the sky before any objects are
  // drawn, allowing conditions to change the light rather than merely adding
  // particles over a clear gradient.
  var diffusion=atmosphere.lightDiffusion*0.16;
  var air=[145,155,170];
  top=lerpC(top,air,diffusion);
  mid=lerpC(mid,air,diffusion*0.72);
  bot=lerpC(bot,[190,195,202],atmosphere.horizonHaze*0.18);
  if(tint.factor>0){
    top=lerpC(top,tint.c,tint.factor);
    mid=lerpC(mid,tint.c,tint.factor);
    bot=lerpC(bot,tint.c,tint.factor);
  }
  return{top:top,mid:mid,bot:bot,phase:a.p};
}
