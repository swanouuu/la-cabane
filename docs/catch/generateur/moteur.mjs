// Le moteur des maquettes du catch : la scène (tampon de profondeur qu'on peut sauver et remettre, pour redessiner
// les catcheurs sur un même décor), un corps articulé fait de pavés (les catcheurs gonflés, le public), deux
// éclairages (le studio des planches de tenues, et la salle sombre avec les projecteurs sur le ring), le texte et
// les effets (étoiles d'impact, ondes de choc, flammes, faisceaux de lumière).
// Unités : le pixel du modèle du joueur (16 par bloc) ; y vers le haut ; un perso regarde vers +z, sa gauche est +x.
import { lire, ecrirePng, hache, norme, croix, POLICE } from "../../fruits/generateur/moteur.mjs";

export { lire, ecrirePng, hache, norme, croix, POLICE };
export const DEG = Math.PI / 180;
export const ajoute = (a, b) => a.map((v, i) => v + b[i]);
export const sous = (a, b) => a.map((v, i) => v - b[i]);
export const fois = (a, k) => a.map((v) => v * k);
export const melange = (a, b, k) => a.map((v, i) => v + (b[i] - v) * k);
export const scal = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
export const lisse = (t) => { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); };
export const rotX = (v, a) => [v[0], v[1] * Math.cos(a) - v[2] * Math.sin(a), v[1] * Math.sin(a) + v[2] * Math.cos(a)];
export const rotY = (v, a) => [v[0] * Math.cos(a) + v[2] * Math.sin(a), v[1], -v[0] * Math.sin(a) + v[2] * Math.cos(a)];
export const rotZ = (v, a) => [v[0] * Math.cos(a) - v[1] * Math.sin(a), v[0] * Math.sin(a) + v[1] * Math.cos(a), v[2]];
export const ton = (c, k) => c.slice(0, 3).map((v) => v * k);

Object.assign(POLICE, {
  "?": "110001010000010", "@": "010101111100011", "%": "101001010100101", "&": "010101010101011", "*": "000101010101000", "#": "101111101111101",
  "(": "010100100100010", ")": "010001001001010", "=": "000111000111000", ">": "100010001010100", "<": "001010100010001",
});

// ------------------------------------------------------------------ la scène
/**
 * Une vue : lacet / tangage en degrés, echelle en pixels d'image par unité, centre (en unités) au point `pied` de
 * l'image. fond(x, y) donne la couleur du fond. sauver() / remettre() gardent le décor déjà dessiné.
 */
