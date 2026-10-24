// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Stephen de la Vega
// See ../LICENSE.txt for the MIT license.
'use strict';

function lerpCityColor(color, target, blend) {
  color.lerp(target, blend);
  // Match GPU precision so settled colors stop causing invisible uniform uploads.
  color.r = Math.fround(color.r); color.g = Math.fround(color.g); color.b = Math.fround(color.b);
}

function cityRandom(seed) {
  return function() {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return seed / 4294967296;
  };
}

function buildCity(seed) {
  const random = cityRandom(seed), range = (a, b) => a + (b - a) * random();
  const towers = [];
  const add = (band, x, z, w, h, d) => towers.push({ band, x, z, w, h, d });
  for (let i = 0; i < 22; i++) add('near', (i % 2 ? 1 : -1) * range(43, 92),
    range(-270, 95), range(18, 31), range(90, 210), range(21, 40));
  for (let i = 0; i < 175; i++) {
    let x = range(-270, 270);
    if (Math.abs(x) < 30) x += x < 0 ? -40 : 40;
    add('mid', x, range(-670, -120), range(10, 25), range(27, 145), range(12, 28));
  }
  for (let i = 0; i < 100; i++) add('far', range(-600, 600), range(-1100, -650),
    range(14, 37), range(40, 180), range(15, 35));
  return { seed: seed >>> 0, towers };
}

let threeCity = null;

