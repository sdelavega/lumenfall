'use strict';

// ── Canvas weather icon primitives ───────────────────────────────────────────
// Simple Canvas 2D drawing functions for the weather icons shown in the current-
// conditions card and forecast strip. Each function draws into an existing ctx
// using the provided centre point (x, y) and radius (r). No images or SVGs —
// icons are drawn at runtime so they scale perfectly to any DPI.
//
// All primitives are also composed together in drawWeatherIcon() for compound
// icons like "partly cloudy" (sun + cloud) or "rainy" (cloud + drops).
function drawSun(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#FFD93D';
  ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fill();
  ctx.strokeStyle='#FFD93D';
  ctx.lineWidth=Math.max(1,r*0.15);
  for(var i=0;i<8;i++){
    var a=i*Math.PI/4;
    ctx.beginPath();
    ctx.moveTo(x+Math.cos(a)*r*1.35,y+Math.sin(a)*r*1.35);
    ctx.lineTo(x+Math.cos(a)*r*1.8,y+Math.sin(a)*r*1.8);
    ctx.stroke();
  }
}

function drawMoon(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#E8E8D0';
  ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fill();
  ctx.fillStyle='#2a2a40';
  ctx.beginPath();ctx.arc(x+r*0.4,y-r*0.2,r*0.75,0,Math.PI*2);ctx.fill();
}

function drawCloud(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='rgba(220,225,235,0.88)';
  ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fill();
  ctx.beginPath();ctx.arc(x-r*0.8,y+r*0.2,r*0.7,0,Math.PI*2);ctx.fill();
  ctx.beginPath();ctx.arc(x+r*0.8,y+r*0.2,r*0.7,0,Math.PI*2);ctx.fill();
  ctx.beginPath();ctx.arc(x-r*0.3,y-r*0.5,r*0.65,0,Math.PI*2);ctx.fill();
  ctx.beginPath();ctx.arc(x+r*0.3,y-r*0.5,r*0.65,0,Math.PI*2);ctx.fill();
}

function drawRainDrops(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#6fb8ff';
  for(var i=-1;i<=1;i++){
    ctx.beginPath();
    ctx.ellipse(x+i*r*0.7,y+i*r*0.3,Math.max(1,r*0.1),Math.max(1,r*0.28),0,0,Math.PI*2);
    ctx.fill();
  }
}

function drawSnowflakes(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#E0E8F0';
  for(var i=-1;i<=1;i++){
    ctx.beginPath();ctx.arc(x+i*r*0.7,y+i*r*0.3,Math.max(1,r*0.16),0,Math.PI*2);ctx.fill();
  }
}

function drawHailPellets(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#DCEBFA';
  ctx.strokeStyle='rgba(105,135,170,0.85)';
  ctx.lineWidth=Math.max(0.75,r*0.08);
  for(var i=-1;i<=1;i++){
    ctx.beginPath();ctx.arc(x+i*r*0.68,y+i*r*0.24,Math.max(1.2,r*0.16),0,Math.PI*2);ctx.fill();ctx.stroke();
  }
}

function drawBolt(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.fillStyle='#FFD93D';
  ctx.beginPath();
  ctx.moveTo(x,y-r*0.5);
  ctx.lineTo(x-r*0.3,y+r*0.1);
  ctx.lineTo(x+r*0.05,y+r*0.05);
  ctx.lineTo(x-r*0.1,y+r*0.6);
  ctx.lineTo(x+r*0.3,y-r*0.05);
  ctx.lineTo(x-r*0.05,y);
  ctx.closePath();ctx.fill();
}

function drawFogIcon(ctx,x,y,r){
  r=Math.max(1,r);
  ctx.strokeStyle='rgba(200,210,220,0.8)';
  ctx.lineWidth=Math.max(1,r*0.2);
  for(var i=-1;i<=1;i++){
    ctx.beginPath();
    ctx.moveTo(x-r*1.2,y+i*r*0.5);
    ctx.lineTo(x+r*1.2,y+i*r*0.5);
    ctx.stroke();
  }
}


// ── drawWeatherIcon() ────────────────────────────────────────────────────────
// Draws the compound icon for a WMO weather code into ctx. Maps code → bucket
// → appropriate combination of primitives. The `isDay` flag switches between
// sun and moon for clear/partly-cloudy conditions.
function drawWeatherIcon(ctx,code,isDay,sz){
  ctx.save();ctx.clearRect(0,0,sz,sz);
  var cx=sz/2,cy=sz/2,r=sz*0.15;
  var bk=wmoBucket(code);
  if(bk==='clear'){
    if(isDay)drawSun(ctx,cx,cy,r);else drawMoon(ctx,cx,cy,r);
  }else if(bk==='partly'){
    if(isDay){drawSun(ctx,cx-r*0.6,cy-r*0.3,r*0.7);drawCloud(ctx,cx+r*0.3,cy+r*0.2,r*1.1);}
    else{drawMoon(ctx,cx-r*0.6,cy-r*0.3,r*0.7);drawCloud(ctx,cx+r*0.3,cy+r*0.2,r*1.1);}
  }else if(bk==='overcast'){
    drawCloud(ctx,cx,cy,r*1.3);
  }else if(bk==='fog'){
    drawFogIcon(ctx,cx,cy,r);
  }else if(bk==='rain'){
    drawCloud(ctx,cx,cy-r*0.3,r*1.1);drawRainDrops(ctx,cx,cy+r*0.5,r);
  }else if(bk==='snow'){
    drawCloud(ctx,cx,cy-r*0.3,r*1.1);drawSnowflakes(ctx,cx,cy+r*0.5,r);
  }else if(bk==='freezing'){
    drawCloud(ctx,cx,cy-r*0.3,r*1.1);drawRainDrops(ctx,cx-r*0.5,cy+r*0.5,r*0.8);drawSnowflakes(ctx,cx+r*0.5,cy+r*0.5,r*0.8);
  }else if(bk==='thunder'){
    drawCloud(ctx,cx,cy-r*0.3,r*1.2);drawBolt(ctx,cx,cy+r*0.6,r);
  }else if(bk==='hail'){
    drawCloud(ctx,cx,cy-r*0.35,r*1.2);drawBolt(ctx,cx-r*0.35,cy+r*0.35,r*0.72);drawHailPellets(ctx,cx+r*0.28,cy+r*0.68,r*0.82);
  }
  ctx.restore();
}


// ── getIconCanvas() ──────────────────────────────────────────────────────────
// Returns a cached 48×48 off-screen canvas for the given weather code and
// day/night flag. Draws the icon on first request and caches in S.iconCache
// so repeated calls (e.g., per forecast day per frame) are O(1) lookups.
function getIconCanvas(code,isDay){
  var key=code+'_'+(isDay?'d':'n');
  if(S.iconCache[key])return S.iconCache[key];
  var c=document.createElement('canvas');c.width=48;c.height=48;
  drawWeatherIcon(c.getContext('2d'),code,isDay,48);
  S.iconCache[key]=c;return c;
}
