// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Stephen de la Vega
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

'use strict';

var THREE_ATMOSPHERE_REVISION='precipitation-modulation-1';
var THREE_QUALITY_PROFILES={
  eco:{name:'eco',pixelRatio:0.55,fps:15,motionFps:30,steps:4,rainCount:80,freezingCount:80,snowCount:70,hailCount:100},
  balanced:{name:'balanced',pixelRatio:0.85,fps:24,motionFps:60,steps:7,rainCount:160,freezingCount:160,snowCount:130,hailCount:190},
  showcase:{name:'showcase',pixelRatio:1.15,fps:30,motionFps:60,steps:9,rainCount:240,freezingCount:240,snowCount:200,hailCount:280}
};

function getThreeQualityProfile(){
  var requested=new URLSearchParams(window.location.search).get('quality');
  return THREE_QUALITY_PROFILES[requested]||THREE_QUALITY_PROFILES.balanced;
}

var THREE_SKY_VERTEX=[
  'varying vec2 vUv;',
  'void main(){',
  '  vUv=uv;',
  '  gl_Position=vec4(position.xy,0.0,1.0);',
  '}'
].join('\n');

var THREE_SKY_FRAGMENT=[
  'precision mediump float;',
  'varying vec2 vUv;',
  'uniform vec3 uTop;',
  'uniform vec3 uMiddle;',
  'uniform vec3 uBottom;',
  'uniform vec3 uLightColor;',
  'uniform vec2 uLightPosition;',
  'uniform vec2 uViewportScale;',
  'uniform float uHaze;',
  'uniform float uDiffusion;',
  'uniform float uLightAlpha;',
  'uniform float uCloudDensity;',
  'uniform float uMinRatio;',
  'uniform float uStarVisibility;',
  'uniform float uCloudCover;',
  'uniform float uCloudVolumeEnabled;',
  'uniform vec2 uCloudWind;',
  'uniform vec3 uCloudHighlight;',
  'uniform vec3 uCloudShadow;',
  'uniform vec3 uCloudLightOffset;',
  'uniform float uCloudLightBase;',
  'uniform float uCloudLightStrength;',
  'uniform float uCloudTopLight;',
  'uniform float uCloudDepthShadow;',
  'uniform float uCloudSkyBounce;',
  'uniform float uCloudSteps;',
  'uniform float uTime;',
  'float hash(vec2 p){',
  '  return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453123);',
  '}',
  'float hash3(vec3 p){',
  '  p=fract(p*0.1031);',
  '  p+=dot(p,p.yzx+33.33);',
  '  return fract((p.x+p.y)*p.z);',
  '}',
  'float noise3(vec3 p){',
  '  vec3 i=floor(p);',
  '  vec3 f=fract(p);',
  '  f=f*f*(3.0-2.0*f);',
  '  return mix(mix(mix(hash3(i),hash3(i+vec3(1.0,0.0,0.0)),f.x),mix(hash3(i+vec3(0.0,1.0,0.0)),hash3(i+vec3(1.0,1.0,0.0)),f.x),f.y),mix(mix(hash3(i+vec3(0.0,0.0,1.0)),hash3(i+vec3(1.0,0.0,1.0)),f.x),mix(hash3(i+vec3(0.0,1.0,1.0)),hash3(i+vec3(1.0,1.0,1.0)),f.x),f.y),f.z);',
  '}',
  'float cloudNoise(vec3 p){',
  '  return noise3(p)*0.68+noise3(p*2.07+7.4)*0.32;',
  '}',
  'void main(){',
  '  float lower=smoothstep(0.0,0.52,vUv.y);',
  '  float upper=smoothstep(0.42,1.0,vUv.y);',
  '  vec3 color=mix(uBottom,uMiddle,lower);',
  '  color=mix(color,uTop,upper);',
  '  vec2 starGrid=vec2(180.0,100.0);',
  '  vec2 starCell=floor(vUv*starGrid);',
  '  vec2 starLocal=fract(vUv*starGrid)-0.5;',
  '  float starSeed=hash(starCell);',
  '  float starRadius=mix(0.035,0.105,hash(starCell+17.3));',
  '  float starPoint=(1.0-smoothstep(starRadius,starRadius*2.1,length(starLocal)))*step(0.9945,starSeed);',
  '  float twinkle=0.62+0.38*sin(uTime*(0.7+hash(starCell+4.1)*1.8)+starSeed*20.0);',
  '  float horizonFade=smoothstep(0.10,0.42,vUv.y);',
  '  color+=vec3(0.84,0.89,1.0)*starPoint*twinkle*horizonFade*uStarVisibility;',
  '  float hazeField=1.0-smoothstep(0.0,0.46,vUv.y);',
  '  float hazeAlpha=(0.025+uHaze*0.16)*hazeField;',
  '  color=mix(color,vec3(0.863,0.882,0.910),hazeAlpha);',
  '  vec2 lightDelta=(vUv-uLightPosition)*uViewportScale;',
  '  float lightRadius=0.48+uDiffusion*0.20;',
  '  float airlight=1.0-smoothstep(0.0,lightRadius,length(lightDelta));',
  '  color=mix(color,uLightColor,airlight*uLightAlpha);',
  '  if(uCloudVolumeEnabled>0.5){',
  '    float aspect=uViewportScale.x/max(0.001,uViewportScale.y);',
  '    vec2 screen=(vUv-vec2(0.5))*vec2(aspect,1.0);',
  '    vec3 ray=normalize(vec3(screen.x*0.88,screen.y*0.65+0.32,1.18));',
  '    vec3 origin=vec3(0.0,-0.46,-1.0);',
  '    vec4 cloud=vec4(0.0);',
  '    float opticalDepth=0.0;',
  '    float threshold=mix(0.74,0.43,uCloudCover);',
  '    if(ray.y>0.025){',
  '      float enter=max(0.15,(-0.14-origin.y)/ray.y);',
  '      float exit=min(6.5,(0.46-origin.y)/ray.y);',
  '      float span=max(0.0,exit-enter);',
  '      for(int i=0;i<9;i++){',
  '        if(float(i)>=uCloudSteps)break;',
  '        float stepT=(float(i)+0.5)/uCloudSteps;',
  '        vec3 samplePos=origin+ray*(enter+span*stepT);',
  '        samplePos.xz+=uCloudWind*uTime*0.018;',
  '        samplePos.y+=sin(uTime*0.019+samplePos.x*0.34)*0.022;',
  '        float vertical=smoothstep(-0.14,0.01,samplePos.y)*(1.0-smoothstep(0.30,0.46,samplePos.y));',
  '        vec3 fieldPos=vec3(samplePos.x*0.82,samplePos.y*2.55,samplePos.z*0.82);',
  '        float field=cloudNoise(fieldPos);',
  '        float density=smoothstep(threshold,threshold+0.16,field)*vertical*uCloudDensity;',
  '        float gradientStep=0.085;',
  '        float gradientX=cloudNoise(fieldPos+vec3(gradientStep,0.0,0.0))-field;',
  '        float gradientY=cloudNoise(fieldPos+vec3(0.0,gradientStep,0.0))-field;',
  '        vec3 surfaceNormal=normalize(vec3(-gradientX,-gradientY,0.075));',
  '        vec3 lightDirection=normalize(uCloudLightOffset);',
  '        float facing=clamp(dot(surfaceNormal,lightDirection)*0.5+0.5,0.0,1.0);',
  '        float highlightLobe=pow(facing,4.0);',
  '        float crown=smoothstep(-0.04,0.36,samplePos.y);',
  '        float transmission=exp(-opticalDepth*(1.35+uCloudDepthShadow*4.0));',
  '        float illumination=uCloudLightBase+facing*uCloudLightStrength*0.55+highlightLobe*uCloudLightStrength*0.18+crown*uCloudTopLight;',
  '        illumination=illumination*mix(0.44,1.0,transmission)+uCloudSkyBounce*(1.0-transmission);',
  '        illumination=clamp(illumination,0.04,1.0);',
  '        vec3 cloudColor=mix(uCloudShadow,uCloudHighlight,illumination);',
  '        float alpha=(1.0-exp(-density*(2.38/uCloudSteps)))*(1.0-cloud.a);',
  '        cloud.rgb+=cloudColor*alpha;',
  '        cloud.a+=alpha;',
  '        opticalDepth+=density*(1.12/uCloudSteps);',
  '      }',
  '    }',
  '    color=mix(color,cloud.rgb/max(cloud.a,0.001),cloud.a);',
  '  }',
  '  vec2 edgeDelta=(vUv-vec2(0.5,0.52))*uViewportScale;',
  '  float vignette=smoothstep(uMinRatio*0.22,0.78,length(edgeDelta));',
  '  color*=1.0-vignette*(0.035+uCloudDensity*0.035);',
  '  float grain=hash(gl_FragCoord.xy+floor(uTime*2.0))-0.5;',
  '  color+=grain/255.0;',
  '  gl_FragColor=vec4(color,1.0);',
  '}'
].join('\n');