function initializeThreeCity(canvas, model) {
  const THREE = window.NeonCityThree;
  if (!THREE || !canvas) return false;
  let renderer, scene;
  canvas.addEventListener('webglcontextlost', event => {
    event.preventDefault();
    demoteRenderer('three', 'WebGL context lost');
  });
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'low-power', precision: 'highp' });
    renderer.debug.onShaderError = () => demoteRenderer('three', 'Shader compilation failed');
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;
    scene = new THREE.Scene();
    scene.background = new THREE.Color(CITY_PALETTES.night.sky);
    scene.fog = new THREE.FogExp2(CITY_PALETTES.night.fog, 0.0034);
    const camera = new THREE.PerspectiveCamera(57, 1, 0.5, 1800);
    const ambient = new THREE.HemisphereLight('#668ab3', '#08121f', 0.8);
    const rim = new THREE.DirectionalLight('#89b8dd', 1.4);
    rim.position.set(-130, 260, 140);
    scene.add(ambient, rim);
    const phaseColors = Object.fromEntries(Object.entries(CITY_PALETTES).map(([phase, palette]) =>
      [phase, Object.fromEntries(['sky', 'horizon', 'fog', 'light'].map(key => [key, new THREE.Color(palette[key])]))]));
    const uniforms = { uLit: { value: 0.23 }, uNeon: { value: 0.85 } };
    const towerMaterial = new THREE.MeshStandardMaterial({ color: '#182b40', roughness: 0.86, metalness: 0.25 });
    towerMaterial.onBeforeCompile = shader => {
      Object.assign(shader.uniforms, uniforms);
      shader.vertexShader = 'varying vec3 vFace; varying vec3 vLocal; varying vec3 vSize; varying float vSeed;\n' + shader.vertexShader;
      shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
        vFace=normal; vLocal=position;
        vSize=vec3(length(instanceMatrix[0].xyz),length(instanceMatrix[1].xyz),length(instanceMatrix[2].xyz));
        vSeed=instanceMatrix[3].x+instanceMatrix[3].z;`);
      shader.fragmentShader = `uniform float uLit; uniform float uNeon;
        varying vec3 vFace; varying vec3 vLocal; varying vec3 vSize; varying float vSeed;
        float cityHash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
        ` + shader.fragmentShader;
      shader.fragmentShader = shader.fragmentShader.replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
        vec2 surface=abs(vFace.z)>.5 ? vLocal.xy*vSize.xy : vec2(vLocal.z*vSize.z,vLocal.y*vSize.y);
        vec2 grid=surface/vec2(2.1,3.4),cell=floor(grid),f=fract(grid);
        float h=cityHash(cell+vSeed),suite=cityHash(vec2(floor(cell.x/3.),cell.y)+vSeed);
        float on=step(h,uLit*.48+suite*uLit*.9)*step(.19,suite);
        float width=mix(.32,.76,cityHash(cell+vSeed+8.));
        float mask=step(.16,f.x)*step(f.x,width)*step(.28,f.y)*step(f.y,.72);
        vec3 light=mix(vec3(1.,.43,.12),vec3(1.,.79,.50),step(.40,h));
        light=mix(light,vec3(.23,.57,1.),step(.93,cityHash(cell+vSeed+15.)));
        float facade=1.-step(.8,abs(vFace.y));
        totalEmissiveRadiance+=light*mask*on*facade*(.65+uNeon*1.25)*mix(.35,1.2,suite);
        // Broad edge illumination keeps masonry legible between sparse windows.
        float edge=pow(abs(dot(normalize(vNormal),normalize(vec3(.7,.25,1.)))),3.);
        totalEmissiveRadiance+=vec3(.017,.035,.065)*(.3+edge)*facade;`);
    };
    const box = new THREE.BoxGeometry(1, 1, 1), plane = new THREE.PlaneGeometry(1, 1);
    const dummy = new THREE.Object3D();
    function instance(mesh, index, x, y, z, w, h, d) {
      dummy.position.set(x, y, z); dummy.scale.set(w, h, d); dummy.rotation.set(0, 0, 0);
      dummy.updateMatrix(); mesh.setMatrixAt(index, dummy.matrix);
    }
    const towers = new THREE.InstancedMesh(box, towerMaterial, model.towers.length);
    model.towers.forEach((b, i) => instance(towers, i, b.x, b.h / 2, b.z, b.w, b.h, b.d));
    scene.add(towers);
    const random = cityRandom(model.seed ^ 0x4e454f4e), range = (a, b) => a + (b - a) * random();
    const softCanvas = document.createElement('canvas'); softCanvas.width = softCanvas.height = 64;
    const softContext = softCanvas.getContext('2d');
    const gradient = softContext.createRadialGradient(32, 32, 0, 32, 32, 32);
    for (const [at, alpha] of [[0, 1], [.18, .65], [.5, .15], [1, 0]]) gradient.addColorStop(at, `rgba(255,255,255,${alpha})`);
    softContext.fillStyle = gradient; softContext.fillRect(0, 0, 64, 64);
    const soft = new THREE.CanvasTexture(softCanvas), neonColors = ['#ff168a', '#00eaff', '#ff9e24', '#ac4dff'];
    const life = createLife(model.seed, model), signBatches = [];
    const signMaterials = [], halos = [], signGroups = Array.from({ length: 8 }, () => []);
    signSites(model).forEach((site, lifeIndex) => {
      const i = site.towerIndex, j = site.signIndex, b = model.towers[i];
      const vertical = j % 3 !== 2;
      signGroups[(vertical ? 0 : 4) + i % 4].push({
        ...site, lifeIndex, tile: (vertical ? 0 : 4) + i % 4,
        x: b.x + (vertical ? (j % 2 ? 1 : -1) * b.w * .35 : 0),
        y: Math.min(b.h - 15, b.h * (.22 + j * .17)), z: b.z + b.d / 2 + .5,
        w: vertical ? range(2.8, 5.5) : b.w * .94, h: vertical ? range(14, 32) : 4.5,
        color: neonColors[(i + j) % 4], tint: new THREE.Color(neonColors[(i + j) % 4]), glow: j === 0
      });
    });
    // Pack the eight local art variants at native resolution into one atlas.
    const atlas = document.createElement('canvas'); atlas.width = 2048; atlas.height = 512;
    const atlasContext = atlas.getContext('2d');
    // Tube art is generated locally; deliberate strokes need no language font.
    for (let variant = 0; variant < signGroups.length; variant++) {
      const vertical = variant < 4, art = document.createElement('canvas');
      art.width = vertical ? 128 : 512; art.height = vertical ? 512 : 128;
      const ctx = art.getContext('2d');
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, art.width, art.height);
      ctx.strokeStyle = ctx.fillStyle = '#fff'; ctx.lineWidth = 4;
      ctx.shadowColor = '#fff'; ctx.shadowBlur = 5;
      ctx.strokeRect(7, 7, art.width - 14, art.height - 14);
      if (vertical) {
        for (let n = 0; n < 4; n++) {
          ctx.save(); ctx.translate(24, 35 + n * 112); ctx.lineWidth = 6; ctx.beginPath();
          ctx.moveTo(4, 16); ctx.lineTo(76, 16); ctx.moveTo(40, 0); ctx.lineTo(40, 82);
          ctx.moveTo(9, 45); ctx.lineTo(72, 45); ctx.moveTo(12, 78); ctx.lineTo(34, 56);
          ctx.moveTo(48, 56); ctx.lineTo(73, 80);
          if ((variant + n) % 2) { ctx.moveTo(13, 27); ctx.lineTo(13, 64); ctx.lineTo(68, 64); ctx.lineTo(68, 27); }
          ctx.stroke(); ctx.restore();
        }
      } else {
        ctx.font = '600 68px monospace'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(['HOTEL', 'NEON', '24 : 00', 'MARKET'][variant % 4], 256, 66);
      }
      atlasContext.drawImage(art, vertical ? variant * 128 : 512, vertical ? 0 : (variant - 4) * 128);
    }
    atlasContext.fillStyle = '#fff'; atlasContext.fillRect(1536, 256, 1, 1);
    const texture = new THREE.CanvasTexture(atlas); texture.colorSpace = THREE.SRGBColorSpace;
    const material = new THREE.MeshBasicMaterial({ map: texture, toneMapped: false });
    material.onBeforeCompile = shader => {
      shader.vertexShader = 'attribute float cityTile;\n' + shader.vertexShader;
      shader.vertexShader = shader.vertexShader.replace('#include <uv_vertex>', `#include <uv_vertex>
        vMapUv=cityTile>=8. ? vec2(1536.5/2048.,255.5/512.) : cityTile<4. ?
          vec2((vMapUv.x+cityTile)*.0625,vMapUv.y) :
          vec2(.25+vMapUv.x*.25,(vMapUv.y+7.-cityTile)*.25);`);
    };
    const signs = signGroups.flat(), trim = [];
    const crowns = model.towers.filter((b, i) => b.band !== 'far' && i % 3 === 0);
    crowns.forEach((b, i) => {
      const pieces = [[b.x, b.h - 1, b.w, .3], [b.x - b.w / 2 + .2, b.h * .61, .25, b.h * .74],
        [b.x + b.w / 2 - .2, b.h * .77, .22, b.h * .43]];
      for (const [x, y, w, h] of pieces) trim.push({ x, y, z: b.z + b.d / 2 + .6, w, h,
        tile: 8, tint: new THREE.Color(neonColors[i % 4]) });
    });
    const instances = signs.concat(trim), mesh = new THREE.InstancedMesh(plane.clone(), material, instances.length);
    // Reuse the bundled InstancedBufferAttribute class exposed by instanceMatrix.
    const InstancedAttribute = mesh.instanceMatrix.constructor;
    mesh.geometry.setAttribute('cityTile', new InstancedAttribute(Float32Array.from(instances, sign => sign.tile), 1));
    signMaterials.push(material);
    instances.forEach((s, i) => {
      instance(mesh, i, s.x, s.y, s.z, s.w, s.h, 1);
      mesh.setColorAt(i, s.tint);
      if (s.glow) halos.push(s);
    });
    signBatches.push({ mesh, signs });
    scene.add(mesh);
    const haloMaterial = new THREE.MeshBasicMaterial({ map: soft, transparent: true,
      opacity: .3, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false });
    // Keep Sprite's camera-facing glow while submitting every halo in one draw.
    haloMaterial.onBeforeCompile = shader => {
      shader.vertexShader = shader.vertexShader.replace('#include <project_vertex>', `
        vec4 mvPosition=modelViewMatrix*vec4(instanceMatrix[3].xyz,1.);
        mvPosition.xy+=position.xy*vec2(length(instanceMatrix[0].xyz),length(instanceMatrix[1].xyz));
        gl_Position=projectionMatrix*mvPosition;`);
    };
    const haloMesh = new THREE.InstancedMesh(plane, haloMaterial, halos.length);
    halos.forEach((s, i) => {
      instance(haloMesh, i, s.x, s.y, s.z + .15, s.w * 5, s.h * 2.2, 1);
      haloMesh.setColorAt(i, new THREE.Color(s.color));
    });
    scene.add(haloMesh);
    // Facade-aligned glow pools stay in front of the masonry while depth
    // testing still hides them behind closer towers. All pools share one draw.
    const pools = signGroups.flat();
    const poolMaterial = new THREE.MeshBasicMaterial({ map: soft, transparent: true, opacity: .1,
      blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false });
    const poolMesh = new THREE.InstancedMesh(plane, poolMaterial, pools.length);
    pools.forEach((s, i) => {
      instance(poolMesh, i, s.x, s.y, s.z + .3, Math.max(s.w * 9, 24), s.h * 2.8, 1);
      poolMesh.setColorAt(i, new THREE.Color(s.color));
    });
    scene.add(poolMesh); signMaterials.push(poolMaterial);
    const street = new THREE.Mesh(new THREE.PlaneGeometry(950, 1600),
      new THREE.MeshStandardMaterial({ color: '#07131d', roughness: .22, metalness: .75 }));
    street.rotation.x = -Math.PI / 2; street.position.set(0, -1, -420); scene.add(street);
    const reflectionMaterial = new THREE.MeshBasicMaterial({ transparent: true, opacity: .12, depthWrite: false });
    const reflections = new THREE.InstancedMesh(plane, reflectionMaterial, 32);
    for (let i = 0; i < 32; i++) {
      dummy.position.set(range(-31, 31), -.8, range(-450, 150));
      dummy.scale.set(range(.25, 1.8), range(20, 65), 1); dummy.rotation.set(-Math.PI / 2, 0, 0);
      dummy.updateMatrix(); reflections.setMatrixAt(i, dummy.matrix); reflections.setColorAt(i, new THREE.Color(neonColors[i % 4]));
    }
    scene.add(reflections);
    const skyUniforms = { zenith: { value: new THREE.Color('#02050e') }, horizon: { value: new THREE.Color('#102342') }, uTime: { value: 0 } };
    const sky = new THREE.Mesh(new THREE.SphereGeometry(1400, 24, 16), new THREE.ShaderMaterial({
      precision: 'highp', side: THREE.BackSide, depthWrite: false, uniforms: skyUniforms,
      vertexShader: 'varying vec3 direction; void main(){direction=position;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}',
      fragmentShader: `uniform vec3 zenith; uniform vec3 horizon; uniform float uTime; varying vec3 direction;
        void main(){vec3 d=normalize(direction);float gradient=pow(smoothstep(-.015,.34,d.y),.65);
        float layer=sin(d.y*62.+sin(d.x*7.+uTime*.012)*.65)*sin(d.x*11.+d.z*5.);
        vec3 col=mix(horizon,zenith,gradient)+horizon*layer*.10*exp(-max(0.,d.y)*7.);
        gl_FragColor=vec4(col,1.);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
        }`
    })); scene.add(sky);
    const particleCount = 1200, particleData = [];
    for (let i = 0; i < particleCount; i++) {
      const particle = { x: range(-105, 105), y: range(-52, 110),
        z: range(-170, -3), speed: range(.7, 1.4), phase: range(0, Math.PI * 2) };
      particle.sinPhase = Math.sin(particle.phase); particle.cosPhase = Math.cos(particle.phase);
      particleData.push(particle);
    }
    const rainGeometry = new THREE.BufferGeometry();
    rainGeometry.setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(particleCount * 6), 3));
    const rain = new THREE.LineSegments(rainGeometry, new THREE.LineBasicMaterial({ color: '#a6c5e4',
      transparent: true, opacity: .38, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false }));
    rain.frustumCulled = false; scene.add(rain);
    const snowGeometry = new THREE.BufferGeometry();
    snowGeometry.setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(particleCount * 3), 3));
    snowGeometry.setAttribute('flakeSize', new THREE.Float32BufferAttribute(particleData.map(() => range(.24, .7)), 1));
    const snowMaterial = new THREE.ShaderMaterial({ precision: 'highp', transparent: true, depthWrite: false,
      uniforms: { uPixelRatio: { value: 1 } },
      vertexShader: `attribute float flakeSize; uniform float uPixelRatio;
        void main(){vec4 mv=modelViewMatrix*vec4(position,1.);gl_Position=projectionMatrix*mv;
        gl_PointSize=clamp(flakeSize*460.*uPixelRatio/-mv.z,2.,15.);}`,
      fragmentShader: `void main(){float r=length(gl_PointCoord-.5)*2.;if(r>1.)discard;
        gl_FragColor=vec4(.82,.9,1.,(1.-smoothstep(.18,1.,r))*.9);
        #include <colorspace_fragment>
        }`
    });
    const snow = new THREE.Points(snowGeometry, snowMaterial); snow.frustumCulled = false; scene.add(snow);
    const cameraPath = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0, 44, 159), new THREE.Vector3(7, 46, 135),
      new THREE.Vector3(0, 43, 111), new THREE.Vector3(-7, 40, 135)
    ], true, 'catmullrom', .5);
    const lifeView = createThreeLifeView(THREE, scene, life, soft, box, plane);
    threeCity = { renderer, scene, camera, cameraPath, ambient, rim, uniforms, sky, skyUniforms, signMaterials, halos,
      life, lifeView, signBatches, poolMesh, pools, haloMesh, signStates: new Uint8Array(life.signs.length).fill(2),
      reflectionMaterial, particleData, rain, snow, snowMaterial, lastTime: null, width: 0, height: 0, scale: 0,
      phaseColors, snowFog: new THREE.Color('#526779'),
      backgroundTarget: new THREE.Color(), horizonTarget: new THREE.Color(), fogTarget: new THREE.Color() };
    return true;
  } catch (error) {
    disposeCityResources(scene, renderer);
    threeCity = null;
    return false;
  }
}