export function scene({ w, h, lacet = -30, tangage = 30, echelle = 1, centre = [0, 0, 0], pied = null, fond = () => [236, 236, 242], transparent = false }) {
  const img = Buffer.alloc(w * h * 4);
  const z = new Float32Array(w * h).fill(-1e9);
  if (!transparent) for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) img.set([...fond(x, y).map((v) => Math.max(0, Math.min(255, Math.round(v)))), 255], (y * w + x) * 4);
  const a = lacet * DEG, b = tangage * DEG, py = pied ?? h / 2;
  const vue = ([x0, y0, z0]) => {
    const x = x0 - centre[0], y = y0 - centre[1], zz = z0 - centre[2];
    const x1 = x * Math.cos(a) + zz * Math.sin(a), z1 = -x * Math.sin(a) + zz * Math.cos(a);
    const y2 = y * Math.cos(b) - z1 * Math.sin(b), z2 = y * Math.sin(b) + z1 * Math.cos(b);
    return [w / 2 + x1 * echelle, py - y2 * echelle, z2];
  };
  const dirVue = (n) => {
    const x1 = n[0] * Math.cos(a) + n[2] * Math.sin(a), z1 = -n[0] * Math.sin(a) + n[2] * Math.cos(a);
    return [x1, n[1] * Math.cos(b) - z1 * Math.sin(b), n[1] * Math.sin(b) + z1 * Math.cos(b)];
  };
  /** Le sens vers la caméra, dans le monde. */
  const versCamera = norme([-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)]);
  const visible = (n) => dirVue(n)[2] > 0.01;
  const pose = (x, y, c, zp) => {
    const i = y * w + x;
    if (zp <= z[i]) return;
    z[i] = zp;
    img[i * 4] = Math.max(0, Math.min(255, c[0])); img[i * 4 + 1] = Math.max(0, Math.min(255, c[1])); img[i * 4 + 2] = Math.max(0, Math.min(255, c[2])); img[i * 4 + 3] = 255;
  };
  /** Un quadrilatère plein, d'une couleur, avec la profondeur de son plan (decale le rapproche un peu). */
  const quad = (coins, c, decale = 0) => {
    const pts = coins.map(vue);
    const [p0, p1, p2] = Math.hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]) > 1e-6 ? [pts[0], pts[1], pts[2]] : [pts[0], pts[2], pts[3]];
    const ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2], vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    const zc = pts.reduce((s, p) => s + p[2], 0) / 4;
    const prof = (x, y) => (Math.abs(nz) < 1e-9 ? zc : p0[2] - (nx * (x - p0[0]) + ny * (y - p0[1])) / nz) + decale;
    const cx = pts.reduce((s, p) => s + p[0], 0) / 4, cy = pts.reduce((s, p) => s + p[1], 0) / 4;
    const q = pts.map(([x, y]) => [cx + (x - cx) * 1.06 + Math.sign(x - cx) * 0.25, cy + (y - cy) * 1.06 + Math.sign(y - cy) * 0.25]);
    const xs = q.map((p) => p[0]), ys = q.map((p) => p[1]);
    const y0 = Math.max(0, Math.floor(Math.min(...ys))), y1 = Math.min(h - 1, Math.ceil(Math.max(...ys)));
    const x0 = Math.max(0, Math.floor(Math.min(...xs))), x1 = Math.min(w - 1, Math.ceil(Math.max(...xs)));
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      let dedans = true, signe = 0;
      for (let k = 0; k < 4 && dedans; k++) {
        const [ax, ay] = q[k], [bx, by] = q[(k + 1) % 4];
        const e = (bx - ax) * (y + 0.5 - ay) - (by - ay) * (x + 0.5 - ax);
        if (e !== 0) { const sg = Math.sign(e); if (signe === 0) signe = sg; else if (sg !== signe) dedans = false; }
      }
      if (dedans) pose(x, y, c, prof(x + 0.5, y + 0.5));
    }
  };
  /** Un quadrilatère transparent : il teinte ce qui est derrière lui sans rien cacher (faisceaux, voiles). */
  const voile = (coins, c, alpha, ajout = false) => {
    const pts = coins.map(vue);
    const [p0, p1, p2] = [pts[0], pts[1], pts[2]];
    const ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2], vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
    const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    if (Math.abs(nz) < 1e-9) return;
    const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    for (let y = Math.max(0, Math.floor(Math.min(...ys))); y <= Math.min(h - 1, Math.ceil(Math.max(...ys))); y++) {
      for (let x = Math.max(0, Math.floor(Math.min(...xs))); x <= Math.min(w - 1, Math.ceil(Math.max(...xs))); x++) {
        let dedans = true, signe = 0;
        for (let k = 0; k < 4 && dedans; k++) {
          const [ax, ay] = pts[k], [bx, by] = pts[(k + 1) % 4];
          const e = (bx - ax) * (y + 0.5 - ay) - (by - ay) * (x + 0.5 - ax);
          if (e !== 0) { const sg = Math.sign(e); if (signe === 0) signe = sg; else if (sg !== signe) dedans = false; }
        }
        if (!dedans) continue;
        const zp = p0[2] - (nx * (x + 0.5 - p0[0]) + ny * (y + 0.5 - p0[1])) / nz;
        if (zp <= z[y * w + x]) continue;
        const i = (y * w + x) * 4, al = typeof alpha === "function" ? alpha(x, y) : alpha;
        for (let k = 0; k < 3; k++) img[i + k] = ajout ? Math.min(255, img[i + k] + c[k] * al) : Math.round(img[i + k] * (1 - al) + c[k] * al);
      }
    }
  };
  return {
    img, z, w, h, quad, voile, vue, dirVue, versCamera, visible, echelle,
    sauver: () => ({ img: Buffer.from(img), z: Float32Array.from(z) }),
    remettre: (s) => { s.img.copy(img); z.set(s.z); },
  };
}

