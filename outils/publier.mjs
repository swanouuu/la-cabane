// Publie tes changements de mods pour tout le monde.
// 1. Compare les mods de TON instance Prism avec le pack (ajouts, retraits, changements de version).
// 2. Met à jour le pack (packwiz), puis l'envoie sur GitHub (commit + push).
// Au prochain lancement, Prism met à jour les mods de tes potes, et le serveur aussi.
//   node outils/publier.mjs            (publie)
//   node outils/publier.mjs --essai    (montre seulement ce qui changerait)
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { PACK, config, lancer, packwiz } from "./commun.mjs";

const ESSAI = process.argv.includes("--essai");
const c = config();
const UA = { "User-Agent": "la-cabane-publier/1.0", "Content-Type": "application/json" };

// ---------------------------------------------------------------- les mods de ton instance
const dossierMods = ["minecraft", ".minecraft"].map((d) => path.join(c.instancePrism, d, "mods")).find((d) => fs.existsSync(d));
if (!dossierMods) {
  console.error(`Instance Prism introuvable : ${c.instancePrism}\nCorrige "instancePrism" dans outils/config.json.`);
  process.exit(1);
}
const jars = fs.readdirSync(dossierMods).filter((f) => f.endsWith(".jar"));

// ---------------------------------------------------------------- les mods du pack
const pack = fs.readdirSync(path.join(PACK, "mods")).map((f) => {
  const fichier = path.join(PACK, "mods", f);
  if (!f.endsWith(".pw.toml")) return { nom: f, filename: f, brut: true };
  const t = fs.readFileSync(fichier, "utf8");
  const champ = (re) => t.match(re)?.[1];
  return {
    nom: f.replace(/\.pw\.toml$/, ""),
    filename: champ(/^filename = "([^"]+)"/m),
    side: champ(/^side = "([^"]+)"/m) ?? "both",
    modrinth: champ(/\[update\.modrinth\][^[]*?mod-id = "([^"]+)"/s),
  };
});
const parFichier = new Map(pack.map((m) => [m.filename, m]));
const cotesClient = pack.filter((m) => !m.brut && m.side !== "server");

const presents = jars.filter((j) => parFichier.has(j)).length;
if (presents < cotesClient.length * 0.5) {
  console.error(`Seulement ${presents} mods du pack trouvés dans ${dossierMods} sur ${cotesClient.length}.`);
  console.error("Lance d'abord l'instance une fois pour qu'elle se synchronise, sinon tous les mods seraient retirés.");
  process.exit(1);
}

// ---------------------------------------------------------------- nouveaux jars : on les cherche sur Modrinth
const nouveaux = jars.filter((j) => !parFichier.has(j));
const hashes = Object.fromEntries(nouveaux.map((j) => [crypto.createHash("sha1").update(fs.readFileSync(path.join(dossierMods, j))).digest("hex"), j]));
let versions = {};
if (nouveaux.length) {
  const r = await fetch("https://api.modrinth.com/v2/version_files", { method: "POST", headers: UA, body: JSON.stringify({ hashes: Object.keys(hashes), algorithm: "sha1" }) });
  versions = await r.json();
}

const actions = [];
const inconnus = [];
for (const [hash, jar] of Object.entries(hashes)) {
  const v = versions[hash];
  if (!v) {
    inconnus.push(jar);
    continue;
  }
  const ancien = pack.find((m) => m.modrinth === v.project_id);
  if (ancien) actions.push({ texte: `↻ ${ancien.filename} → ${jar}`, retirer: ancien.nom, projet: v.project_id, version: v.id });
  else actions.push({ texte: `+ ${jar}`, projet: v.project_id, version: v.id });
}
const remplaces = new Set(actions.map((a) => a.retirer).filter(Boolean));
for (const m of cotesClient) {
  if (!jars.includes(m.filename) && !remplaces.has(m.nom)) actions.push({ texte: `- ${m.filename}`, retirer: m.nom });
}

if (!actions.length && !inconnus.length) console.log("Aucun changement de mods.");
actions.forEach((a) => console.log(a.texte));
inconnus.forEach((j) => console.log(`? ${j} : introuvable sur Modrinth, ajoute-le à la main (packwiz curseforge add …)`));
if (ESSAI) process.exit(0);

// ---------------------------------------------------------------- on applique
for (const a of actions) {
  if (a.retirer) {
    const r = packwiz("remove", a.retirer);
    if (!r.ok) console.error(r.sortie);
  }
  if (a.projet) {
    const r = packwiz("modrinth", "add", "--project-id", a.projet, "--version-id", a.version, "-y");
    if (!r.ok) console.error(r.sortie);
  }
}
const refresh = packwiz("refresh");
if (!refresh.ok) {
  console.error(refresh.sortie);
  process.exit(1);
}

// ---------------------------------------------------------------- envoi sur GitHub
lancer("git", ["add", "-A", "pack"]);
const commit = lancer("git", ["commit", "-m", `Mise à jour du pack (${new Date().toLocaleString("fr-FR")})`]);
if (!commit.ok) {
  console.log("Rien de nouveau à publier.");
  process.exit(0);
}
if (!lancer("git", ["remote"]).sortie) {
  console.log("Changements enregistrés, mais aucun dépôt GitHub n'est configuré : rien n'a été envoyé.");
  process.exit(0);
}
const push = lancer("git", ["push"]);
console.log(push.ok ? "Publié ! Tes potes auront les changements au prochain lancement." : `Échec de l'envoi :\n${push.sortie}`);
