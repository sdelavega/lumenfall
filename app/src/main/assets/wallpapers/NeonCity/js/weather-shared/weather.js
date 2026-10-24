// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../../LICENSE.txt for the MIT license.
'use strict';

window.createSharedWeather = function(S, showPill, hidePill, refreshWeather) {
function fetchWithTimeout(url,ms){
  if(typeof AbortSignal!=='undefined'&&typeof AbortSignal.timeout==='function'){
    return fetch(url,{signal:AbortSignal.timeout(ms)});
  }
  var ctrl=new AbortController();
  setTimeout(function(){ctrl.abort();},ms);
  return fetch(url,{signal:ctrl.signal});
}

function cacheLocation(){
  if(S.lat!=null&&S.lon!=null)try{localStorage.setItem('wa.lastLoc',JSON.stringify({lat:S.lat,lon:S.lon,label:S.label}));}catch(e){}
}

function loadCachedLocation(){
  try{var d=localStorage.getItem('wa.lastLoc');return d?JSON.parse(d):null;}catch(e){return null;}
}

function resolveLocation(){
  var seq=++S.locSeq;
  if(S.props.locationMode==='manual')return geocodeManual(S.props.manualLocation,seq);
  return autoLocation(seq);
}

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

return { fetchWithTimeout, cacheLocation, loadCachedLocation, resolveLocation, autoLocation, hasHostCapability, coordinateLabel, reverseGeocode, fetchIP, fallbackLoc, geocodeManual, buildForecastUrl };
};