var THREE_RAIN_VERTEX=[
  'uniform float uTime;',
  'uniform float uPixelRatio;',
  'uniform float uAspect;',
  'uniform float uWind;',
  'uniform float uSpeed;',
  'uniform float uDropScale;',
  'attribute float aSpeed;',
  'attribute float aLength;',
  'attribute float aOpacity;',
  'attribute float aPhase;',
  'varying float vOpacity;',
  'varying float vWind;',
  'void main(){',
  '  float fall=4.0-mod(aPhase+uTime*aSpeed*uSpeed,8.0);',
  '  vec3 p=position;',
  '  p.y=fall;',
  '  float halfWidth=0.46*(5.0-p.z)*uAspect;',
  '  p.x=p.x*halfWidth+uWind*(4.0-fall)*0.11;',
  '  vec4 viewPosition=modelViewMatrix*vec4(p,1.0);',
  '  float perspective=clamp(2.6/max(0.8,-viewPosition.z),0.38,1.65);',
  '  gl_PointSize=clamp(aLength*uDropScale*uPixelRatio*perspective,1.5,64.0);',
  '  gl_Position=projectionMatrix*viewPosition;',
  '  vOpacity=aOpacity*clamp(perspective,0.36,1.0);',
  '  vWind=uWind;',
  '}'
].join('\n');

var THREE_RAIN_FRAGMENT=[
  'precision mediump float;',
  'uniform vec3 uColor;',
  'uniform float uOpacity;',
  'varying float vOpacity;',
  'varying float vWind;',
  'void main(){',
  '  vec2 drop=gl_PointCoord-vec2(0.5);',
  '  drop.x-=drop.y*vWind*0.16;',
  '  float core=1.0-smoothstep(0.025,0.075,abs(drop.x));',
  '  float ends=1.0-smoothstep(0.39,0.50,abs(drop.y));',
  '  float head=mix(0.72,1.0,smoothstep(-0.48,0.34,drop.y));',
  '  float alpha=core*ends*head*vOpacity*uOpacity;',
  '  if(alpha<0.01)discard;',
  '  gl_FragColor=vec4(uColor,alpha);',
  '}'
].join('\n');

var THREE_FREEZING_VERTEX=[
  'uniform float uTime;',
  'uniform float uPixelRatio;',
  'uniform float uAspect;',
  'uniform float uWind;',
  'uniform float uSpeed;',
  'uniform float uDropScale;',
  'uniform float uIceFraction;',
  'attribute float aSpeed;',
  'attribute float aLength;',
  'attribute float aSize;',
  'attribute float aOpacity;',
  'attribute float aPhase;',
  'attribute float aIce;',
  'varying float vOpacity;',
  'varying float vIce;',
  'varying float vWind;',
  'void main(){',
  '  float fall=4.0-mod(aPhase+uTime*aSpeed*uSpeed,8.0);',
  '  vec3 p=position;',
  '  p.y=fall;',
  '  float halfWidth=0.46*(5.0-p.z)*uAspect;',
  '  p.x=p.x*halfWidth+uWind*(4.0-fall)*0.10;',
  '  vec4 viewPosition=modelViewMatrix*vec4(p,1.0);',
  '  float perspective=clamp(2.6/max(0.8,-viewPosition.z),0.38,1.65);',
  '  vIce=step(aIce,uIceFraction);',
  '  float pointSize=mix(aLength*uDropScale,aSize,vIce);',
  '  gl_PointSize=clamp(pointSize*uPixelRatio*perspective,1.5,48.0);',
  '  gl_Position=projectionMatrix*viewPosition;',
  '  vOpacity=aOpacity*clamp(perspective,0.38,1.0);',
  '  vWind=uWind;',
  '}',
].join('\n');

var THREE_FREEZING_FRAGMENT=[
  'precision mediump float;',
  'uniform vec3 uLiquidColor;',
  'uniform vec3 uIceColor;',
  'uniform float uOpacity;',
  'varying float vOpacity;',
  'varying float vIce;',
  'varying float vWind;',
  'void main(){',
  '  vec2 particle=gl_PointCoord-vec2(0.5);',
  '  vec2 drop=particle;',
  '  drop.x-=drop.y*vWind*0.13;',
  '  float dropCore=1.0-smoothstep(0.026,0.080,abs(drop.x));',
  '  float dropEnds=1.0-smoothstep(0.38,0.50,abs(drop.y));',
  '  float pellet=1.0-smoothstep(0.34,0.50,length(particle));',
  '  float shape=mix(dropCore*dropEnds,pellet,vIce);',
  '  float alpha=shape*vOpacity*uOpacity;',
  '  if(alpha<0.01)discard;',
  '  vec3 color=mix(uLiquidColor,uIceColor,vIce);',
  '  gl_FragColor=vec4(color,alpha);',
  '}',
].join('\n');

