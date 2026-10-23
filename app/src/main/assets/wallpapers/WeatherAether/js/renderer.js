'use strict';

// ── rebuildParticles() ───────────────────────────────────────────────────────
// Constructs the particle pool for the current weather type. Called when
// weatherBucket changes (rain → clear, etc.) or on resize. Particle types:
//   rain/thunder  — fast diagonal lines
//   snow          — slow drifting circles with sinusoidal horizontal wobble
//   sleet         — mixed: fast lines (rain) + slower circles (ice pellets)
//   clear night   — stationary twinkling stars
// showParticles:false clears precipitation and stars; atmospheric cloud forms
// remain because they communicate the condition rather than decorate it.
function seededRandom(seed){
  return function(){
    seed=(seed*1664525+1013904223)>>>0;
    return seed/4294967296;
  };
}

// Builds one reusable cloud image as a unified silhouette, then shades that
// mask as a single volume. Internal lobes are deliberately opaque; softness is
// confined to the outer edge so clouds do not resemble translucent foam.
function buildCloudSprite(seed,density,lighting){
  var canvas=document.createElement('canvas');
  canvas.width=420;canvas.height=180;
  var ctx=canvas.getContext('2d');
  var rand=seededRandom(seed);
  var highlight=lighting.highlight;
  var middle=lighting.middle;
  var shadow=lighting.shadow;

  // Build one solid alpha mask. Fewer, wider lobes create coherent cloud
  // masses instead of a chain of similarly sized bubbles.
  ctx.fillStyle='#fff';
  ctx.shadowColor='rgba(255,255,255,0.72)';
  ctx.shadowBlur=13;
  ctx.beginPath();ctx.ellipse(210,112,176,46,0,0,Math.PI*2);ctx.fill();
  for(var i=0;i<7;i++){
    var x=60+rand()*300;
    var centerBias=1-Math.abs(x-210)/210;
    var y=103-rand()*30-centerBias*25;
    var rx=43+rand()*35+centerBias*14;
    var ry=30+rand()*22+centerBias*12;
    ctx.beginPath();ctx.ellipse(x,y,rx,ry,0,0,Math.PI*2);ctx.fill();
  }
  ctx.shadowBlur=0;

  // Color the complete mask with one top-to-bottom light field. source-in
  // preserves the unified alpha while eliminating internal overlap seams.
  ctx.globalCompositeOperation='source-in';
  var volume=ctx.createLinearGradient(0,42,0,156);
  volume.addColorStop(0,rgb(highlight));
  volume.addColorStop(0.56,rgb(middle));
  volume.addColorStop(1,rgb(shadow));
  ctx.fillStyle=volume;ctx.fillRect(0,0,canvas.width,canvas.height);

  // One broad highlight suggests illumination without revealing construction.
  ctx.globalCompositeOperation='source-atop';
  var lightX=canvas.width*lighting.sourceX;
  var light=ctx.createRadialGradient(lightX,52,8,lightX,76,170);
  light.addColorStop(0,'rgba(255,255,255,'+lighting.highlightAlpha+')');
  light.addColorStop(1,'rgba(255,255,255,0)');
  ctx.fillStyle=light;ctx.fillRect(0,0,canvas.width,canvas.height);
  ctx.globalCompositeOperation='source-over';
  return canvas;
}

function refreshCloudSprites(atmosphere,now){
  var lighting=getCloudLighting(atmosphere,now);
  S.cloudSprites=[];
  for(var i=0;i<4;i++)S.cloudSprites.push(buildCloudSprite(1701+i*7919,atmosphere.cloudDensity,lighting));
  S.cloudLightingKey=getCloudLightingKey(atmosphere,now);
}

