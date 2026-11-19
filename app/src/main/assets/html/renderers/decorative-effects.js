/* Fixed decorative effects and adaptive budget. No own animation loop/readbacks. */
(function (root) {
  'use strict';
  function revealAt(reveal, index, time, enabled) {
    if (!reveal || !enabled) return 1;
    const t = Math.max(0, Math.min(1, (time - index * reveal.stagger) / reveal.duration));
    return t * t * (3 - 2 * t);
  }
  function ease(value, mode) {
    const t = Math.max(0, Math.min(1, value));
    if (mode === 'linear') return t;
    if (mode === 'ease_in') return t * t;
    if (mode === 'ease_out') return 1 - (1 - t) * (1 - t);
    if (mode === 'elastic') return t <= 0 || t >= 1 ? t : t + Math.sin(t * Math.PI * 3) * (1 - t) * .08;
    return t * t * (3 - 2 * t);
  }
  function choreography(animation, time, index = 0) {
    const a = animation || {};
    if (!a.mode) return { progress: 1, opacity: 1, scale: 1, rotation: 0, draw: 1 };
    const duration = Math.max(.2, Number(a.duration) || 1.8), delay = Math.max(0, Number(a.delay) || 0) + index * Math.max(0, Number(a.stagger) || 0);
    const raw = time - delay;
    const cycle = a.loop === false ? Math.max(0, Math.min(duration, raw)) : ((raw % duration) + duration) % duration;
    const progress = ease(cycle / duration, a.easing);
    const wave = Math.sin((cycle / duration) * Math.PI * 2 * (Number(a.frequency) || 1) + (Number(a.phase) || 0) * Math.PI / 180);
    const amplitude = Math.max(0, Math.min(1, Number(a.amplitude) || .12));
    switch (a.mode) {
      case 'pulse': return { progress, opacity: 1, scale: 1 + wave * amplitude, rotation: 0, draw: 1 };
      case 'draw': return { progress, opacity: 1, scale: 1, rotation: 0, draw: progress };
      case 'flicker': return { progress, opacity: Math.max(.15, 1 - amplitude * .5 + wave * amplitude * .5), scale: 1, rotation: 0, draw: 1 };
      case 'orbit': return { progress, opacity: 1, scale: 1, rotation: wave * amplitude * 18, draw: 1 };
      case 'sequence': return { progress, opacity: progress, scale: .85 + .15 * progress, rotation: 0, draw: progress };
      default: return { progress, opacity: 1, scale: 1, rotation: wave * amplitude * 8, draw: 1 };
    }
  }
  function createBudget() {
    let tier = 0, pressure = 0, floor = 0, frames = 0, total = 0, max = 0;
    return {
      sample(drawMs, intervalMs) {
        frames++; total += drawMs; max = Math.max(max, drawMs);
        if (drawMs > 12 || intervalMs > 75) pressure++; else pressure = Math.max(0, pressure - .25);
        if (pressure >= 12 && tier < 2) { tier++; pressure = 0; }
      },
      constrain(environment) { floor = environment.lowMemory || environment.powerSave || environment.hardwareAccelerated === false ? 2 : 0; },
      level() { return Math.max(tier, floor); },
      interval() { return [1000 / 30, 1000 / 24, 1000 / 15][this.level()]; },
      snapshot() { return { quality: ['normal', 'balanced', 'low'][this.level()], frames, meanDrawMs: frames ? total / frames : 0, maxDrawMs: max }; },
    };
  }
  function create(canvas, spec) {
    const dust = spec.stardust, trail = spec.trail;
    const ctx = canvas.getContext('2d', { alpha: true });
    if (!ctx) throw new Error('星尘画布不可用');
    let state = (dust ? dust.seed : 42) >>> 0;
    const random = () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
    const particles = dust ? Array.from({ length: Math.min(128, dust.count) }, () => ({ angle: random() * Math.PI * 2,
      radius: dust.radius * (.25 + random() * .75), speed: (.6 + random() * .8) * dust.speed * Math.PI / 180,
      size: dust.size * (.55 + random() * .45), phase: random() * Math.PI * 2 })) : [];
    const sprite = document.createElement('canvas'); sprite.width = sprite.height = 32;
    const spriteContext = sprite.getContext('2d');
    const gradient = spriteContext.createRadialGradient(16, 16, 0, 16, 16, 16);
    gradient.addColorStop(0, '#ffffff'); gradient.addColorStop(.15, dust ? dust.color : '#a78bfa'); gradient.addColorStop(1, 'transparent');
    spriteContext.fillStyle = gradient; spriteContext.fillRect(0, 0, 32, 32);
    let width = 0, height = 0, density = 1, disposed = false, draws = 0;
    return {
      render(time, zoom, quality, reduced, opacity = 1, pan = { x: 0, y: 0 }) {
        if (disposed || !dust) return;
        const parent = canvas.parentElement;
        const w = Math.max(1, parent.clientWidth), h = Math.max(1, parent.clientHeight);
        const scale = Math.min(1, (quality === 2 ? 640 : 900) / Math.max(w, h));
        const nextW = Math.max(1, Math.round(w * scale)), nextH = Math.max(1, Math.round(h * scale));
        const nextD = quality ? 1 : Math.min(2, Math.max(1, Math.floor(Math.sqrt(1200000 / (nextW * nextH)))));
        if (nextW !== width || nextH !== height || nextD !== density) {
          width = nextW; height = nextH; density = nextD; canvas.width = width * density; canvas.height = height * density;
        }
        ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, canvas.width, canvas.height);
        const baseUnit = Math.min(canvas.width, canvas.height) / 220, unit = baseUnit * zoom;
        ctx.translate(canvas.width / 2 + baseUnit * pan.x, canvas.height / 2 + baseUnit * pan.y);
        const count = Math.max(1, Math.ceil(particles.length * [1, .65, .35][quality]));
        const samples = trail && !reduced ? Math.max(1, Math.ceil(trail.samples * [1, .5, .25][quality])) : 0;
        for (let i = 0; i < count; i++) {
          const particle = particles[i];
          for (let j = samples; j >= 0; j--) {
            const age = j && trail ? trail.duration * j / samples : 0;
            const t = time - age, angle = particle.angle + t * particle.speed;
            const radius = particle.radius + (reduced ? 0 : Math.sin(t * .7 + particle.phase) * 1.5);
            const size = particle.size * unit * 4;
            ctx.globalAlpha = opacity * (j ? trail.opacity * (1 - j / (samples + 1)) : .7 + .3 * Math.sin(t + particle.phase) ** 2);
            ctx.drawImage(sprite, Math.cos(angle) * radius * unit - size / 2, Math.sin(angle) * radius * unit - size / 2, size, size);
          }
        }
        ctx.globalAlpha = 1; draws++;
      },
      snapshot() { return { particleCount: particles.length, draws, pixels: canvas.width * canvas.height }; },
      remove() { disposed = true; canvas.width = canvas.height = sprite.width = sprite.height = 1; },
    };
  }
  const api = Object.freeze({ revealAt, choreography, createBudget, create });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RikkaEffects = api;
})(typeof window === 'object' ? window : globalThis);