var THREE_SNOW_VERTEX=[
  'uniform float uTime;',
  'uniform float uPixelRatio;',
  'uniform float uAspect;',
  'uniform float uWind;',
  'uniform float uSpeed;',
  'uniform float uFlakeScale;',
  'uniform float uFlutter;',
  'attribute float aSpeed;',
  'attribute float aSize;',
  'attribute float aOpacity;',
  'attribute float aPhase;',
  'attribute float aWobble;',
  'varying float vOpacity;',
  'varying float vSparkle;',
  'void main(){',
  '  float fall=4.0-mod(aPhase+uTime*aSpeed*uSpeed,8.0);',
  '  vec3 p=position;',
  '  p.y=fall;',
  '  float halfWidth=0.46*(5.0-p.z)*uAspect;',
  '  float flutter=sin(uTime*aWobble+aPhase*2.7)*(0.07+0.035*aWobble)*uFlutter;',
  '  p.x=p.x*halfWidth+flutter+uWind*(4.0-fall)*0.045;',
  '  vec4 viewPosition=modelViewMatrix*vec4(p,1.0);',
  '  float perspective=clamp(2.6/max(0.8,-viewPosition.z),0.38,1.65);',
  '  gl_PointSize=clamp(aSize*uFlakeScale*uPixelRatio*perspective,1.0,20.0);',
  '  gl_Position=projectionMatrix*viewPosition;',
  '  vOpacity=aOpacity*clamp(perspective,0.42,1.0);',
  '  vSparkle=0.5+0.5*sin(uTime*(0.7+aWobble*0.2)+aPhase);',
  '}'
].join('\n');

var THREE_SNOW_FRAGMENT=[
  'precision mediump float;',
  'uniform vec3 uColor;',
  'uniform float uOpacity;',
  'uniform float uGrain;',
  'varying float vOpacity;',
  'varying float vSparkle;',
  'void main(){',
  '  vec2 flake=gl_PointCoord-vec2(0.5);',
  '  float radius=length(flake);',
  '  float softBody=1.0-smoothstep(0.24,0.50,radius);',
  '  float hardBody=1.0-smoothstep(0.36,0.49,radius);',
  '  float body=mix(softBody,hardBody,uGrain);',
  '  float core=1.0-smoothstep(0.0,0.20,radius);',
  '  float alpha=(body*0.72+core*0.28)*vOpacity*uOpacity;',
  '  if(alpha<0.01)discard;',
  '  vec3 flakeColor=mix(uColor,vec3(1.0),vSparkle*0.10*(1.0-uGrain));',
  '  gl_FragColor=vec4(flakeColor,alpha);',
  '}'
].join('\n');

var THREE_HAIL_VERTEX=[
  'uniform float uTime;',
  'uniform float uPixelRatio;',
  'uniform float uAspect;',
  'uniform float uWind;',
  'uniform float uSpeed;',
  'uniform float uPelletScale;',
  'attribute float aSpeed;',
  'attribute float aSize;',
  'attribute float aOpacity;',
  'attribute float aPhase;',
  'varying float vOpacity;',
  'varying float vNearness;',
  'void main(){',
  '  float fall=4.0-mod(aPhase+uTime*aSpeed*uSpeed,8.0);',
  '  vec3 p=position;',
  '  p.y=fall;',
  '  float halfWidth=0.46*(5.0-p.z)*uAspect;',
  '  p.x=p.x*halfWidth+uWind*(4.0-fall)*0.16;',
  '  vec4 viewPosition=modelViewMatrix*vec4(p,1.0);',
  '  float perspective=clamp(2.6/max(0.8,-viewPosition.z),0.38,1.65);',
  '  gl_PointSize=clamp(aSize*uPelletScale*uPixelRatio*perspective,1.5,20.0);',
  '  gl_Position=projectionMatrix*viewPosition;',
  '  vOpacity=aOpacity*clamp(perspective,0.42,1.0);',
  '  vNearness=perspective;',
  '}',
].join('\n');

var THREE_HAIL_FRAGMENT=[
  'precision mediump float;',
  'uniform vec3 uColor;',
  'uniform float uOpacity;',
  'varying float vOpacity;',
  'varying float vNearness;',
  'void main(){',
  '  vec2 pellet=gl_PointCoord-vec2(0.5);',
  '  float radius=length(pellet);',
  '  float body=1.0-smoothstep(0.34,0.50,radius);',
  '  float rim=smoothstep(0.18,0.38,radius)*(1.0-smoothstep(0.38,0.50,radius));',
  '  float glint=1.0-smoothstep(0.0,0.14,length(pellet-vec2(-0.13,0.14)));',
  '  float alpha=body*vOpacity*uOpacity;',
  '  if(alpha<0.01)discard;',
  '  vec3 color=mix(uColor,vec3(0.56,0.68,0.82),rim*0.42);',
  '  color=mix(color,vec3(1.0),glint*(0.45+vNearness*0.18));',
  '  gl_FragColor=vec4(color,alpha);',
  '}',
].join('\n');

var THREE_PRESENT_FRAGMENT=[
  'precision mediump float;',
  'varying vec2 vUv;',
  'uniform sampler2D uAtmosphere;',
  'void main(){',
  '  gl_FragColor=texture2D(uAtmosphere,vUv);',
  '}'
].join('\n');

function threeColor(c){
  return new WeatherAetherThree.Color(c[0]/255,c[1]/255,c[2]/255);
}

function reportWeatherRendererStatus(){
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var volume=false;
  if(S.threeAtmosphere){
    volume=S.threeAtmosphere.material.uniforms.uCloudVolumeEnabled.value>0.5;
  }
  var status={
    type:'weather-aether-renderer-status',
    renderer:S.renderer||(S.useThreeRenderer?'three':'canvas'),
    revision:THREE_ATMOSPHERE_REVISION,
    quality:S.threeAtmosphere?S.threeAtmosphere.quality.name:getThreeQualityProfile().name,
    targetFps:S.threeAtmosphere?S.threeAtmosphere.quality.fps:getThreeQualityProfile().fps,
    motionTargetFps:S.threeAtmosphere?S.threeAtmosphere.quality.motionFps:getThreeQualityProfile().motionFps,
    steps:S.threeAtmosphere?S.threeAtmosphere.quality.steps:getThreeQualityProfile().steps,
    volume:volume,
    rain:!!(S.threeRain&&S.threeRain.active),
    freezing:!!(S.threeFreezing&&S.threeFreezing.active),
    snow:!!(S.threeSnow&&S.threeSnow.active),
    hail:!!(S.threeHail&&S.threeHail.active),
    precipitationIntensity:atmosphere.precipitationIntensity||0,
    precipitationActivity:getPrecipitationActivity(atmosphere,S.motionTime),
    metrics:S.threeAtmosphere?S.threeAtmosphere.latestMetrics||null:null,
    motionMetrics:S.threeAtmosphere?S.threeAtmosphere.latestMotionMetrics||null:null,
    error:S.rendererFailureReason||S.threeShaderError||null
  };
  var nativeLog=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.aetherDesk;
  if(nativeLog){
    // Log renderer transitions once; frame metrics continue to the preview only.
    var summary=JSON.stringify({renderer:status.renderer,revision:status.revision,quality:status.quality,error:status.error});
    if(summary!==reportWeatherRendererStatus.lastNativeStatus){
      try{
        nativeLog.postMessage({action:'log',message:'reportWeatherRendererStatus '+summary});
        reportWeatherRendererStatus.lastNativeStatus=summary;
      }catch(e){console.warn('Weather Aether: renderer status bridge unavailable.',e);}
    }
  }
  if(window.parent!==window)window.parent.postMessage(status,'*');
}