function rebuildClouds(w,h,atmosphere){
  S.clouds=[];S.cloudSprites=[];S.cloudLightingKey=null;
  // Ground-level fog and snowfall read as continuous atmospheric extinction,
  // not discrete cloud bodies. Snow keeps its dense overcast sky, but visible
  // cloud forms would compete with the flakes and flatten the scene into layers.
  if(atmosphere.cloudCover<0.10||atmosphere.condition==='fog'||atmosphere.condition==='snow')return;
  refreshCloudSprites(atmosphere,wallpaperNow());
  var count=Math.round(3+atmosphere.cloudCover*9);
  var rand=seededRandom(8137+Math.round(atmosphere.cloudCover*1000));
  for(var i=0;i<count;i++){
    var depth=0.28+rand()*0.72;
    var width=w*(0.20+rand()*0.22)*(0.78+depth*0.34);
    S.clouds.push({
      x:-width+rand()*(w+width*2),
      y:h*(0.03+rand()*0.40),
      width:width,height:width*(180/420),depth:depth,
      speed:3+depth*8+atmosphere.motionEnergy*7,
      opacity:Math.min(0.98,0.70+atmosphere.cloudDensity*0.22+depth*0.05),
      sprite:i%S.cloudSprites.length
    });
  }
}

function rebuildParticles(){
  S.particles=[];
  S.stars=[];
  S.particleType=null;
  S.fogAlpha=0;
  var w=cssW(),h=cssH(),bk=S.weatherBucket;
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  rebuildClouds(w,h,atmosphere);
  if(!S.props.showParticles)return;
  S.particleType=bk;
  if(bk==='rain'||bk==='thunder'){
    var rainCount=Math.round(70+atmosphere.precipitation*160);
    for(var i=0;i<rainCount;i++){
      var depth=Math.pow(Math.random(),0.72);
      var near=depth>0.92?1.35:1;
      S.particles.push({x:Math.random()*w,y:Math.random()*h,depth:depth,
        speed:180+depth*620,len:(6+depth*23)*near,width:0.45+depth*1.05,
        op:0.055+depth*0.36});
    }
  }else if(bk==='snow'){
    var snowCount=Math.round(60+atmosphere.precipitation*140);
    for(var i=0;i<snowCount;i++){
      var depth=Math.pow(Math.random(),0.82);
      S.particles.push({x:Math.random()*w,y:Math.random()*h,depth:depth,
        speed:10+depth*66,sz:0.55+depth*3.5,wb:Math.random()*Math.PI*2,
        wbs:0.55+Math.random()*1.8,wobble:7+depth*19,op:0.10+depth*0.58});
    }
  }else if(bk==='sleet'){
    var sleetCount=Math.round(65+atmosphere.precipitation*135);
    for(var i=0;i<sleetCount;i++){
      var depth=Math.pow(Math.random(),0.78);
      var ice=Math.random()<0.38;
      S.particles.push(ice?
        {x:Math.random()*w,y:Math.random()*h,depth:depth,speed:75+depth*230,sz:0.6+depth*2.5,op:0.10+depth*0.42,ice:true}:
        {x:Math.random()*w,y:Math.random()*h,depth:depth,speed:155+depth*440,len:4+depth*13,width:0.4+depth*0.8,op:0.06+depth*0.30,ice:false});
    }
  }
  if((bk==='clear'||bk==='partly')&&!S.isDay){
    S.stars=[];
    var starCnt=bk==='clear'?90:32;
    for(var i=0;i<starCnt;i++){
      S.stars.push({x:Math.random()*w,y:Math.random()*h*0.65,sz:0.5+Math.random()*1.5,tw:Math.random()*Math.PI*2,tws:1+Math.random()*2.5});
    }
  }
}


// ── updateParticles() ────────────────────────────────────────────────────────
// Advances particle positions by `dt` seconds scaled by animationSpeed. Each
// depth layer has distinct speed, scale, opacity, and wind coupling. Particles
// that exit the canvas are recycled back to a random position at the top.
// Lightning alpha decays exponentially each frame; fog alpha ramps to target.
// Weather APIs report the direction wind comes FROM, clockwise from north.
// Convert that to the horizontal component of the direction precipitation
// travels TOWARD. Missing/northerly wind produces essentially vertical fall.
function precipitationWindDrift(atmosphere){
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  return Math.sin(toward)*(24+atmosphere.motionEnergy*40);
}