// ------------------------------------------------------------------ la lumière
/**
 * L'éclairage courant. "studio" : une lumière douce de face à gauche (les planches de tenues). "salle" : la salle
 * sombre, un peu de lumière violette partout et le gros projecteur au-dessus du ring (spot, r1 → r2 : où il s'éteint).
 */
export const LUMIERE = {
  mode: "studio", ambiant: [0.34, 0.31, 0.46], zenith: [0.2, 0.18, 0.26],
  // les projecteurs : p (unités), r1 → r2 (rayon où la lumière s'éteint), c (couleur et force), ombre(P) → caché ?
  spots: [],
  soleil: norme([-0.5, 0.8, 0.35]),
};

export function eclaire(sc, N, P = null, ombre = false) {
  const L = LUMIERE;
  if (L.mode === "studio") {
    const v = sc.dirVue(N), l = norme([-0.45, 0.55, 0.7]);
    const d = Math.max(0, v[0] * l[0] + v[1] * l[1] + v[2] * l[2]);
    const k = 0.5 + 0.62 * d;
    return [k * 1.03, k, k * 0.98 + 0.04 * (1 - d)];
  }
  if (L.mode === "jour") {
    const d = Math.max(0, scal(N, L.soleil)), cache = ombre || (P && L.ombreSoleil && d > 0 && L.ombreSoleil(P));
    const base = N[1] < -0.5 ? 0.5 : 0.62 + 0.44 * d;
    return cache || d === 0 ? [0.6, 0.63, 0.78].map((v) => v * (0.62 + 0.4 * (base / 1.06))) : [1.06, 1.0, 0.9].map((v) => v * base);
  }
  const haut = Math.max(0, N[1]);
  let r = L.ambiant.map((v, i) => v * (0.8 + 0.25 * haut + 0.1 * Math.abs(N[0])) + L.zenith[i] * haut);
  if (P) for (const s of L.spots) {
    const d = sous(s.p, P), dist = Math.hypot(d[0], d[2]);
    if (dist >= s.r2) continue;
    const cone = 1 - lisse((dist - s.r1) / (s.r2 - s.r1)), ndl = Math.max(0, scal(N, norme(d)));
    if (ndl <= 0) continue;
    const vu = ombre || (s.ombre && s.ombre(P)) ? 0.12 : 1;
    r = r.map((v, i) => v + s.c[i] * cone * ndl * vu);
  }
  return r;
}

// ------------------------------------------------------------------ les pavés et les corps
const FACES = [
  { nom: "devant", N: [0, 0, 1], o: (a, b) => [a[0], b[1], b[2]], U: (a, b) => [b[0] - a[0], 0, 0], V: (a, b) => [0, a[1] - b[1], 0] },
  { nom: "dos", N: [0, 0, -1], o: (a, b) => [b[0], b[1], a[2]], U: (a, b) => [a[0] - b[0], 0, 0], V: (a, b) => [0, a[1] - b[1], 0] },
  { nom: "droite", N: [-1, 0, 0], o: (a, b) => [a[0], b[1], a[2]], U: (a, b) => [0, 0, b[2] - a[2]], V: (a, b) => [0, a[1] - b[1], 0] },
  { nom: "gauche", N: [1, 0, 0], o: (a, b) => [b[0], b[1], b[2]], U: (a, b) => [0, 0, a[2] - b[2]], V: (a, b) => [0, a[1] - b[1], 0] },
  { nom: "haut", N: [0, 1, 0], o: (a, b) => [a[0], b[1], a[2]], U: (a, b) => [b[0] - a[0], 0, 0], V: (a, b) => [0, 0, b[2] - a[2]] },
  { nom: "bas", N: [0, -1, 0], o: (a, b) => [a[0], a[1], b[2]], U: (a, b) => [b[0] - a[0], 0, 0], V: (a, b) => [0, 0, a[2] - b[2]] },
];

/** Le point (local) d'une face d'un pavé de a à b, en (u, v) de 0 à 1. */
export function pointFace(a, b, face, u, v) {
  const f = FACES.find((g) => g.nom === face);
  return ajoute(ajoute(f.o(a, b), fois(f.U(a, b), u)), fois(f.V(a, b), v));
}