function initializeThreeAtmosphere(){
  if(new URLSearchParams(window.location.search).get('renderer')==='canvas')return false;
  var api=window.WeatherAetherThree;
  var canvas=document.getElementById('wa-webgl');
  if(!api||!canvas)return false;
  try{
    var renderer=new api.WebGLRenderer({
      canvas:canvas,antialias:false,alpha:false,depth:false,stencil:false,
      powerPreference:'low-power',failIfMajorPerformanceCaveat:true
    });
    renderer.debug.onShaderError=function(gl,program,vertexShader,fragmentShader){
      var details=[gl.getProgramInfoLog(program),gl.getShaderInfoLog(vertexShader),gl.getShaderInfoLog(fragmentShader)].filter(Boolean).join('\n');
      S.threeShaderError=details||'WebGL shader compilation failed';
      console.error('Weather Aether: Three.js shader error.',S.threeShaderError);
      if(!S.threeUnavailable)S.pendingRendererDemotion=S.threeShaderError;
    };
    var quality=getThreeQualityProfile();
    renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,quality.pixelRatio));
    var scene=new api.Scene();
    var presentationScene=new api.Scene();
    var camera=new api.PerspectiveCamera(45,1,0.1,20);
    camera.position.set(0,0,5);
    var material=new api.ShaderMaterial({
      vertexShader:THREE_SKY_VERTEX,fragmentShader:THREE_SKY_FRAGMENT,
      depthTest:false,depthWrite:false,
      uniforms:{
        uTop:{value:new api.Color()},uMiddle:{value:new api.Color()},uBottom:{value:new api.Color()},
        uLightColor:{value:new api.Color()},uLightPosition:{value:new api.Vector2(0.5,0.5)},
        uViewportScale:{value:new api.Vector2(1,1)},uHaze:{value:0},uDiffusion:{value:0},
        uLightAlpha:{value:0},uCloudDensity:{value:0},uMinRatio:{value:1},
        uStarVisibility:{value:0},uCloudCover:{value:0},uCloudVolumeEnabled:{value:0},
        uCloudWind:{value:new api.Vector2(0,0)},uCloudHighlight:{value:new api.Color()},
        uCloudShadow:{value:new api.Color()},uCloudLightOffset:{value:new api.Vector3(-0.20,0.16,0.10)},
        uCloudLightBase:{value:0.34},uCloudLightStrength:{value:0.82},uCloudTopLight:{value:0.12},
        uCloudDepthShadow:{value:0},uCloudSkyBounce:{value:0},uCloudSteps:{value:quality.steps},uTime:{value:0}
      }
    });
    var geometry=new api.PlaneGeometry(2,2);
    var skyMesh=new api.Mesh(geometry,material);
    skyMesh.frustumCulled=false;skyMesh.renderOrder=-100;
    scene.add(skyMesh);
    var atmosphereTarget=new api.WebGLRenderTarget(1,1,{depthBuffer:false,stencilBuffer:false});
    var presentationMaterial=new api.ShaderMaterial({
      vertexShader:THREE_SKY_VERTEX,fragmentShader:THREE_PRESENT_FRAGMENT,
      depthTest:false,depthWrite:false,uniforms:{uAtmosphere:{value:atmosphereTarget.texture}}
    });
    var presentationMesh=new api.Mesh(new api.PlaneGeometry(2,2),presentationMaterial);
    presentationMesh.frustumCulled=false;presentationMesh.renderOrder=-100;
    presentationScene.add(presentationMesh);
    S.threeAtmosphere={
      renderer:renderer,scene:scene,presentationScene:presentationScene,camera:camera,material:material,
      presentationMaterial:presentationMaterial,atmosphereTarget:atmosphereTarget,quality:quality,
      lastPaintKey:null,lastFrame:0,lastPresentationFrame:0,nextAtmosphereFrame:0,nextPresentationFrame:0,
      latestMetrics:null,latestMotionMetrics:null,
      performance:{startedAt:0,lastRenderedAt:0,frames:0,submitTotal:0,submitMax:0,intervals:[]},
      motionPerformance:{startedAt:0,lastRenderedAt:0,frames:0,submitTotal:0,submitMax:0,intervals:[]}
    };
    initializeThreeRain(api,S.threeAtmosphere);
    initializeThreeFreezing(api,S.threeAtmosphere);
    initializeThreeSnow(api,S.threeAtmosphere);
    initializeThreeHail(api,S.threeAtmosphere);
    S.threeShaderError=null;
    S.useThreeRenderer=true;canvas.style.display='block';
    canvas.addEventListener('webglcontextlost',function(event){
      event.preventDefault();
      if(!S.threeUnavailable)S.pendingRendererDemotion='WebGL context lost';
    });
    return true;
  }catch(error){
    S.useThreeRenderer=false;S.threeAtmosphere=null;canvas.style.display='none';
    S.threeShaderError=String(error&&error.message?error.message:error);
    console.warn('Weather Aether: WebGL atmosphere unavailable; using Canvas fallback.',error);
    return false;
  }
}

function initializeThreeRain(api,state){
  var maximum=THREE_QUALITY_PROFILES.showcase.rainCount;
  var positions=[],speeds=[],lengths=[],opacities=[],phases=[];
  var random=seededRandom(29471);
  for(var i=0;i<maximum;i++){
    var nearness=Math.pow(random(),0.72);
    positions.push(random()*2-1,(random()-0.5)*8,-2.2+nearness*5.7);
    speeds.push(1.55+nearness*2.75+random()*0.55);
    lengths.push(11+nearness*23+random()*5);
    opacities.push(0.20+nearness*0.55);
    phases.push(random()*8);
  }
  var geometry=new api.BufferGeometry();
  geometry.setAttribute('position',new api.Float32BufferAttribute(positions,3));
  geometry.setAttribute('aSpeed',new api.Float32BufferAttribute(speeds,1));
  geometry.setAttribute('aLength',new api.Float32BufferAttribute(lengths,1));
  geometry.setAttribute('aOpacity',new api.Float32BufferAttribute(opacities,1));
  geometry.setAttribute('aPhase',new api.Float32BufferAttribute(phases,1));
  geometry.setDrawRange(0,state.quality.rainCount);
  var material=new api.ShaderMaterial({
    vertexShader:THREE_RAIN_VERTEX,fragmentShader:THREE_RAIN_FRAGMENT,
    transparent:true,depthTest:false,depthWrite:false,
    uniforms:{
      uTime:{value:0},uPixelRatio:{value:state.renderer.getPixelRatio()},uAspect:{value:1},uWind:{value:0},
      uSpeed:{value:1},uDropScale:{value:1},uColor:{value:new api.Color(0.65,0.76,0.90)},uOpacity:{value:0.62}
    }
  });
  var points=new api.Points(geometry,material);
  points.frustumCulled=false;points.renderOrder=50;points.visible=false;
  state.presentationScene.add(points);
  S.threeRain={points:points,geometry:geometry,material:material,active:false,qualityCount:state.quality.rainCount};
}