function disposeCityResources(scene, renderer) {
  const resources = new Set();
  if (scene) scene.traverse(object => {
    if (object.geometry) resources.add(object.geometry);
    for (const material of object.material ? [object.material].flat() : []) {
      for (const value of Object.values(material)) if (value && value.isTexture) resources.add(value);
      resources.add(material);
    }
  });
  for (const resource of resources) resource.dispose();
  if (renderer) renderer.dispose();
}

function disposeThreeCity() {
  if (!threeCity) return;
  disposeCityResources(threeCity.scene, threeCity.renderer);
  threeCity = null;
}

function renderThreeCity(t, cityState) {
  if (!threeCity) throw new Error('Three city is unavailable');
  const c = threeCity, state = cityState, tier = window.NeonCityStatus.tier;
  const scale = window.NeonCityStatus.renderScale;
  const width = Math.max(1, window.innerWidth), height = Math.max(1, window.innerHeight);
  if (c.width !== width || c.height !== height || c.scale !== scale) {
    c.width = width; c.height = height; c.scale = scale;
    // Quality scale is a CSS-pixel ratio, as in WeatherAether, not a Retina multiplier.
    c.renderer.setPixelRatio(scale);
    c.renderer.setSize(width, height, false);
    c.camera.aspect = width / height; c.camera.updateProjectionMatrix();
    c.snowMaterial.uniforms.uPixelRatio.value = c.renderer.getPixelRatio();
  }
  const dt = c.lastTime === null ? 1 / tier.fps : Math.min((t - c.lastTime) / 1000, .1);
  c.lastTime = t;
  const palette = CITY_PALETTES[state.phase] || CITY_PALETTES.night;
  const colors = c.phaseColors[state.phase] || c.phaseColors.night;
  const a = 1 - Math.exp(-dt * 1.8);
  c.backgroundTarget.copy(colors.sky); c.horizonTarget.copy(colors.horizon); c.fogTarget.copy(colors.fog);
  if (state.weather === 'snow') c.fogTarget.lerp(c.snowFog, .22);
  lerpCityColor(c.scene.background, c.backgroundTarget, a); lerpCityColor(c.scene.fog.color, c.fogTarget, a);
  lerpCityColor(c.skyUniforms.zenith.value, c.backgroundTarget, a); lerpCityColor(c.skyUniforms.horizon.value, c.horizonTarget, a);
  c.skyUniforms.uTime.value = t / 1000;
  c.scene.fog.density = Math.fround(c.scene.fog.density + (.0024 + state.fog * .0105 - c.scene.fog.density) * a);
  lerpCityColor(c.rim.color, colors.light, a);
  c.ambient.intensity = Math.fround(c.ambient.intensity + (palette.ambient - c.ambient.intensity) * a);
  c.uniforms.uLit.value = Math.fround(c.uniforms.uLit.value + (state.windowsLit * .44 - c.uniforms.uLit.value) * a);
  const energy = state.neon * (window.NeonCityStatus.neonBrightness || 100) / 100;
  c.uniforms.uNeon.value = Math.fround(c.uniforms.uNeon.value + (energy - c.uniforms.uNeon.value) * a);
  for (let i = 0; i < c.signMaterials.length; i++) {
    c.signMaterials[i].color.setScalar(c.uniforms.uNeon.value * 3.6 + .025);
  }
  c.reflectionMaterial.opacity = (state.rain > 0 ? .15 : .035) * c.uniforms.uNeon.value;
  // A closed ten-minute path, evaluated from absolute time so throttling cannot accelerate it.
  const cycle = (t % 600000) / 600000;
  c.cameraPath.getPointAt(cycle, c.camera.position);
  c.camera.lookAt(Math.sin(cycle * Math.PI * 2 + .6) * 9, 64 + Math.sin(cycle * Math.PI * 2) * 3, -350);
  c.sky.position.copy(c.camera.position);
  const snowy = state.weather === 'snow';
  const swaySin = snowy ? Math.sin(t * .0006) * 2.5 : 0, swayCos = snowy ? Math.cos(t * .0006) * 2.5 : 0;
  const amount = Math.round((snowy ? 900 : state.weather === 'downpour' ? 600 : state.rain * 1200) * tier.countScale);
  c.rain.visible = !snowy && amount > 0; c.snow.visible = snowy;
  c.rain.geometry.setDrawRange(0, amount * 2); c.snow.geometry.setDrawRange(0, amount);
  const positions = (snowy ? c.snow : c.rain).geometry.attributes.position;
  for (let i = 0; i < amount; i++) {
    const p = c.particleData[i];
    p.y -= dt * (snowy ? 3.4 : state.weather === 'downpour' ? 95 : 65) * p.speed;
    if (p.y < -52) p.y += 162;
    const x = p.x + c.camera.position.x + (snowy ? swaySin * p.cosPhase + swayCos * p.sinPhase : 0);
    const y = p.y + c.camera.position.y, z = p.z + c.camera.position.z;
    const j = i * (snowy ? 3 : 6);
    positions.array[j] = x; positions.array[j + 1] = y; positions.array[j + 2] = z;
    if (!snowy) {
      positions.array[j + 3] = x - .25;
      positions.array[j + 4] = y + (state.weather === 'drizzle' ? 1.1 : state.weather === 'downpour' ? 3.4 : 2.3);
      positions.array[j + 5] = z;
    }
  }
  if (amount) positions.needsUpdate = true;
  renderThreeLife(c, dt, state);
  c.renderer.render(c.scene, c.camera);
}