/**
 * Un pavé de a à b (en coordonnées locales), passé par T = { p, n } (point, normale → monde). couleur(face, u, v, i, j)
 * rend [r, g, b] (éclairé), [r, g, b, 1] (qui brille) ou null (percé). pas : la taille des cases.
 */
export function pave(sc, a, b, T, couleur, pas = 1, decale = 0) {
  for (const f of FACES) {
    const N = norme(T.n(f.N));
    if (!sc.visible(N)) continue;
    const O = f.o(a, b), U = f.U(a, b), V = f.V(a, b);
    const nu = Math.max(1, Math.round(Math.hypot(...U) / pas)), nv = Math.max(1, Math.round(Math.hypot(...V) / pas));
    const pt = (s, t) => T.p(ajoute(ajoute(O, fois(U, s / nu)), fois(V, t / nv)));
    let lumMoy = null;
    for (let j = 0; j < nv; j++) for (let i = 0; i < nu; i++) {
      const c = typeof couleur === "function" ? couleur(f.nom, (i + 0.5) / nu, (j + 0.5) / nv, i, j, nu, nv) : couleur;
      if (!c) continue;
      let col;
      if (c[3] === 1) col = c.slice(0, 3);
      else {
        if (!lumMoy || LUMIERE.mode !== "studio") lumMoy = eclaire(sc, N, pt(i + 0.5, j + 0.5));
        col = c.map((v, k) => v * lumMoy[k]);
      }
      sc.quad([pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)], col, decale);
    }
  }
}

/** Le repère d'un corps : position des hanches (unités), lacet / tangage / roulis en degrés. */
export function repereRacine(o) {
  const l = (o.lacet ?? 0) * DEG, t = (o.tangage ?? 0) * DEG, r = (o.roulis ?? 0) * DEG;
  const pos = o.pos ?? [0, 12, 0];
  const R = (v) => rotY(rotX(rotZ(v, r), t), l);
  return { p: (v) => ajoute(R(sous(v, [0, 12, 0])), pos), n: R };
}

/**
 * Un corps articulé : os = { nom: { pivot, parent } }, parties = [{ os, a, b, couleur, pas, decale }]. o donne les
 * rotations en degrés : o[nom] = [rx, rz, ry]. Rend les repères de chaque os (pour poser des effets dessus).
 */
export function corps(sc, os, parties, o) {
  const racine = repereRacine(o);
  const T = {};
  const repere = (nom) => {
    if (T[nom]) return T[nom];
    const { pivot, parent } = os[nom];
    const [rx = 0, rz = 0, ry = 0] = (typeof o[nom] === "number" ? [o[nom]] : o[nom] ?? []).map((v) => v * DEG);
    const P = parent ? repere(parent) : racine;
    const loc = (v) => rotY(rotX(rotZ(v, rz), rx), ry);
    T[nom] = { p: (v) => P.p(ajoute(loc(sous(v, pivot)), pivot)), n: (v) => P.n(loc(v)) };
    return T[nom];
  };
  for (const pa of parties) pave(sc, pa.a, pa.b, repere(pa.os), pa.couleur, pa.pas ?? 1, pa.decale ?? 0);
  for (const nom of Object.keys(os)) repere(nom);
  return T;
}