function rendersThreeRain(){
  return!!(S.useThreeRenderer&&S.threeRain&&S.threeRain.active);
}

function updateThreeRain(timestamp,atmosphere){
  var rain=S.threeRain;
  if(!rain)return;
  var active=S.props.showParticles&&(atmosphere.condition==='rain'||atmosphere.condition==='thunder');
  rain.active=active;rain.points.visible=active;
  if(!active)return;
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  var intensity=atmosphere.precipitationIntensity||atmosphere.precipitation;
  var activity=getPrecipitationActivity(atmosphere,S.motionTime);
  var visibleFraction=lerp(0.20,1,intensity)*lerp(0.28,1,activity);
  rain.geometry.setDrawRange(0,Math.max(10,Math.round(rain.qualityCount*visibleFraction)));
  rain.material.uniforms.uTime.value=timestamp/1000;
  rain.material.uniforms.uWind.value=Math.sin(toward)*(0.28+atmosphere.motionEnergy*0.72);
  rain.material.uniforms.uSpeed.value=S.props.animationSpeed*lerp(0.56,1.28,intensity)*(0.84+atmosphere.motionEnergy*0.34);
  rain.material.uniforms.uDropScale.value=(atmosphere.precipitationScale||1)*lerp(0.82,1.12,intensity);
  rain.material.uniforms.uOpacity.value=lerp(0.38,0.80,intensity)*lerp(0.72,1,activity);
  var lightKey=getCloudLightingKey(atmosphere,wallpaperNow())+':'+atmosphere.condition;
  if(rain.lastLightKey!==lightKey){
    var lighting=getPrecipitationLighting(atmosphere,wallpaperNow());
    rain.material.uniforms.uColor.value.copy(threeColor(lighting.rain));
    rain.lastLightKey=lightKey;
  }
}

function initializeThreeFreezing(api,state){
  var maximum=THREE_QUALITY_PROFILES.showcase.freezingCount;
  var positions=[],speeds=[],lengths=[],sizes=[],opacities=[],phases=[],iceSeeds=[];
  var random=seededRandom(41863);
  for(var i=0;i<maximum;i++){
    var nearness=Math.pow(random(),0.74);
    positions.push(random()*2-1,(random()-0.5)*8,-2.2+nearness*5.7);
    speeds.push(1.30+nearness*2.35+random()*0.48);
    lengths.push(8+nearness*18+random()*4);
    sizes.push(2.1+nearness*5.0+random()*1.6);
    opacities.push(0.20+nearness*0.54);
    phases.push(random()*8);
    iceSeeds.push(random());
  }
  var geometry=new api.BufferGeometry();
  geometry.setAttribute('position',new api.Float32BufferAttribute(positions,3));
  geometry.setAttribute('aSpeed',new api.Float32BufferAttribute(speeds,1));
  geometry.setAttribute('aLength',new api.Float32BufferAttribute(lengths,1));
  geometry.setAttribute('aSize',new api.Float32BufferAttribute(sizes,1));
  geometry.setAttribute('aOpacity',new api.Float32BufferAttribute(opacities,1));
  geometry.setAttribute('aPhase',new api.Float32BufferAttribute(phases,1));
  geometry.setAttribute('aIce',new api.Float32BufferAttribute(iceSeeds,1));
  geometry.setDrawRange(0,state.quality.freezingCount);
  var material=new api.ShaderMaterial({
    vertexShader:THREE_FREEZING_VERTEX,fragmentShader:THREE_FREEZING_FRAGMENT,
    transparent:true,depthTest:false,depthWrite:false,
    uniforms:{
      uTime:{value:0},uPixelRatio:{value:state.renderer.getPixelRatio()},uAspect:{value:1},uWind:{value:0},
      uSpeed:{value:1},uDropScale:{value:1},uIceFraction:{value:0.08},
      uLiquidColor:{value:new api.Color(0.63,0.74,0.88)},uIceColor:{value:new api.Color(0.82,0.90,0.98)},uOpacity:{value:0.66}
    }
  });
  var points=new api.Points(geometry,material);
  points.frustumCulled=false;points.renderOrder=54;points.visible=false;
  state.presentationScene.add(points);
  S.threeFreezing={points:points,geometry:geometry,material:material,active:false,qualityCount:state.quality.freezingCount};
}

function rendersThreeFreezing(){
  return!!(S.useThreeRenderer&&S.threeFreezing&&S.threeFreezing.active);
}

function updateThreeFreezing(timestamp,atmosphere){
  var freezing=S.threeFreezing;
  if(!freezing)return;
  var active=S.props.showParticles&&atmosphere.condition==='freezing';
  freezing.active=active;freezing.points.visible=active;
  if(!active)return;
  var intensity=atmosphere.precipitationIntensity||atmosphere.precipitation;
  var activity=getPrecipitationActivity(atmosphere,S.motionTime);
  var visibleFraction=lerp(0.22,1,intensity)*lerp(0.34,1,activity);
  freezing.geometry.setDrawRange(0,Math.max(10,Math.round(freezing.qualityCount*visibleFraction)));
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  freezing.material.uniforms.uTime.value=timestamp/1000;
  freezing.material.uniforms.uWind.value=Math.sin(toward)*(0.24+atmosphere.motionEnergy*0.62);
  freezing.material.uniforms.uSpeed.value=S.props.animationSpeed*lerp(0.60,1.16,intensity)*(0.86+atmosphere.motionEnergy*0.30);
  freezing.material.uniforms.uDropScale.value=(atmosphere.precipitationScale||1)*lerp(0.84,1.08,intensity);
  freezing.material.uniforms.uIceFraction.value=atmosphere.iceFraction||0.06;
  freezing.material.uniforms.uOpacity.value=lerp(0.42,0.78,intensity)*lerp(0.76,1,activity);
  var lightKey=getCloudLightingKey(atmosphere,wallpaperNow());
  if(freezing.lastLightKey!==lightKey){
    var lighting=getPrecipitationLighting(atmosphere,wallpaperNow());
    freezing.material.uniforms.uLiquidColor.value.copy(threeColor(lighting.rain));
    freezing.material.uniforms.uIceColor.value.copy(threeColor(lighting.freezing));
    freezing.lastLightKey=lightKey;
  }
}

