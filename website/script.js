(() => {
  "use strict";

  /* ---------- MilkDrop-style hero ----------
     Two passes, like a MilkDrop preset: a warp pass that feeds the previous frame
     back through zoom/rotation/warp with decay and draws a wave on top, then a
     composite pass to the screen. Everything runs at reduced resolution and the
     browser scales the canvas up, which keeps it cheap on mobile GPUs. */

  const presets = [
    { name: "Violet hour",    wave: 0, zoom: 1.018, rot: 0.006,  warp: 0.0045, decay: 0.962, c: ["#8f74ea", "#d8baff", "#5fc9b8"] },
    { name: "Night bus",      wave: 1, zoom: 1.012, rot: -0.011, warp: 0.007,  decay: 0.955, c: ["#e48b5f", "#d48bb0", "#8f74ea"] },
    { name: "Open water",     wave: 2, zoom: 0.988, rot: 0.009,  warp: 0.011,  decay: 0.968, c: ["#2fa596", "#b8e5ce", "#7fa6e8"] },
    { name: "Sunroom",        wave: 3, zoom: 1.025, rot: 0.003,  warp: 0.003,  decay: 0.95,  c: ["#e6cb6a", "#f0a77f", "#d8baff"] },
    { name: "After the rain", wave: 4, zoom: 1.006, rot: -0.018, warp: 0.006,  decay: 0.958, c: ["#d48bb0", "#7fa6e8", "#b8e5ce"] }
  ];

  const root = document.documentElement;
  const hero = document.querySelector(".hero");
  const canvas = document.querySelector(".viz");
  const nameEl = document.querySelector(".preset-name");
  const nextButton = document.querySelector(".next-preset");
  const motionButton = document.querySelector(".toggle-motion");
  const reducedQuery = window.matchMedia("(prefers-reduced-motion: reduce)");

  let index = 0;
  let paused = false;
  let visible = true;
  let frame = 0;
  let renderer = null;

  function applyColours() {
    const p = presets[index];
    nameEl.textContent = p.name;
    p.c.forEach((hex, i) => root.style.setProperty(`--c${i + 1}`, hex));
  }

  const vertexSource = `
    attribute vec2 pos; varying vec2 uv;
    void main() { uv = pos * 0.5 + 0.5; gl_Position = vec4(pos, 0.0, 1.0); }`;

  const warpSource = `
    precision mediump float;
    varying vec2 uv;
    uniform sampler2D prev;
    uniform vec2 res;
    uniform float t, beat, zoom, rot, warp, decay, wave;
    uniform vec3 c1, c2, c3;

    float waveDist(vec2 q, float r, float a) {
      if (wave < 0.5) {            // breathing ring
        return abs(r - (0.2 + 0.05 * beat + 0.025 * sin(a * 5.0 + t * 2.0)));
      } else if (wave < 1.5) {     // oscilloscope line
        float y = 0.13 * sin(q.x * 9.0 + t * 3.0) * sin(q.x * 2.3 - t) * (0.5 + beat);
        return abs(q.y - y);
      } else if (wave < 2.5) {     // lissajous dots
        float d = 1.0;
        for (int i = 0; i < 14; i++) {
          float f = float(i);
          vec2 p = 0.32 * vec2(sin(t * 1.1 + f * 0.45), sin(t * 1.6 + f * 0.7));
          d = min(d, length(q - p) - 0.006 - 0.012 * beat);
        }
        return max(d, 0.0);
      } else if (wave < 3.5) {     // six-fold kaleidoscope
        float s = 1.0471976;
        float k = mod(a, s) - s * 0.5;
        vec2 p = r * vec2(cos(k), sin(k));
        return r > 0.42 ? 1.0 : abs(p.y - 0.05 * sin(p.x * 22.0 - t * 4.0) * (0.4 + beat));
      }
      float sp = fract(a / 6.2831853 * 2.0 - r * 3.5 + t * 0.2);  // spiral
      return r > 0.4 ? 1.0 : abs(sp - 0.5) * r * 0.9;
    }

    void main() {
      float aspect = res.x / res.y;
      vec2 q = uv - 0.5; q.x *= aspect;
      float r = length(q), a = atan(q.y, q.x);

      float z = zoom + 0.012 * sin(t * 0.7 + r * 7.0) + 0.01 * beat;
      float ro = rot * (1.0 + 0.6 * sin(t * 0.31));
      vec2 w = mat2(cos(ro), -sin(ro), sin(ro), cos(ro)) * q / z;
      w += warp * vec2(sin(w.y * 7.0 + t * 1.3), cos(w.x * 6.0 - t * 1.1));
      w.x /= aspect;

      vec3 col = texture2D(prev, w + 0.5).rgb;
      col = mix(col, col.brg, 0.004) * decay - 1.5 / 255.0;

      float d = waveDist(q, r, a);
      float line = 1.0 - smoothstep(0.0, 0.012, d);
      vec3 wc = mix(c1, c2, 0.5 + 0.5 * sin(a * 2.0 + t * 0.8));
      wc = mix(wc, c3, beat * 0.6);
      col += line * wc * 0.5;
      gl_FragColor = vec4(max(col, 0.0), 1.0);
    }`;

  const showSource = `
    precision mediump float;
    varying vec2 uv;
    uniform sampler2D prev;
    void main() {
      vec3 col = texture2D(prev, uv).rgb;
      col = 1.0 - exp(-col * 1.2);
      float lum = dot(col, vec3(0.299, 0.587, 0.114));
      col = clamp(mix(vec3(lum), col, 1.6), 0.0, 1.0);
      vec3 ink = vec3(0.09, 0.07, 0.12);
      float v = 1.0 - smoothstep(0.3, 1.1, length(uv - 0.5));
      gl_FragColor = vec4(mix(ink, ink + col * (1.0 - ink), v * 0.85 + 0.15), 1.0);
    }`;

  function createRenderer() {
    const gl = canvas.getContext("webgl", { antialias: false, alpha: false, depth: false, powerPreference: "low-power", preserveDrawingBuffer: false });
    if (!gl) return null;

    function program(fragmentSource) {
      const prog = gl.createProgram();
      for (const [type, src] of [[gl.VERTEX_SHADER, vertexSource], [gl.FRAGMENT_SHADER, fragmentSource]]) {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, src);
        gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
        gl.attachShader(prog, shader);
      }
      gl.bindAttribLocation(prog, 0, "pos");
      gl.linkProgram(prog);
      const uniforms = {};
      const count = gl.getProgramParameter(prog, gl.ACTIVE_UNIFORMS);
      for (let i = 0; i < count; i++) {
        const { name } = gl.getActiveUniform(prog, i);
        uniforms[name] = gl.getUniformLocation(prog, name);
      }
      return { prog, uniforms };
    }

    const warpProg = program(warpSource);
    const showProg = program(showSource);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    let targets = [];
    let width = 0;
    let height = 0;

    function resize() {
      const rect = canvas.getBoundingClientRect();
      // Half of CSS resolution, capped: the soft upscale suits the look and saves fill rate.
      const scale = Math.min(0.5, 640 / Math.max(rect.width, 1));
      const w = Math.max(64, Math.round(rect.width * scale));
      const h = Math.max(64, Math.round(rect.height * scale));
      if (w === width && h === height) return false;
      width = canvas.width = w;
      height = canvas.height = h;
      targets.forEach(t => { gl.deleteTexture(t.tex); gl.deleteFramebuffer(t.fb); });
      targets = [0, 1].map(() => {
        const tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        const fb = gl.createFramebuffer();
        gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
        gl.clearColor(0, 0, 0, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        return { tex, fb };
      });
      return true;
    }

    function hexToRgb(hex) {
      const n = parseInt(hex.slice(1), 16);
      return [(n >> 16) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
    }

    function step(t, show) {
      const p = presets[index];
      const phase = (t * 2) % 1; // a steady 120 bpm pulse stands in for audio
      const beat = Math.exp(-phase * 6) * (0.7 + 0.3 * Math.sin(t * 0.37));
      const [src, dst] = frame % 2 ? [targets[1], targets[0]] : [targets[0], targets[1]];
      frame++;

      gl.useProgram(warpProg.prog);
      const u = warpProg.uniforms;
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb);
      gl.viewport(0, 0, width, height);
      gl.bindTexture(gl.TEXTURE_2D, src.tex);
      gl.uniform2f(u.res, width, height);
      gl.uniform1f(u.t, t);
      gl.uniform1f(u.beat, beat);
      gl.uniform1f(u.zoom, p.zoom);
      gl.uniform1f(u.rot, p.rot);
      gl.uniform1f(u.warp, p.warp);
      gl.uniform1f(u.decay, p.decay);
      gl.uniform1f(u.wave, p.wave);
      p.c.forEach((hex, i) => gl.uniform3fv(u[`c${i + 1}`], hexToRgb(hex)));
      gl.drawArrays(gl.TRIANGLES, 0, 3);

      if (!show) return;
      gl.useProgram(showProg.prog);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.bindTexture(gl.TEXTURE_2D, dst.tex);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }

    function clear() {
      targets.forEach(t => {
        gl.bindFramebuffer(gl.FRAMEBUFFER, t.fb);
        gl.clear(gl.COLOR_BUFFER_BIT);
      });
    }

    resize();
    return { resize, step, clear };
  }

  let start = performance.now();
  let raf = 0;
  let presetTimer = 0;

  function running() { return renderer && visible && !paused && !reducedQuery.matches && !document.hidden; }

  function loop(now) {
    raf = 0;
    if (!running()) return;
    renderer.step((now - start) / 1000, true);
    raf = requestAnimationFrame(loop);
  }

  function update() {
    if (running()) {
      if (!raf) raf = requestAnimationFrame(loop);
      if (!presetTimer) presetTimer = setTimeout(nextPreset, 10000);
    } else {
      if (raf) {
        cancelAnimationFrame(raf);
        raf = 0;
      }
      clearTimeout(presetTimer);
      presetTimer = 0;
    }
  }

  function nextPreset() {
    clearTimeout(presetTimer);
    presetTimer = 0;
    index = (index + 1) % presets.length;
    applyColours();
    if (!running()) still();
    update();
  }

  // Reduced motion: simulate a couple of seconds off-screen and show one still frame.
  function still() {
    if (!renderer) return;
    renderer.clear();
    const t0 = 7 + index * 3;
    for (let i = 0; i < 150; i++) renderer.step(t0 + i / 60, i === 149);
  }

  function syncMotionUi() {
    motionButton.hidden = !renderer || reducedQuery.matches;
    motionButton.textContent = paused ? "Play" : "Pause";
    motionButton.setAttribute("aria-pressed", String(paused));
    if (reducedQuery.matches) still();
  }

  try {
    renderer = createRenderer();
  } catch {
    renderer = null;
  }
  if (!renderer) {
    canvas.hidden = true;
    motionButton.hidden = true;
  }

  canvas.addEventListener("webglcontextlost", event => {
    event.preventDefault();
    renderer = null;
    canvas.hidden = true;
    motionButton.hidden = true;
    update();
  });

  nextButton.addEventListener("click", nextPreset);
  motionButton.addEventListener("click", () => {
    paused = !paused;
    if (!paused) start = performance.now() - frame * 16;
    syncMotionUi();
    update();
  });
  reducedQuery.addEventListener("change", () => { syncMotionUi(); update(); });
  document.addEventListener("visibilitychange", update);

  new IntersectionObserver(entries => {
    visible = entries[0].isIntersecting;
    update();
  }).observe(hero);

  let resizeTimer;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      if (renderer && renderer.resize() && !running()) still();
    }, 150);
  });

  applyColours();
  if (renderer) syncMotionUi();
  update();

  /* ---------- Copy buttons ---------- */
  const copyIcon = '<svg class="icon copy-icon" aria-hidden="true" focusable="false"><use href="assets/icons.svg#content_copy"/></svg>';
  const checkIcon = '<svg class="icon check-icon" aria-hidden="true" focusable="false"><use href="assets/icons.svg#check"/></svg>';
  const status = document.createElement("span");
  status.className = "sr-only";
  status.setAttribute("role", "status");
  document.body.append(status);

  document.querySelectorAll(".code-block").forEach(block => {
    const button = document.createElement("button");
    const label = block.dataset.copyLabel;
    button.type = "button";
    button.className = "copy-button";
    button.innerHTML = copyIcon + checkIcon;
    button.setAttribute("aria-label", label);
    button.title = label;
    let reset;
    function restore() {
      delete button.dataset.copied;
      button.setAttribute("aria-label", label);
      button.title = label;
    }
    button.addEventListener("click", async () => {
      clearTimeout(reset);
      try {
        await navigator.clipboard.writeText(block.querySelector("code").textContent);
        button.dataset.copied = "true";
        button.setAttribute("aria-label", "Copied");
        button.title = "Copied";
        status.textContent = "Copied to clipboard";
      } catch {
        restore();
        status.textContent = "Could not copy. Select the code and copy it manually.";
      }
      reset = setTimeout(() => { restore(); status.textContent = ""; }, 2000);
    });
    block.append(button);
  });
})();