// ------------------------------------------------------------------ les effets, à l'écran
export function melangePixel(sc, x, y, c, a) {
  if (x < 0 || y < 0 || x >= sc.w || y >= sc.h) return;
  const i = (Math.round(y) * sc.w + Math.round(x)) * 4;
  for (let k = 0; k < 3; k++) sc.img[i + k] = Math.max(0, Math.min(255, sc.img[i + k] * (1 - a) + c[k] * a));
}
/** Un trait entre deux points (en unités), épaisseur e en pixels d'image. */
export function trait(sc, A, B, c, e = 1, a = 1) {
  const [ax, ay] = sc.vue(A), [bx, by] = sc.vue(B), L = Math.hypot(bx - ax, by - ay);
  for (let s = 0; s <= L; s += 0.5) {
    const x = ax + ((bx - ax) * s) / (L || 1), y = ay + ((by - ay) * s) / (L || 1);
    for (let dy = -e; dy <= e; dy++) for (let dx = -e; dx <= e; dx++) if (Math.hypot(dx, dy) <= e + 0.2) melangePixel(sc, x + dx, y + dy, c, a);
  }
}
/** L'étoile d'impact de bande dessinée : jaune, bordée de blanc, avec un halo. */
export function impact(sc, P, r, c = [255, 214, 60]) {
  const [cx, cy] = sc.vue(P);
  for (let y = Math.floor(cy - r * 1.8); y <= cy + r * 1.8; y++) for (let x = Math.floor(cx - r * 1.8); x <= cx + r * 1.8; x++) {
    const dx = x - cx, dy = y - cy, d = Math.hypot(dx, dy), ang = Math.atan2(dy, dx);
    const ray = r * (0.5 + 0.5 * Math.abs(Math.cos(ang * 4.5 + 0.4))) * (0.85 + 0.15 * Math.sin(ang * 11));
    if (d < ray * 0.55) melangePixel(sc, x, y, [255, 255, 236], 1);
    else if (d < ray) melangePixel(sc, x, y, c, 1);
    else if (d < ray + 2) melangePixel(sc, x, y, [255, 255, 255], 1);
    else if (d < r * 1.8) melangePixel(sc, x, y, [255, 240, 180], 0.35 * (1 - d / (r * 1.8)));
  }
}
/** Une onde de choc à plat sur le sol (cercle en unités) : un anneau clair et des éclats. */
export function onde(sc, C, R, c = [255, 255, 255], a = 0.7) {
  for (let k = 0; k < 64; k++) {
    const t0 = (k / 64) * Math.PI * 2, t1 = ((k + 1) / 64) * Math.PI * 2;
    trait(sc, ajoute(C, [Math.cos(t0) * R, 0, Math.sin(t0) * R]), ajoute(C, [Math.cos(t1) * R, 0, Math.sin(t1) * R]), c, 1.5, a);
  }
}
/** Une poussière / fumée ronde (en unités). */
export function nuage(sc, P, r, c = [230, 226, 220], a = 0.5) {
  const [cx, cy] = sc.vue(P), R = r * sc.echelle;
  for (let y = Math.floor(cy - R); y <= cy + R; y++) for (let x = Math.floor(cx - R); x <= cx + R; x++) {
    const d = Math.hypot(x - cx, y - cy) / R;
    if (d > 1) continue;
    melangePixel(sc, x, y, c, a * (1 - d) * (0.65 + 0.35 * hache(x >> 1, y >> 1, 9)));
  }
}
/** Des traits de vitesse derrière quelqu'un qui fonce (de A vers B, en unités). */
export function vitesse(sc, A, B, n = 7, c = [255, 255, 255]) {
  for (let k = 0; k < n; k++) {
    const d = ajoute([0, (k - n / 2) * 4, 0], [0, 0, 0]), s = 0.2 + 0.6 * hache(k, 3, 5);
    trait(sc, ajoute(A, d), ajoute(melange(A, B, s), d), c, 1, 0.55);
  }
}
/** Une langue de feu (pointe à base carrée) qui brille : jaune à la base, rouge au bout. */
export function feu(sc, base, dir, L, l, jaune = [255, 226, 90], rouge = [236, 60, 20]) {
  const d = norme(dir);
  let e1 = croix(d, [0, 1, 0]);
  if (Math.hypot(...e1) < 0.2) e1 = croix(d, [1, 0, 0]);
  e1 = norme(e1);
  const e2 = croix(d, e1);
  const coin = (a, c) => base.map((x, i) => x + ((e1[i] * a + e2[i] * c) * l) / 2);
  const bas = [coin(-1, -1), coin(1, -1), coin(1, 1), coin(-1, 1)], bout = base.map((x, i) => x + d[i] * L);
  for (let k = 0; k < 4; k++) {
    const p0 = bas[k], p1 = bas[(k + 1) % 4];
    for (let j = 0; j < 4; j++) {
      const q = (p, t) => p.map((x, i) => x + (bout[i] - x) * t);
      sc.quad([q(p0, j / 4), q(p1, j / 4), q(p1, (j + 1) / 4), q(p0, (j + 1) / 4)], melange(jaune, rouge, j / 3.5), 0);
    }
  }
}
/** Un faisceau de projecteur : un cône (en tranches de voiles) de S vers le disque C de rayon R. */
export function faisceau(sc, S, C, R, c = [255, 246, 214], a = 0.12) {
  const n = 18;
  for (let k = 0; k < n; k++) {
    const t0 = (k / n) * Math.PI * 2, t1 = ((k + 1) / n) * Math.PI * 2;
    sc.voile([S, S, ajoute(C, [Math.cos(t1) * R, 0, Math.sin(t1) * R]), ajoute(C, [Math.cos(t0) * R, 0, Math.sin(t0) * R])].map((p, i) => (i === 1 ? ajoute(p, [0.01, 0, 0]) : p)), c, a / 2, true);
  }
}