function initializeThreeSnow(api,state){
  var maximum=THREE_QUALITY_PROFILES.showcase.snowCount;
  var positions=[],speeds=[],sizes=[],opacities=[],phases=[],wobbles=[];
  var random=seededRandom(51803);
  for(var i=0;i<maximum;i++){
    var nearness=Math.pow(random(),0.78);
    positions.push(random()*2-1,(random()-0.5)*8,-2.2+nearness*5.7);
    speeds.push(0.34+nearness*0.72+random()*0.24);
    sizes.push(2.0+nearness*5.8+random()*1.8);
    opacities.push(0.20+nearness*0.60);
    phases.push(random()*8);
    wobbles.push(0.65+random()*1.75);
  }
  var geometry=new api.BufferGeometry();
  geometry.setAttribute('position',new api.Float32BufferAttribute(positions,3));
  geometry.setAttribute('aSpeed',new api.Float32BufferAttribute(speeds,1));
  geometry.setAttribute('aSize',new api.Float32BufferAttribute(sizes,1));
  geometry.setAttribute('aOpacity',new api.Float32BufferAttribute(opacities,1));
  geometry.setAttribute('aPhase',new api.Float32BufferAttribute(phases,1));
  geometry.setAttribute('aWobble',new api.Float32BufferAttribute(wobbles,1));
  geometry.setDrawRange(0,state.quality.snowCount);
  var material=new api.ShaderMaterial({
    vertexShader:THREE_SNOW_VERTEX,fragmentShader:THREE_SNOW_FRAGMENT,
    transparent:true,depthTest:false,depthWrite:false,
    uniforms:{
      uTime:{value:0},uPixelRatio:{value:state.renderer.getPixelRatio()},uAspect:{value:1},uWind:{value:0},
      uSpeed:{value:1},uFlakeScale:{value:1},uFlutter:{value:0.72},uGrain:{value:0},
      uColor:{value:new api.Color(0.86,0.90,0.97)},uOpacity:{value:0.78}
    }
  });
  var points=new api.Points(geometry,material);
  points.frustumCulled=false;points.renderOrder=55;points.visible=false;
  state.presentationScene.add(points);
  S.threeSnow={points:points,geometry:geometry,material:material,active:false,qualityCount:state.quality.snowCount};
}

function rendersThreeSnow(){
  return!!(S.useThreeRenderer&&S.threeSnow&&S.threeSnow.active);
}

function updateThreeSnow(timestamp,atmosphere){
  var snow=S.threeSnow;
  if(!snow)return;
  var active=S.props.showParticles&&atmosphere.condition==='snow';
  snow.active=active;snow.points.visible=active;
  if(!active)return;
  var intensity=atmosphere.precipitationIntensity||atmosphere.precipitation;
  var activity=getPrecipitationActivity(atmosphere,S.motionTime);
  var visibleFraction=lerp(0.24,1,intensity)*lerp(0.30,1,activity);
  snow.geometry.setDrawRange(0,Math.max(10,Math.round(snow.qualityCount*visibleFraction)));
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  snow.material.uniforms.uTime.value=timestamp/1000;
  snow.material.uniforms.uWind.value=Math.sin(toward)*(0.18+atmosphere.motionEnergy*0.46);
  snow.material.uniforms.uSpeed.value=S.props.animationSpeed*lerp(0.66,1.10,intensity)*(0.78+atmosphere.motionEnergy*0.32);
  snow.material.uniforms.uFlakeScale.value=(atmosphere.precipitationScale||1)*lerp(0.88,1.08,intensity);
  snow.material.uniforms.uFlutter.value=atmosphere.precipitationFlutter==null?0.72:atmosphere.precipitationFlutter;
  snow.material.uniforms.uGrain.value=atmosphere.precipitationGrain||0;
  snow.material.uniforms.uOpacity.value=lerp(0.54,0.86,intensity)*lerp(0.74,1,activity);
  var lightKey=getCloudLightingKey(atmosphere,wallpaperNow());
  if(snow.lastLightKey!==lightKey){
    var lighting=getPrecipitationLighting(atmosphere,wallpaperNow());
    var cleanSnow=lerpC(lighting.snow,[248,250,255],atmosphere.isDay?0.62:0.42);
    snow.material.uniforms.uColor.value.copy(threeColor(cleanSnow));
    snow.lastLightKey=lightKey;
  }
}

function initializeThreeHail(api,state){
  var maximum=THREE_QUALITY_PROFILES.showcase.hailCount;
  var positions=[],speeds=[],sizes=[],opacities=[],phases=[];
  var random=seededRandom(73189);
  for(var i=0;i<maximum;i++){
    var nearness=Math.pow(random(),0.72);
    positions.push(random()*2-1,(random()-0.5)*8,-2.2+nearness*5.7);
    speeds.push(2.15+nearness*3.35+random()*0.85);
    sizes.push(2.2+nearness*5.8+random()*2.4);
    opacities.push(0.34+nearness*0.58);
    phases.push(random()*8);
  }
  var geometry=new api.BufferGeometry();
  geometry.setAttribute('position',new api.Float32BufferAttribute(positions,3));
  geometry.setAttribute('aSpeed',new api.Float32BufferAttribute(speeds,1));
  geometry.setAttribute('aSize',new api.Float32BufferAttribute(sizes,1));
  geometry.setAttribute('aOpacity',new api.Float32BufferAttribute(opacities,1));
  geometry.setAttribute('aPhase',new api.Float32BufferAttribute(phases,1));
  geometry.setDrawRange(0,state.quality.hailCount);
  var material=new api.ShaderMaterial({
    vertexShader:THREE_HAIL_VERTEX,fragmentShader:THREE_HAIL_FRAGMENT,
    transparent:true,depthTest:false,depthWrite:false,
    uniforms:{
      uTime:{value:0},uPixelRatio:{value:state.renderer.getPixelRatio()},uAspect:{value:1},uWind:{value:0},
      uSpeed:{value:1},uPelletScale:{value:1},uColor:{value:new api.Color(0.80,0.88,0.96)},uOpacity:{value:0.92}
    }
  });
  var points=new api.Points(geometry,material);
  points.frustumCulled=false;points.renderOrder=58;points.visible=false;
  state.presentationScene.add(points);
  S.threeHail={points:points,geometry:geometry,material:material,active:false,qualityCount:state.quality.hailCount};
}

function rendersThreeHail(){
  return!!(S.useThreeRenderer&&S.threeHail&&S.threeHail.active);
}