function precipitationGust(atmosphere){
  var energy=atmosphere.motionEnergy;
  var slow=Math.sin(S.motionTime*0.37);
  var fast=Math.sin(S.motionTime*1.13+1.7);
  return Math.max(0.62,0.88+energy*(0.17*slow+0.09*fast));
}

function updateClouds(dt,atmosphere,w){
  if(S.clouds.length===0)return;
  var now=wallpaperNow();
  if(S.cloudLightingKey!==getCloudLightingKey(atmosphere,now))refreshCloudSprites(atmosphere,now);
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  var direction=Math.sin(toward);
  if(Math.abs(direction)<0.15)direction=0.15;
  for(var i=0;i<S.clouds.length;i++){
    var cloud=S.clouds[i];
    cloud.x+=cloud.speed*direction*S.props.animationSpeed*dt;
    if(direction>0&&cloud.x>w+cloud.width*0.5)cloud.x=-cloud.width*1.05;
    if(direction<0&&cloud.x<-cloud.width*1.05)cloud.x=w+cloud.width*0.5;
  }
}

function updateParticles(dt){
  var w=cssW(),h=cssH(),atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  S.motionTime+=dt;
  S.windGust=precipitationGust(atmosphere);
  updateClouds(dt,atmosphere,w);
  if(!S.props.showParticles)return;
  var spd=S.props.animationSpeed*(0.65+atmosphere.motionEnergy*0.7),bk=S.weatherBucket;
  var windDrift=precipitationWindDrift(atmosphere)*S.windGust;
  if(bk==='rain'||bk==='thunder'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      p.y+=p.speed*spd*dt;
      p.x+=windDrift*(0.42+p.depth*0.78)*spd*dt;
      if(p.y>h){p.y=-p.len;p.x=Math.random()*w;}
      if(p.x<-10)p.x=w+10;
      if(p.x>w+10)p.x=-10;
    }
  }else if(bk==='snow'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      p.wb+=p.wbs*spd*dt;
      p.y+=p.speed*spd*dt;
      p.x+=(Math.sin(p.wb)*p.wobble+windDrift*p.depth*0.22)*spd*dt;
      if(p.y>h){p.y=-p.sz;p.x=Math.random()*w;}
      if(p.x<-10)p.x=w+10;
      if(p.x>w+10)p.x=-10;
    }
  }else if(bk==='sleet'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      p.y+=p.speed*spd*dt;
      p.x+=windDrift*(p.ice?0.42:0.55+p.depth*0.55)*spd*dt;
      var bot=p.ice?h:h+(p.len||0);
      if(p.y>bot){p.y=p.ice?-p.sz:-p.len;p.x=Math.random()*w;}
      if(p.x<-10)p.x=w+10;
      if(p.x>w+10)p.x=-10;
    }
  }
  for(var i=0;i<S.stars.length;i++){
    S.stars[i].tw+=S.stars[i].tws*spd*dt;
  }
  if(bk==='thunder'){
    S.lightningCooldown-=dt;
    if(S.lightningCooldown<=0&&Math.random()<0.03*spd){
      S.lightningAlpha=0.6+Math.random()*0.4;
      S.lightningCooldown=2+Math.random()*6;
    }
    S.lightningAlpha*=Math.pow(0.85,dt*60);
    if(S.lightningAlpha<0.005)S.lightningAlpha=0;
  }else{S.lightningAlpha=0;}
  if(bk==='fog'){
    if(S.fogAlpha<0.28)S.fogAlpha=Math.min(0.28,S.fogAlpha+0.004);
  }else{S.fogAlpha=0;}
}


// ── drawParticles() ──────────────────────────────────────────────────────────
// Renders all particles, stars, lightning flash, and fog overlay for the
// current frame. Each weather type uses a different drawing primitive:
// rain=strokeLine, snow=arc, sleet=mixed, stars=arc+twinkle.
// Lightning: full-canvas white fillRect at lightningAlpha opacity.
// Fog: full-canvas grey fillRect at fogAlpha opacity.
function drawClouds(ctx){
  for(var i=0;i<S.clouds.length;i++){
    var cloud=S.clouds[i],sprite=S.cloudSprites[cloud.sprite];
    if(!sprite)continue;
    ctx.save();ctx.globalAlpha=cloud.opacity;
    ctx.drawImage(sprite,cloud.x,cloud.y,cloud.width,cloud.height);
    ctx.restore();
  }
}

