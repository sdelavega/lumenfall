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

function selectRenderer(env){
  if(!env.canvas2d)return 'static';
  return env.rendererPref!=='classic'&&env.webgl?'three':'canvas';
}

function applyStaticAtmosphere(atmosphere){
  var canvas=S.canvas||document.getElementById('wa-sky');
  if(!canvas||!canvas.parentElement)return;
  var colors=atmosphere&&atmosphere.colors||getPhaseColors();
  canvas.parentElement.style.background='linear-gradient(to bottom,'+rgb(colors.top)+' 0%,'+rgb(colors.mid)+' 46%,'+rgb(colors.bot)+' 100%)';
}

function applyRendererPreference(){
  if(!S.canvas)return;
  var requested=new URLSearchParams(window.location.search).get('renderer');
  var preference=requested==='canvas'||requested==='classic'?'classic':S.props.renderer;
  var next=selectRenderer({rendererPref:preference,webgl:!!window.WeatherAetherThree&&!S.threeUnavailable,canvas2d:!!S.ctx});
  if(next==='three'){
    if(!S.threeAtmosphere&&!initializeThreeAtmosphere()){
      S.threeUnavailable=true;
      next='canvas';
    }
  }
  if(next===S.renderer)return;
  S.renderer=next;
  S.useThreeRenderer=next==='three';
  var canvas=document.getElementById('wa-webgl');
  if(canvas)canvas.style.display=S.useThreeRenderer?'block':'none';
  S.canvas.style.display=next==='static'?'none':'block';
  if(next==='static'){
    applyStaticAtmosphere(S.atmosphere);
  }else{
    resizeThreeAtmosphere(cssW(),cssH());
    rebuildParticles();
  }
  syncRendererAnimation();
  reportWeatherRendererStatus();
}

function demoteRenderer(origin,reason){
  if(S.renderer==='static'||(origin==='three'&&S.threeUnavailable))return;
  var gpuFailure=origin==='three';
  if(!gpuFailure)S.ctx=null;
  S.rendererFailureReason=reason;
  S.pendingRendererDemotion=null;
  S.useThreeRenderer=false;
  if(gpuFailure){
    S.threeUnavailable=true;
    S.renderer=S.ctx?'canvas':'static';
  }else{
    S.renderer='static';
  }
  var canvas=document.getElementById('wa-webgl');
  if(canvas)canvas.style.display='none';
  S.canvas.style.display=S.renderer==='static'?'none':'block';
  S.skyPaintCache=null;
  if(S.renderer==='canvas'){
    rebuildParticles();
  }else{
    applyStaticAtmosphere(S.atmosphere);
  }
  syncRendererAnimation();
  reportWeatherRendererStatus();
}