function updateThreeHail(timestamp,atmosphere){
  var hail=S.threeHail;
  if(!hail)return;
  var active=S.props.showParticles&&atmosphere.condition==='hail';
  hail.active=active;hail.points.visible=active;
  if(!active)return;
  var intensity=atmosphere.precipitationIntensity||atmosphere.precipitation;
  var activity=getPrecipitationActivity(atmosphere,S.motionTime);
  var visibleFraction=lerp(0.26,1,intensity)*lerp(0.32,1,activity);
  hail.geometry.setDrawRange(0,Math.max(12,Math.round(hail.qualityCount*visibleFraction)));
  var toward=((atmosphere.windDirection+180)%360)*Math.PI/180;
  hail.material.uniforms.uTime.value=timestamp/1000;
  hail.material.uniforms.uWind.value=Math.sin(toward)*(0.46+atmosphere.motionEnergy*0.84);
  hail.material.uniforms.uSpeed.value=S.props.animationSpeed*lerp(0.78,1.34,intensity)*(0.88+atmosphere.motionEnergy*0.36);
  hail.material.uniforms.uPelletScale.value=(atmosphere.precipitationScale||1)*lerp(0.86,1.10,intensity);
  hail.material.uniforms.uOpacity.value=lerp(0.66,0.96,intensity)*lerp(0.76,1,activity);
  var lightKey=getCloudLightingKey(atmosphere,wallpaperNow());
  if(hail.lastLightKey!==lightKey){
    var lighting=getPrecipitationLighting(atmosphere,wallpaperNow());
    hail.material.uniforms.uColor.value.copy(threeColor(lighting.hail));
    hail.lastLightKey=lightKey;
  }
}

function usesThreeCloudVolume(){
  return new URLSearchParams(window.location.search).get('cloudModel')!=='planes';
}

// Low sunlight benefits from dramatic lateral shading, but reusing that model
// at every hour makes noon clouds look under-lit and night clouds look carved.
// Keep the horizon treatment, rotate the light overhead around noon, and let
// night settle into subdued ambient moonlight.
function getThreeCloudShading(atmosphere,track){
  if(!atmosphere.isDay){
    return{offset:[(track.x-0.5)*0.18,0.10,0.08],base:0.16,strength:0.48,top:0.07,depth:0.16,bounce:0};
  }
  var overhead=Math.max(0,Math.min(1,(track.elevation-0.18)/0.64));
  overhead=overhead*overhead*(3-2*overhead);
  return{
    offset:[lerp(-0.20,(track.x-0.5)*0.08,overhead),lerp(0.16,0.34,overhead),lerp(0.10,0.04,overhead)],
    base:lerp(0.34,0.48,overhead),
    strength:lerp(0.82,0.60,overhead),
    top:lerp(0.12,0.22,overhead),
    depth:lerp(0,0.14,overhead),
    bounce:lerp(0,0.24,overhead)
  };
}

function resizeThreeAtmosphere(w,h){
  if(!S.useThreeRenderer||!S.threeAtmosphere)return;
  S.threeAtmosphere.renderer.setSize(w,h,false);
  S.threeAtmosphere.camera.aspect=w/Math.max(1,h);
  S.threeAtmosphere.camera.updateProjectionMatrix();
  var maxDimension=Math.max(w,h);
  var uniforms=S.threeAtmosphere.material.uniforms;
  uniforms.uViewportScale.value.set(w/maxDimension,h/maxDimension);
  uniforms.uMinRatio.value=Math.min(w,h)/maxDimension;
  var drawingSize=S.threeAtmosphere.renderer.getDrawingBufferSize(new WeatherAetherThree.Vector2());
  S.threeAtmosphere.atmosphereTarget.setSize(Math.max(1,drawingSize.x),Math.max(1,drawingSize.y));
  if(S.threeRain)S.threeRain.material.uniforms.uAspect.value=w/Math.max(1,h);
  if(S.threeFreezing)S.threeFreezing.material.uniforms.uAspect.value=w/Math.max(1,h);
  if(S.threeSnow)S.threeSnow.material.uniforms.uAspect.value=w/Math.max(1,h);
  if(S.threeHail)S.threeHail.material.uniforms.uAspect.value=w/Math.max(1,h);
}

function makeThreeCloudTexture(canvas){
  var api=window.WeatherAetherThree;
  var texture=new api.CanvasTexture(canvas);
  texture.colorSpace=api.SRGBColorSpace;
  return texture;
}

function clearThreeClouds(){
  var state=S.threeAtmosphere,cloudState=S.threeClouds;
  if(!state||!cloudState)return;
  for(var i=0;i<cloudState.meshes.length;i++){
    state.scene.remove(cloudState.meshes[i]);
    cloudState.meshes[i].material.dispose();
  }
  for(var i=0;i<cloudState.textures.length;i++)cloudState.textures[i].dispose();
  if(cloudState.geometry)cloudState.geometry.dispose();
  S.threeClouds=null;
}

function refreshThreeCloudTextures(){
  var api=window.WeatherAetherThree,cloudState=S.threeClouds;
  if(!S.useThreeRenderer||!api||!cloudState)return;
  for(var i=0;i<cloudState.textures.length;i++)cloudState.textures[i].dispose();
  cloudState.textures=[];
  for(var i=0;i<S.cloudSprites.length;i++)cloudState.textures.push(makeThreeCloudTexture(S.cloudSprites[i]));
  for(var i=0;i<cloudState.meshes.length;i++){
    cloudState.meshes[i].material.map=cloudState.textures[S.clouds[i].sprite];
    cloudState.meshes[i].material.needsUpdate=true;
  }
}

function rebuildThreeClouds(){
  if(usesThreeCloudVolume()||!S.useThreeRenderer||!S.threeAtmosphere||S.clouds.length===0)return;
  var api=window.WeatherAetherThree;
  var geometry=new api.PlaneGeometry(1,1);
  var textures=[];
  for(var i=0;i<S.cloudSprites.length;i++)textures.push(makeThreeCloudTexture(S.cloudSprites[i]));
  var meshes=[];
  for(var i=0;i<S.clouds.length;i++){
    var cloud=S.clouds[i];
    var material=new api.MeshBasicMaterial({
      map:textures[cloud.sprite],transparent:true,opacity:cloud.opacity,
      depthTest:true,depthWrite:false
    });
    var mesh=new api.Mesh(geometry,material);
    mesh.position.z=0.25+cloud.depth*2.25;
    mesh.rotation.z=((cloud.sprite%4)-1.5)*0.008;
    mesh.renderOrder=Math.round(cloud.depth*100);
    S.threeAtmosphere.scene.add(mesh);meshes.push(mesh);
  }
  S.threeClouds={geometry:geometry,textures:textures,meshes:meshes};
}

function updateThreeClouds(timestamp){
  var state=S.threeAtmosphere,cloudState=S.threeClouds;
  if(!state||!cloudState)return;
  var camera=state.camera,w=cssW(),h=cssH();
  camera.position.x=Math.sin(timestamp*0.000031)*0.035;
  camera.position.y=Math.sin(timestamp*0.000019+1.2)*0.018;
  camera.lookAt(0,0,0);
  for(var i=0;i<cloudState.meshes.length;i++){
    var cloud=S.clouds[i],mesh=cloudState.meshes[i];
    if(!cloud||!mesh)continue;
    var distance=camera.position.z-mesh.position.z;
    var viewHeight=2*Math.tan(camera.fov*Math.PI/360)*distance;
    var viewWidth=viewHeight*camera.aspect;
    mesh.position.x=((cloud.x+cloud.width*0.5)/w-0.5)*viewWidth;
    mesh.position.y=(0.5-(cloud.y+cloud.height*0.5)/h)*viewHeight;
    mesh.scale.set((cloud.width/w)*viewWidth,(cloud.height/h)*viewHeight,1);
    mesh.material.opacity=cloud.opacity;
  }
}