function drawParticles(ctx){
  var bk=S.weatherBucket;
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var windDrift=precipitationWindDrift(atmosphere)*S.windGust;
  var light=getPrecipitationLighting(atmosphere,wallpaperNow());
  // Stars sit behind cloud cover; precipitation sits in front of it.
  for(var i=0;i<S.stars.length;i++){
    var s=S.stars[i];
    var tw=0.25+0.75*Math.abs(Math.sin(s.tw));
    ctx.beginPath();ctx.arc(s.x,s.y,Math.max(0.3,s.sz),0,Math.PI*2);
    ctx.fillStyle=rgba([255,255,240],tw);ctx.fill();
  }
  drawClouds(ctx);
  if(!S.props.showParticles)return;
  if(bk==='rain'||bk==='thunder'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      ctx.beginPath();
      ctx.moveTo(p.x,p.y);
      ctx.lineTo(p.x+windDrift*(0.42+p.depth*0.78)*(p.len/p.speed),p.y+p.len);
      ctx.strokeStyle=rgba(light.rain,p.op);
      ctx.lineWidth=p.width;
      ctx.stroke();
    }
  }else if(bk==='snow'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      ctx.beginPath();
      ctx.arc(p.x,p.y,Math.max(0.5,p.sz),0,Math.PI*2);
      var lowerLight=0.86+0.20*Math.max(0,Math.min(1,p.y/cssH()));
      ctx.fillStyle=rgba(light.snow,Math.min(0.9,p.op*lowerLight));
      ctx.fill();
    }
  }else if(bk==='sleet'){
    for(var i=0;i<S.particles.length;i++){
      var p=S.particles[i];
      if(p.ice){
        ctx.beginPath();ctx.arc(p.x,p.y,Math.max(0.5,p.sz),0,Math.PI*2);
        ctx.fillStyle=rgba(light.sleet,p.op);ctx.fill();
      }else{
        ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(p.x+windDrift*(0.55+p.depth*0.55)*(p.len/p.speed),p.y+p.len);
        ctx.strokeStyle=rgba(light.sleet,p.op);ctx.lineWidth=p.width;ctx.stroke();
      }
    }
  }
  if(S.lightningAlpha>0.005){
    ctx.fillStyle=rgba([255,255,255],S.lightningAlpha);
    ctx.fillRect(0,0,cssW(),cssH());
  }
  if(S.fogAlpha>0.005){
    ctx.fillStyle=rgba([180,185,195],S.fogAlpha);
    ctx.fillRect(0,0,cssW(),cssH());
  }
}