function createThreeLifeView(THREE, scene, life, soft, box, plane) {
  const body = new THREE.InstancedMesh(box, new THREE.MeshStandardMaterial({ color: '#162c3d',
    emissive: '#276875', emissiveIntensity: .25, metalness: .7, roughness: .3 }), 20);
  const lamps = new THREE.InstancedMesh(box, new THREE.MeshBasicMaterial({ toneMapped: false }), 80);
  const glow = new THREE.InstancedMesh(plane, new THREE.MeshBasicMaterial({ map: soft,
    transparent: true, opacity: .65, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }), 20);
  const tracks = new THREE.InstancedMesh(box.clone(), new THREE.MeshBasicMaterial({ transparent: true,
    blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }), 40);
  const InstancedAttribute = tracks.instanceMatrix.constructor;
  tracks.geometry.setAttribute('cityAlpha', new InstancedAttribute(Float32Array.from({ length: 40 }, (_, i) => i < 20 ? .35 : .6), 1));
  tracks.material.onBeforeCompile = shader => {
    shader.vertexShader = 'attribute float cityAlpha; varying float vCityAlpha;\n' + shader.vertexShader;
    shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvCityAlpha=cityAlpha;');
    shader.fragmentShader = 'varying float vCityAlpha;\n' + shader.fragmentShader;
    shader.fragmentShader = shader.fragmentShader.replace('#include <alphamap_fragment>', '#include <alphamap_fragment>\ndiffuseColor.a*=vCityAlpha;');
  };
  const steam = new THREE.InstancedMesh(plane, new THREE.MeshBasicMaterial({ map: soft, color: '#7fabbc',
    transparent: true, opacity: .18, depthWrite: false, blending: THREE.AdditiveBlending }), 48);
  const color = new THREE.Color();
  for (let i = 0; i < 20; i++) {
    color.set(i % 3 ? '#72f9ff' : '#ff357b');
    glow.setColorAt(i, color); tracks.setColorAt(i, color); tracks.setColorAt(20 + i, color);
    for (let j = 0; j < 4; j++) lamps.setColorAt(i * 4 + j, color.set(j < 2 ? '#c9faff' : '#ff225f'));
  }
  for (const mesh of [body, lamps, glow, tracks, steam]) {
    mesh.frustumCulled = false; scene.add(mesh);
  }
  // Build the open cone once with opposite-winding triangle pairs so FrontSide
  // renders both faces without adding another geometry to the vendor bundle.
  const beamGeometry = new THREE.BufferGeometry(), beamVertices = new Float32Array(24 * 18);
  for (let i = 0; i < 24; i++) {
    const a = i / 24 * Math.PI * 2, b = (i + 1) / 24 * Math.PI * 2;
    const ax = Math.cos(a) * 30, az = Math.sin(a) * 30, bx = Math.cos(b) * 30, bz = Math.sin(b) * 30;
    beamVertices.set([0, 0, 0, ax, 150, az, bx, 150, bz, 0, 0, 0, bx, 150, bz, ax, 150, az], i * 18);
  }
  beamGeometry.setAttribute('position', new THREE.Float32BufferAttribute(beamVertices, 3));
  const beam = new THREE.Mesh(beamGeometry, new THREE.MeshBasicMaterial({ color: '#9acbe2',
    transparent: true, opacity: .045, blending: THREE.AdditiveBlending, depthWrite: false }));
  scene.add(beam);
  const blimp = new THREE.Object3D();
  const hull = new THREE.Mesh(new THREE.SphereGeometry(1, 24, 12),
    new THREE.MeshStandardMaterial({ color: '#233448', roughness: .65, metalness: .3, fog: false }));
  hull.scale.set(27, 6, 7); blimp.add(hull);
  const gondola = new THREE.Mesh(box, new THREE.MeshBasicMaterial({ color: '#f8bc7a', toneMapped: false, fog: false }));
  gondola.scale.set(5, 1.3, 3); gondola.position.y = -6; blimp.add(gondola);
  const bannerCanvas = document.createElement('canvas'); bannerCanvas.width = 256; bannerCanvas.height = 64;
  const ctx = bannerCanvas.getContext('2d');
  ctx.fillStyle = '#82fbe8'; ctx.font = 'bold 38px monospace'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.fillText('LUMEN', 128, 34);
  const banner = new THREE.Mesh(plane, new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(bannerCanvas),
    transparent: true, depthWrite: false, toneMapped: false, fog: false }));
  banner.position.set(0, -.5, 7.1); banner.scale.set(30, 7.5, 1); blimp.add(banner); scene.add(blimp);
  return { body, lamps, glow, tracks, streamCount: 0, steam, beam, blimp, banner, gondola,
    color };
}