function updateThreeAtmosphere(timestamp){
  var state=S.threeAtmosphere;
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  var now=wallpaperNow();
  var key=[Math.floor(now.getTime()/15000),atmosphere.condition,Math.round(atmosphere.precipitationIntensity*100),atmosphere.isDay?1:0].join(':');
  var uniforms=state.material.uniforms;
  if(state.lastPaintKey!==key){
    var colors=getPhaseColors();
    var track=getCelestialTrack(now,atmosphere.isDay);
    var lightColor=lerpC([225,238,255],[255,155,72],track.horizonWarmth);
    uniforms.uTop.value.copy(threeColor(colors.top));
    uniforms.uMiddle.value.copy(threeColor(colors.mid));
    uniforms.uBottom.value.copy(threeColor(colors.bot));
    uniforms.uLightColor.value.copy(threeColor(lightColor));
    uniforms.uLightPosition.value.set(track.x,Math.min(0.66,1-track.y));
    uniforms.uHaze.value=atmosphere.horizonHaze;
    uniforms.uDiffusion.value=atmosphere.lightDiffusion;
    uniforms.uLightAlpha.value=(0.055+atmosphere.lightDiffusion*0.09)*(atmosphere.isDay?1:0.34);
    uniforms.uCloudDensity.value=atmosphere.cloudDensity;
    uniforms.uCloudCover.value=atmosphere.cloudCover;
    uniforms.uCloudVolumeEnabled.value=usesThreeCloudVolume()&&atmosphere.cloudCover>=0.10&&
      atmosphere.condition!=='fog'&&atmosphere.condition!=='snow'?1:0;
    var cloudLight=getCloudLighting(atmosphere,now);
    uniforms.uCloudHighlight.value.copy(threeColor(cloudLight.highlight));
    uniforms.uCloudShadow.value.copy(threeColor(cloudLight.shadow));
    var cloudShading=getThreeCloudShading(atmosphere,track);
    uniforms.uCloudLightOffset.value.set(cloudShading.offset[0],cloudShading.offset[1],cloudShading.offset[2]);
    uniforms.uCloudLightBase.value=cloudShading.base;
    uniforms.uCloudLightStrength.value=cloudShading.strength;
    uniforms.uCloudTopLight.value=cloudShading.top;
    uniforms.uCloudDepthShadow.value=cloudShading.depth;
    uniforms.uCloudSkyBounce.value=cloudShading.bounce;
    var windToward=((atmosphere.windDirection+180)%360)*Math.PI/180;
    uniforms.uCloudWind.value.set(Math.sin(windToward)*atmosphere.motionEnergy,Math.cos(windToward)*atmosphere.motionEnergy);
    state.lastPaintKey=key;
  }
  uniforms.uStarVisibility.value=!atmosphere.isDay&&S.props.showParticles?
    (atmosphere.condition==='clear'?1:atmosphere.condition==='partly'?0.38:0):0;
  uniforms.uTime.value=timestamp/1000;
}

function recordThreePerformance(stats,timestamp,submitTime,state,drawCalls){
  if(!stats.startedAt){
    stats.startedAt=timestamp;stats.lastRenderedAt=timestamp;
    return null;
  }
  stats.frames++;
  stats.submitTotal+=submitTime;
  stats.submitMax=Math.max(stats.submitMax,submitTime);
  stats.intervals.push(timestamp-stats.lastRenderedAt);
  stats.lastRenderedAt=timestamp;
  var duration=timestamp-stats.startedAt;
  if(duration<2000)return null;
  var ordered=stats.intervals.slice().sort(function(a,b){return a-b;});
  var p95=ordered[Math.max(0,Math.ceil(ordered.length*0.95)-1)]||0;
  var result={
    fps:stats.frames*1000/duration,p95:p95,
    submitAverage:stats.frames?stats.submitTotal/stats.frames:0,submitMax:stats.submitMax,
    width:state.renderer.domElement.width,height:state.renderer.domElement.height,drawCalls:drawCalls
  };
  stats.startedAt=timestamp;stats.frames=0;stats.submitTotal=0;stats.submitMax=0;stats.intervals=[];
  return result;
}

function claimThreeFrame(state,key,timestamp,fps){
  var interval=1000/fps;
  var deadline=state[key]||timestamp;
  if(timestamp+0.5<deadline)return false;
  if(timestamp-deadline>interval*2)deadline=timestamp;
  state[key]=deadline+interval;
  return true;
}

function renderThreeAtmosphere(timestamp){
  var state=S.threeAtmosphere;
  if(!S.useThreeRenderer||!state)return;
  if(!claimThreeFrame(state,'nextAtmosphereFrame',timestamp,state.quality.fps))return false;
  updateThreeAtmosphere(timestamp);
  updateThreeClouds(timestamp);
  var submitStart=performance.now();
  state.renderer.setRenderTarget(state.atmosphereTarget);
  state.renderer.render(state.scene,state.camera);
  var skyDrawCalls=state.renderer.info.render.calls;
  state.renderer.setRenderTarget(null);
  var submitTime=performance.now()-submitStart;
  var metrics=recordThreePerformance(state.performance,timestamp,submitTime,state,skyDrawCalls);
  if(metrics){state.latestMetrics=metrics;reportWeatherRendererStatus();}
  if(!state.statusReported){
    state.statusReported=true;
    reportWeatherRendererStatus();
  }
  state.lastFrame=timestamp;
  return true;
}

function renderThreePresentation(timestamp,atmosphereChanged){
  var state=S.threeAtmosphere;
  if(!S.useThreeRenderer||!state)return;
  var atmosphere=S.atmosphere||deriveAtmosphericState(S.weather);
  updateThreeRain(timestamp,atmosphere);
  updateThreeFreezing(timestamp,atmosphere);
  updateThreeSnow(timestamp,atmosphere);
  updateThreeHail(timestamp,atmosphere);
  var targetFps=(rendersThreeRain()||rendersThreeFreezing()||rendersThreeSnow()||rendersThreeHail())?state.quality.motionFps:state.quality.fps;
  if(atmosphereChanged){
    state.nextPresentationFrame=timestamp+1000/targetFps;
  }else if(!claimThreeFrame(state,'nextPresentationFrame',timestamp,targetFps))return;
  var submitStart=performance.now();
  state.renderer.setRenderTarget(null);
  state.renderer.render(state.presentationScene,state.camera);
  var drawCalls=state.renderer.info.render.calls;
  var submitTime=performance.now()-submitStart;
  var metrics=recordThreePerformance(state.motionPerformance,timestamp,submitTime,state,drawCalls);
  if(metrics){state.latestMotionMetrics=metrics;reportWeatherRendererStatus();}
  state.lastPresentationFrame=timestamp;
}