// Paints the atmosphere as a set of inexpensive large-scale light fields.
// The base timeline supplies time-of-day color; atmospheric state controls
// diffusion and haze; the celestial track places a broad source of airlight.
// This remains Canvas 2D so it is also the eventual low-power fallback.
function drawAtmosphericSky(ctx,w,h){
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var paintKey=[w,h,Math.floor(Date.now()/15000),atmosphere.condition,S.isDay].join(':');
  var paint=S.skyPaintCache;
  if(!paint||paint.key!==paintKey){
    var colors=getPhaseColors();
    var track=getCelestialTrack(wallpaperNow(),S.isDay);

    var sky=ctx.createLinearGradient(0,0,0,h);
    sky.addColorStop(0,rgb(colors.top));
    sky.addColorStop(0.46,rgb(colors.mid));
    sky.addColorStop(0.82,rgb(lerpC(colors.mid,colors.bot,0.72)));
    sky.addColorStop(1,rgb(colors.bot));

    // A low sun warms illuminated air near the horizon. At midday the same
    // field becomes broad white-blue airlight rather than an orange overlay.
    var sourceX=track.x*w;
    var sourceY=Math.max(h*0.34,track.y*h);
    var radius=Math.max(w,h)*(0.48+atmosphere.lightDiffusion*0.20);
    var warm=track.horizonWarmth;
    var lightColor=lerpC([225,238,255],[255,155,72],warm);
    var lightAlpha=(0.055+atmosphere.lightDiffusion*0.09)*(S.isDay?1:0.34);
    var airlight=ctx.createRadialGradient(sourceX,sourceY,0,sourceX,sourceY,radius);
    airlight.addColorStop(0,rgba(lightColor,lightAlpha));
    airlight.addColorStop(0.42,rgba(lightColor,lightAlpha*0.48));
    airlight.addColorStop(1,rgba(lightColor,0));

    // Horizon extinction adds distance without introducing scenery or a hard
    // horizon line. Dense air lifts and softens the bottom of the visual field.
    var hazeAlpha=0.025+atmosphere.horizonHaze*0.16;
    var haze=ctx.createLinearGradient(0,h*0.54,0,h);
    haze.addColorStop(0,'rgba(220,225,232,0)');
    haze.addColorStop(1,'rgba(220,225,232,'+hazeAlpha+')');

    // Barely perceptible edge falloff focuses the scene and removes the flat,
    // uniformly illuminated quality of a simple full-screen gradient.
    var vignette=ctx.createRadialGradient(w*0.5,h*0.48,Math.min(w,h)*0.22,w*0.5,h*0.48,Math.max(w,h)*0.78);
    vignette.addColorStop(0,'rgba(5,10,22,0)');
    vignette.addColorStop(1,'rgba(5,10,22,'+(0.035+atmosphere.cloudDensity*0.035)+')');
    paint=S.skyPaintCache={key:paintKey,sky:sky,airlight:airlight,haze:haze,vignette:vignette};
  }

  ctx.fillStyle=paint.sky;
  ctx.fillRect(0,0,w,h);
  ctx.fillStyle=paint.airlight;
  ctx.fillRect(0,0,w,h);
  ctx.fillStyle=paint.haze;
  ctx.fillRect(0,h*0.54,w,h*0.46);
  ctx.fillStyle=paint.vignette;
  ctx.fillRect(0,0,w,h);
}


// ── getLunarPhase() ──────────────────────────────────────────────────────────
// Returns the current lunar phase as a value 0–1 (0 = new moon, 0.5 = full
// moon) using the Julian Day Number formula. Used by drawBgMoon() to render
// the correct crescent/gibbous phase rather than always drawing a full moon.
function getLunarPhase(){
  var JD=Date.now()/86400000+2440587.5;
  var age=(JD-2451549.5)%29.53058867;
  if(age<0)age+=29.53058867;
  return age/29.53058867; // 0=new, 0.5=full
}


// ── drawBgSun() / drawBgMoon() / drawBgCelestial() ──────────────────────────
// Render the large background sun or moon disc in the sky canvas. These are
// separate from the small card icons — they're the ambient light sources
// painted directly behind the city/sky gradient, not inside the UI card.
//
// drawBgSun: white-hot disc with atmosphere-scaled diffusion.
// drawBgMoon: cached shaded lunar surface clipped to the current phase.
// drawBgCelestial: dispatcher — chooses sun or moon based on S.isDay,
//   and fades the body out for non-clear conditions (overcast hides the sun).
function drawBgSun(ctx,cx,cy,r,t){
  r=Math.max(4,r);
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var diffusion=atmosphere.lightDiffusion;
  var corona=ctx.createRadialGradient(cx,cy,r*0.55,cx,cy,r*(5.5+diffusion*3));
  corona.addColorStop(0,'rgba(255,250,220,'+(0.34+diffusion*0.10)+')');
  corona.addColorStop(0.18,'rgba(255,222,150,'+(0.16+diffusion*0.08)+')');
  corona.addColorStop(0.55,'rgba(255,190,105,0.045)');
  corona.addColorStop(1,'rgba(255,170,80,0)');
  ctx.fillStyle=corona;ctx.beginPath();ctx.arc(cx,cy,r*(5.5+diffusion*3),0,Math.PI*2);ctx.fill();

  var sg=ctx.createRadialGradient(cx-r*0.16,cy-r*0.16,r*0.05,cx,cy,r);
  sg.addColorStop(0,'rgb(255,255,250)');
  sg.addColorStop(0.72,'rgb(255,250,222)');
  sg.addColorStop(0.93,'rgb(255,228,168)');
  sg.addColorStop(1,'rgba(255,205,115,0.82)');
  ctx.fillStyle=sg;ctx.beginPath();ctx.arc(cx,cy,r,0,Math.PI*2);ctx.fill();
}