function lifeInstance(view, mesh, index, x, y, z, w, h, d) {
  // Pooled life instances only translate and scale identity matrices.
  const matrix = mesh.instanceMatrix.array, at = index * 16;
  matrix[at] = w; matrix[at + 5] = h; matrix[at + 10] = d;
  matrix[at + 12] = x; matrix[at + 13] = y; matrix[at + 14] = z;
}

function renderThreeLife(c, dt, state) {
  const life = c.life, view = c.lifeView;
  life.update(dt, state);
  const energy = life.brightness * (window.NeonCityStatus.neonBrightness || 100) / 100;
  // Flicker remains per sign; upload shared color buffers only when it changes.
  let signsChanged = false;
  for (let i = 0; i < life.signs.length; i++) {
    const on = life.signs[i].on ? 1 : 0;
    if (c.signStates[i] !== on) { c.signStates[i] = on; signsChanged = true; }
  }
  if (signsChanged) {
    for (let b = 0; b < c.signBatches.length; b++) {
      const batch = c.signBatches[b];
      for (let i = 0; i < batch.signs.length; i++) {
        const sign = batch.signs[i];
        view.color.copy(sign.tint).multiplyScalar(life.signs[sign.lifeIndex].on ? 1 : .008);
        batch.mesh.setColorAt(i, view.color);
      }
      if (batch.mesh.instanceColor) batch.mesh.instanceColor.needsUpdate = true;
    }
    for (const [mesh, sites] of [[c.poolMesh, c.pools], [c.haloMesh, c.halos]]) {
      for (let i = 0; i < sites.length; i++) {
        const sign = sites[i];
        view.color.copy(sign.tint).multiplyScalar(life.signs[sign.lifeIndex].on ? 1 : 0);
        mesh.setColorAt(i, view.color);
      }
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }
  }
  c.haloMesh.material.color.setScalar(energy);
  view.body.count = view.glow.count = life.vehicles.length;
  view.lamps.count = life.vehicles.length * 4;
  if (view.trackVehicleCount !== life.vehicles.length) {
    // Stable slots retain each vehicle's color. Collapse inactive pooled boxes.
    const matrix = view.tracks.instanceMatrix.array;
    for (let i = 0; i < 40; i++) {
      const at = i * 16; matrix[at] = matrix[at + 5] = matrix[at + 10] = 0;
    }
    view.trackVehicleCount = life.vehicles.length;
  }
  view.streamCount = 0;
  for (let i = 0; i < life.vehicles.length; i++) {
    const vehicle = life.vehicles[i], lane = life.lanes[vehicle.lane];
    const z = lane.z0 + (lane.z1 - lane.z0) * vehicle.t, direction = lane.z1 > lane.z0 ? 1 : -1;
    lifeInstance(view, view.body, i, lane.x, lane.y, z, 1.4, .45, 2.8);
    lifeInstance(view, view.glow, i, lane.x, lane.y - .35, z, 6, 2.2, 1);
    lifeInstance(view, view.tracks, i, lane.x, lane.y - .12, z - direction * 4.5, .14, .08, 6);
    for (let j = 0; j < 4; j++) {
      lifeInstance(view, view.lamps, i * 4 + j, lane.x + (j % 2 ? .48 : -.48), lane.y,
        z + direction * (j < 2 ? 1.45 : -1.45), .28, .16, .18);
    }
    if (lane.y < 2) {
      lifeInstance(view, view.tracks, 20 + i, lane.x, -.65, z - direction * 8, .32, .1, 16);
      view.streamCount++;
    }
  }
  view.body.material.emissiveIntensity = .25 * energy;
  view.lamps.material.color.setScalar(energy * 2.5);
  view.glow.material.opacity = .65 * energy; view.tracks.material.opacity = energy;
  view.body.instanceMatrix.needsUpdate = view.lamps.instanceMatrix.needsUpdate = true;
  view.glow.instanceMatrix.needsUpdate = view.tracks.instanceMatrix.needsUpdate = true;
  view.steam.count = 0;
  for (let i = 0; i < life.events.length; i++) {
    const event = life.events[i], tower = event.tower;
    if (event.type === 'searchlight') {
      view.beam.visible = event.active;
      if (event.active && tower) {
        view.beam.position.set(tower.x, tower.h + 1, tower.z);
        view.beam.rotation.set(.35, 0, Math.sin(event.t * Math.PI * 2) * .65);
        view.beam.material.opacity = .045 * energy * Math.sin(Math.PI * event.t);
      }
    } else if (event.type === 'blimp') {
      view.blimp.visible = event.active;
      view.blimp.position.set(-330 + event.t * 660, 156 + Math.sin(event.t * Math.PI * 2) * 3, -735);
      view.banner.material.color.setScalar(energy); view.gondola.material.color.setScalar(energy);
    } else if (event.active && tower) {
      const envelope = Math.sin(event.t * Math.PI);
      for (let puff = 0; puff < 8; puff++) {
        const rise = (event.t * 2 + puff / 8) % 1, size = 5 + rise * 11, index = view.steam.count++;
        lifeInstance(view, view.steam, index, tower.x + Math.sin(rise * 4 + i) * rise * 5,
          tower.h + 2 + rise * 24, tower.z + tower.d / 3, size, size * 1.3, 1);
        view.steam.setColorAt(index, view.color.setScalar((1 - rise) * envelope));
      }
    }
  }
  view.steam.material.opacity = .18 * (state.phase === 'day' ? .35 : 1);
  view.steam.instanceMatrix.needsUpdate = true;
  if (view.steam.instanceColor) view.steam.instanceColor.needsUpdate = true;
}