// ------------------------------------------------------------------ le texte
const sansAccent = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "");
export function texte(img, W, s, x0, y0, t, couleur) {
  let x = x0;
  const H = img.length / 4 / W;
  for (const ch of sansAccent(s).toUpperCase()) {
    const mm = POLICE[ch] ?? POLICE[" "];
    for (let k = 0; k < 15; k++) if (mm[k] === "1") for (let dy = 0; dy < t; dy++) for (let dx = 0; dx < t; dx++) {
      const X = x + (k % 3) * t + dx, Y = y0 + Math.floor(k / 3) * t + dy;
      if (X >= 0 && Y >= 0 && X < W && Y < H) img.set([...couleur.slice(0, 3), 255], (Y * W + X) * 4);
    }
    x += 4 * t;
  }
  return x;
}
export const largeur = (s, t) => sansAccent(s).length * 4 * t - t;
export function rect(img, W, x0, y0, x1, y1, c, a = 1) {
  const H = img.length / 4 / W;
  for (let y = Math.max(0, Math.round(y0)); y < Math.min(H, Math.round(y1)); y++) for (let x = Math.max(0, Math.round(x0)); x < Math.min(W, Math.round(x1)); x++) {
    const o = (y * W + x) * 4;
    for (let q = 0; q < 3; q++) img[o + q] = img[o + q] * (1 - a) + c[q] * a;
    img[o + 3] = 255;
  }
}
/** Une étiquette : un cartouche sombre avec le texte et un trait vers ce qu'elle montre (point en unités). */
export function etiquette(sc, p, s, { t = 3, dy = -40, dx = 0, couleur = [255, 214, 90] } = {}) {
  const [x, y] = sc.vue(p), L = largeur(s, t);
  const bx = Math.round(x + dx - L / 2), by = Math.round(y + dy);
  const n = Math.max(Math.abs(dy), Math.abs(dx));
  for (let k = 0; k < n; k++) rect(sc.img, sc.w, x + (dx * k) / n - 1, y + (dy * k) / n, x + (dx * k) / n + 1, y + (dy * k) / n + 1, [16, 12, 20], 0.85);
  rect(sc.img, sc.w, bx - 7, by - 6, bx + L + 7, by + 5 * t + 6, [18, 14, 24], 0.9);
  rect(sc.img, sc.w, bx - 7, by - 6, bx + L + 7, by - 4, couleur, 1);
  texte(sc.img, sc.w, s, bx, by, t, couleur);
}
/** Un bloc de texte à la ligne (mots), de largeur maxi L pixels ; rend le y de fin. */
export function paragraphe(img, W, s, x0, y0, t, couleur, L, interligne = 1.9) {
  const mots = s.split(" ");
  let ligne = "", y = y0;
  for (const m of mots) {
    const essai = ligne ? ligne + " " + m : m;
    if (largeur(essai, t) > L && ligne) { texte(img, W, ligne, x0, y, t, couleur); y += Math.round(5 * t * interligne); ligne = m; }
    else ligne = essai;
  }
  if (ligne) { texte(img, W, ligne, x0, y, t, couleur); y += Math.round(5 * t * interligne); }
  return y;
}

/** Colle une image (rgba w×h) dans une plus grande. */
export function coller(dst, W, src, w, h, x0, y0) {
  for (let y = 0; y < h; y++) src.copy(dst, ((y0 + y) * W + x0) * 4, y * w * 4, (y + 1) * w * 4);
}