function getMoonTexture(){
  if(S.moonTexture)return S.moonTexture;
  var canvas=document.createElement('canvas');canvas.width=192;canvas.height=192;
  var ctx=canvas.getContext('2d'),center=96,radius=88;
  var surface=ctx.createRadialGradient(68,58,8,96,96,radius);
  surface.addColorStop(0,'rgb(232,232,216)');
  surface.addColorStop(0.62,'rgb(198,200,190)');
  surface.addColorStop(0.88,'rgb(151,155,151)');
  surface.addColorStop(1,'rgb(91,96,98)');
  ctx.fillStyle=surface;ctx.beginPath();ctx.arc(center,center,radius,0,Math.PI*2);ctx.fill();

  ctx.save();ctx.beginPath();ctx.arc(center,center,radius,0,Math.PI*2);ctx.clip();
  var rand=seededRandom(2903);
  for(var i=0;i<22;i++){
    var angle=rand()*Math.PI*2,dist=Math.sqrt(rand())*radius*0.78;
    var x=center+Math.cos(angle)*dist,y=center+Math.sin(angle)*dist;
    var crater=2.5+rand()*8.5,flatten=0.62+rand()*0.28;
    ctx.fillStyle='rgba(52,58,62,'+(0.07+rand()*0.09)+')';
    ctx.beginPath();ctx.ellipse(x,y,crater,crater*flatten,rand()*0.5,0,Math.PI*2);ctx.fill();
    ctx.strokeStyle='rgba(255,255,244,0.055)';ctx.lineWidth=1;
    ctx.beginPath();ctx.ellipse(x-1,y-1,crater,crater*flatten,rand()*0.5,Math.PI,Math.PI*2);ctx.stroke();
  }
  ctx.restore();
  S.moonTexture=canvas;return canvas;
}

function drawMoonTextureRegion(ctx,texture,cx,cy,r){
  ctx.save();ctx.clip();ctx.drawImage(texture,cx-r,cy-r,r*2,r*2);ctx.restore();
}

function drawBgMoon(ctx,cx,cy,r){
  r=Math.max(4,r);
  var phase=getLunarPhase(); // 0=new, 0.5=full
  var texture=getMoonTexture();
  var grd=ctx.createRadialGradient(cx,cy,r*0.72,cx,cy,r*3.8);
  grd.addColorStop(0,'rgba(218,225,218,0.20)');
  grd.addColorStop(0.35,'rgba(190,205,205,0.065)');
  grd.addColorStop(1,'rgba(170,190,200,0)');
  ctx.fillStyle=grd;ctx.beginPath();ctx.arc(cx,cy,r*3.8,0,Math.PI*2);ctx.fill();

  ctx.save();
  ctx.beginPath();ctx.arc(cx,cy,r,0,Math.PI*2);ctx.clip();
  ctx.globalAlpha=0.16;ctx.drawImage(texture,cx-r,cy-r,r*2,r*2);ctx.globalAlpha=1;
  ctx.fillStyle='rgba(5,9,19,0.72)';ctx.fillRect(cx-r,cy-r,r*2,r*2);
  if(phase>0.02&&phase<0.98){
    if(phase<=0.5){
      // waxing: lit on right
      ctx.beginPath();ctx.arc(cx,cy,r,-Math.PI/2,Math.PI/2);ctx.closePath();drawMoonTextureRegion(ctx,texture,cx,cy,r);
      var ex=Math.cos(phase*Math.PI*2)*r;
      if(ex>0.5){
        // crescent: dark ellipse over right side
        ctx.fillStyle='rgba(5,9,19,0.90)';
        ctx.beginPath();ctx.ellipse(cx,cy,ex,r,0,-Math.PI/2,Math.PI/2);ctx.closePath();ctx.fill();
      }else if(ex<-0.5){
        // gibbous: add lit ellipse on left
        ctx.beginPath();ctx.ellipse(cx,cy,-ex,r,0,Math.PI/2,-Math.PI/2);ctx.closePath();drawMoonTextureRegion(ctx,texture,cx,cy,r);
      }
    }else{
      // waning: lit on left
      ctx.beginPath();ctx.arc(cx,cy,r,Math.PI/2,-Math.PI/2);ctx.closePath();drawMoonTextureRegion(ctx,texture,cx,cy,r);
      var ex=Math.cos(phase*Math.PI*2)*r;
      if(ex<-0.5){
        // gibbous: add lit ellipse on right
        ctx.beginPath();ctx.ellipse(cx,cy,-ex,r,0,-Math.PI/2,Math.PI/2);ctx.closePath();drawMoonTextureRegion(ctx,texture,cx,cy,r);
      }else if(ex>0.5){
        // crescent: dark ellipse over left side
        ctx.fillStyle='rgba(5,9,19,0.90)';
        ctx.beginPath();ctx.ellipse(cx,cy,ex,r,0,Math.PI/2,-Math.PI/2);ctx.closePath();ctx.fill();
      }
    }
  }
  ctx.restore();
}

function drawBgCelestial(ctx,w,h,t){
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var op=atmosphere.celestialVisibility;
  if(op===0)return;
  var track=getCelestialTrack(wallpaperNow(),S.isDay);
  ctx.save();ctx.globalAlpha=op;
  var r=Math.min(w,h)*(S.isDay?0.030:0.034);
  if(S.isDay)drawBgSun(ctx,w*track.x,h*track.y,r,t);
  else drawBgMoon(ctx,w*track.x,h*track.y,r);
  ctx.restore();
}


// ── setupVisibility() ────────────────────────────────────────────────────────
// Pauses the rAF loop when the page is hidden (e.g., display turned off or
// wallpaper covered by a full-screen app) and resumes it when visible again.
// This prevents unnecessary CPU/GPU usage when the wallpaper isn't visible.
// Uses the Page Visibility API (document.hidden / 'visibilitychange').
function setupVisibility(){
  document.addEventListener('visibilitychange',function(){
    if(document.hidden){
      if(S.animationFrameId){cancelAnimationFrame(S.animationFrameId);S.animationFrameId=null;}
    }else{
      if(!S.animationFrameId){S.lastFrameTime=0;S.animationFrameId=requestAnimationFrame(render);}
    }
  });
}

function resizeCanvas(){
  var dpr=window.devicePixelRatio||1;
  var w=window.innerWidth,h=window.innerHeight;
  S.canvas.width=w*dpr;S.canvas.height=h*dpr;
  S.canvas.style.width=w+'px';S.canvas.style.height=h+'px';
  S.ctx.setTransform(dpr,0,0,dpr,0,0);
  S.skyPaintCache=null;
  rebuildParticles();
}


// ── render() — main animation loop ───────────────────────────────────────────
// Called every frame via requestAnimationFrame. Computes elapsed time (dt),
// advances particle physics, and paints the sky gradient + particles to the
// canvas. Does NOT update the DOM UI — that happens only in applyWeather() /
// renderUI() when data changes. Separating data updates from frame rendering
// keeps the render loop lean and avoids DOM thrashing every 33ms.
function render(timestamp){
  if(!timestamp)timestamp=performance.now();
  var dt=S.lastFrameTime?Math.min((timestamp-S.lastFrameTime)/1000,0.1):0.016;
  S.lastFrameTime=timestamp;
  var w=cssW(),h=cssH(),ctx=S.ctx;
  drawAtmosphericSky(ctx,w,h);
  drawBgCelestial(ctx,w,h,timestamp);
  updateParticles(dt);
  drawParticles(ctx);
  if(!document.hidden){
    S.animationFrameId=requestAnimationFrame(render);
  }
}
