// Génère le livre de quêtes FTB Quests dans pack/config/ftbquests/quests.
// Les textes sont modifiables ici, ou directement en jeu (mode édition du livre, en op).
//   node outils/generer-quetes.mjs
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { RELIQUES } from "../mod-cabane/outils/reliques.mjs";

const RACINE = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const SORTIE = path.join(RACINE, "pack", "config", "ftbquests", "quests");

// ---------------------------------------------------------------- SNBT façon FTB
class Typed { constructor(v, s) { this.v = v; this.s = s; } }
const D = (n) => new Typed(n, "d");
const L = (n) => new Typed(n, "L");
function snbt(v, ind = "") {
  const sous = ind + "\t";
  if (v instanceof Typed) return `${Number.isInteger(v.v) && v.s === "d" ? v.v.toFixed(1) : v.v}${v.s}`;
  if (Array.isArray(v)) return v.length ? `[\n${v.map((x) => sous + snbt(x, sous)).join("\n")}\n${ind}]` : "[ ]";
  if (v && typeof v === "object") {
    const cles = Object.keys(v).filter((k) => v[k] !== undefined).sort();
    return `{\n${cles.map((k) => `${sous}${k}: ${snbt(v[k], sous)}`).join("\n")}\n${ind}}`;
  }
  if (typeof v === "string") return JSON.stringify(v);
  return String(v);
}

// Identifiants stables (16 hexa, bit de signe à 0)
const id = (cle) => {
  const h = crypto.createHash("sha1").update("cabane:" + cle).digest("hex").slice(0, 16).toUpperCase();
  return (parseInt(h[0], 16) & 7).toString(16).toUpperCase() + h.slice(1);
};

const lang = {};
const objet = (nom, n = 1) => ({ count: n, id: nom });

// ---------------------------------------------------------------- tâches et récompenses
const coche = (k) => ({ id: id(k + "/t"), type: "checkmark" });
const avoir = (k, item, n = 1) => ({ id: id(k + "/t"), type: "item", item: objet(item), count: L(n) });
const visiter = (k, structure) => ({ id: id(k + "/t"), type: "structure", structure });
const tuer = (k, entite, n = 1) => ({ id: id(k + "/t"), type: "kill", entity: entite, value: L(n) });
const succes = (k, adv) => ({ id: id(k + "/t"), type: "advancement", advancement: adv, criterion: "" });

const xp = (k, n) => ({ id: id(k + "/xp"), type: "xp_levels", xp_levels: n });
const donne = (k, item, n = 1) => ({ id: id(k + "/r/" + item), type: "item", item: objet(item), count: n });
const commande = (k, cmd) => ({ id: id(k + "/cmd"), type: "command", command: cmd, elevate_perms: true, permission_level: 2, silent: true });

function quete(k, { titre, sous, desc, x, y, taches, recompenses = [], deps = [], forme, taille, argent }) {
  const qid = id(k);
  if (argent) recompenses = [...recompenses, { id: id(k + "/argent"), type: "command", command: `/argent donner {p} ${argent}`, elevate_perms: true, permission_level: 2, silent: true }];
  lang[`quest.${qid}.title`] = titre;
  if (sous) lang[`quest.${qid}.quest_subtitle`] = sous;
  if (desc) lang[`quest.${qid}.quest_desc`] = desc;
  return { id: qid, x: D(x), y: D(y), tasks: taches, rewards: recompenses, dependencies: deps.map(id), shape: forme, size: taille ? D(taille) : undefined };
}

function chapitre(fichier, { titre, icone, ordre, quetes }) {
  const cid = id("chapitre/" + fichier);
  lang[`chapter.${cid}.title`] = titre;
  return [fichier, {
    default_hide_dependency_lines: false,
    default_quest_shape: "",
    filename: fichier,
    group: "",
    icon: objet(icone),
    id: cid,
    order_index: ordre,
    quest_links: [],
    quests: quetes,
  }];
}

// ---------------------------------------------------------------- chapitre 1 : la Cabane
const cabane = chapitre("la_cabane", { titre: "&6La Cabane", icone: "cabane:telephone", ordre: 0, quetes: [
  quete("bienvenue", { argent: 50, titre: "Bienvenue à la Cabane", sous: "Le début de tout", x: 0, y: 0, forme: "gear", taille: 1.5,
    desc: ["Votre avion s'est écrasé, mais vous avez sauté à temps. Vous voilà dans la Grande Cabane, au centre de la map.", "",
      "C'est ici que vous construirez votre base. Allez parler au &6Vieux Gardien&r près du porche, lisez son journal sur le pupitre, puis cochez cette quête."],
    taches: [coche("bienvenue")], recompenses: [xp("bienvenue", 2), donne("bienvenue", "minecraft:cooked_beef", 8)] }),
  quete("telephone", { argent: 50, titre: "Allô ?", sous: "Ton numéro perso", x: 2, y: -1.5, deps: ["bienvenue"],
    desc: ["Appuie sur &eN&r (ou clic droit avec le téléphone) et choisis ton numéro à 3 ou 4 chiffres.", "",
      "Donne-le à tes potes… ou garde-le secret ! Puis appelle quelqu'un : la voix passe par Simple Voice Chat, où que vous soyez sur la map.", "",
      "Pas de téléphone ? Il se fabrique avec du fer, du cuivre, de la redstone et une vitre."],
    taches: [succes("telephone", "cabane:premier_appel")], recompenses: [donne("telephone", "minecraft:emerald", 5)] }),
  quete("waystone", { argent: 75, titre: "Pierres de voyage", x: 4, y: -1.5, deps: ["bienvenue"],
    desc: ["La map est immense. Fabrique une &bWaystone&r et pose-la près de la Cabane : vous pourrez y revenir de très loin."],
    taches: [avoir("waystone", "waystones:waystone")], recompenses: [xp("waystone", 3), donne("waystone", "minecraft:ender_pearl", 4)] }),
  quete("sac", { argent: 50, titre: "Bien équipé", x: 2, y: 1.5, deps: ["bienvenue"],
    desc: ["Un sac à dos, c'est la base pour partir en expédition."],
    taches: [avoir("sac", "sophisticatedbackpacks:backpack")], recompenses: [xp("sac", 3), donne("sac", "minecraft:leather", 8)] }),
  quete("cuisine", { argent: 50, titre: "Aux fourneaux", x: 4, y: 1.5, deps: ["sac"],
    desc: ["Fabrique une marmite (Farmer's Delight). Les bons plats nourrissent beaucoup plus longtemps."],
    taches: [avoir("cuisine", "farmersdelight:cooking_pot")], recompenses: [xp("cuisine", 3), donne("cuisine", "minecraft:pumpkin_pie", 6)] }),
  quete("create", { argent: 100, titre: "Les rouages", x: 6, y: -1.5, deps: ["waystone"],
    desc: ["Create permet de construire des machines, des moulins, des trains…", "Fabrique 16 alliages d'andésite pour commencer."],
    taches: [avoir("create", "create:andesite_alloy", 16)], recompenses: [xp("create", 5), donne("create", "create:cogwheel", 8)] }),
] });

// ---------------------------------------------------------------- chapitre : le village qui nous héberge
const village = chapitre("le_village", { titre: "&aLe Village", icone: "minecraft:bell", ordre: 1, quetes: [
  quete("village/accueil", { titre: "Le village qui nous héberge", x: 0, y: 0, deps: ["bienvenue"], forme: "gear", taille: 1.3, argent: 50,
    desc: ["Votre avion s'est écrasé, mais le village voisin vous accueille dans la Grande Cabane.", "", "Allez saluer les villageois, puis cochez cette quête."],
    taches: [coche("village/accueil")] }),
  quete("village/contrat", { titre: "Premier contrat", x: 2, y: -1.5, deps: ["village/accueil"], argent: 100,
    desc: ["Bako, au marché, propose trois contrats qui changent toutes les heures. Remplis-en un !"],
    taches: [succes("village/contrat", "cabane:premier_contrat")] }),
  quete("village/pain", { titre: "Nourrir le village", x: 2, y: 0, deps: ["village/accueil"], argent: 200,
    desc: ["Les villageois ont faim : apporte 32 pains."], taches: [avoir("village/pain", "minecraft:bread", 32)] }),
  quete("village/bois", { titre: "Du bois pour l'hiver", x: 2, y: 1.5, deps: ["village/accueil"], argent: 200,
    desc: ["64 bûches d'acacia pour réparer les maisons."], taches: [avoir("village/bois", "minecraft:acacia_log", 64)] }),
  quete("village/fer", { titre: "Le forgeron a besoin de fer", x: 4, y: 1.5, deps: ["village/bois"], argent: 300,
    desc: ["24 lingots de fer pour la forge du village."], taches: [avoir("village/fer", "minecraft:iron_ingot", 24)] }),
  quete("village/etal", { titre: "Une place au marché", x: 4, y: -1.5, deps: ["village/contrat"], argent: 100,
    desc: ["Achète un étal à la boutique et pose-le au marché : les autres pourront acheter ce que tu vends."],
    taches: [avoir("village/etal", "cabane:etal")] }),
  quete("village/cartes", { titre: "Collectionneur", x: 4, y: 0, deps: ["village/pain"],
    desc: ["Procure-toi un classeur : il range toutes tes cartes à collectionner."],
    taches: [avoir("village/cartes", "cabane:classeur")], recompenses: [donne("village/cartes", "cabane:booster", 2)] }),
] });

// ---------------------------------------------------------------- chapitre 2 : les Sept Reliques
const reliques = [];
RELIQUES.forEach((r, i) => {
  const y = i * 1.5 - 4.5;
  reliques.push(
    quete(`indice/${r.id}`, { titre: `Indice : ${r.lieu}`, x: 0, y, deps: ["bienvenue"],
      desc: [r.desc, "", "Coche cette quête pour recevoir une &6carte au trésor&r. Elle mène à un donjon à environ " + r.distance + " blocs de la cabane. Prévoyez le voyage !"],
      taches: [coche(`indice/${r.id}`)], recompenses: [commande(`indice/${r.id}`, `/cabane carte {p} ${r.cle} ${r.distance}`)] }),
    quete(`lieu/${r.id}`, { titre: `Trouver : ${r.lieu}`, x: 2, y, deps: [`indice/${r.id}`],
      desc: ["Entre dans le donjon. Méfie-toi, ses gardiens ne t'attendent pas les bras ouverts."],
      taches: [visiter(`lieu/${r.id}`, `#cabane:quete/${r.cle}`)], recompenses: [xp(`lieu/${r.id}`, 5)] }),
    quete(`relique/${r.id}`, { argent: 500, titre: `&6${r.nom}`, x: 4, y, deps: [`lieu/${r.id}`], forme: "diamond",
      desc: [r.desc, "", "Ramène-la à la Cabane et pose-la sur un cadre du &6mur des reliques&r."],
      taches: [avoir(`relique/${r.id}`, `cabane:${r.id}`)],
      recompenses: [xp(`relique/${r.id}`, 10), donne(`relique/${r.id}`, "minecraft:diamond", 3), donne(`relique/${r.id}`, "minecraft:emerald", 10)] }),
  );
});
reliques.push(quete("reveil", { argent: 5000, titre: "&6&lLe Réveil de la Cabane", sous: "Les sept reliques sont réunies", x: 6.5, y: 0, forme: "gear", taille: 2,
  deps: RELIQUES.map((r) => `relique/${r.id}`),
  desc: ["Les sept reliques sont de retour sur le comptoir.", "", "La Confrérie des Explorateurs renaît : vous êtes les nouveaux Gardiens de la Cabane."],
  taches: [coche("reveil")],
  recompenses: [donne("reveil", "minecraft:elytra"), donne("reveil", "minecraft:diamond", 16),
    commande("reveil", `/title {p} title {"text":"Gardien de la Cabane","color":"gold"}`)] }));
const chapReliques = chapitre("reliques", { titre: "&eLes Sept Reliques", icone: "cabane:scarabee_dor", ordre: 2, quetes: reliques });

// ---------------------------------------------------------------- chapitre 3 : les grands donjons
const DONJONS = [
  ["dungeons_arise:keep_kayra", "Le Donjon de Kayra"], ["dungeons_arise:thornborn_towers", "Les Tours Épineuses"],
  ["dungeons_arise:typhon", "Le Typhon"], ["dungeons_arise:plague_asylum", "L'Asile de la Peste"],
  ["dungeons_arise:shiraz_palace", "Le Palais de Shiraz"], ["idas:labyrinth", "Le Labyrinthe"],
  ["idas:haunted_manor", "Le Manoir Hanté"], ["idas:necromancers_spire", "La Flèche du Nécromancien"],
  ["cataclysm:sunken_city", "La Cité Engloutie"], ["cataclysm:acropolis", "L'Acropole"],
  ["cataclysm:ancient_factory", "L'Usine Ancienne"], ["cataclysm:frosted_prison", "La Prison Gelée"],
];
const BOSS = [
  ["mowziesmobs:umvuthi", "Umvuthi, roi de la savane", 3], ["mowziesmobs:ferrous_wroughtnaut", "Le Wroughtnaut", 3],
  ["mowziesmobs:frostmaw", "Frostmaw", 3], ["mowziesmobs:sculptor", "Le Sculpteur", 4],
  ["cataclysm:ancient_remnant", "Le Vestige Ancien", 5], ["cataclysm:the_leviathan", "Le Léviathan", 6],
  ["cataclysm:netherite_monstrosity", "La Monstruosité de Netherite", 6], ["cataclysm:ignis", "Ignis", 7],
  ["cataclysm:maledictus", "Maledictus", 7], ["cataclysm:scylla", "Scylla", 7],
  ["cataclysm:ender_guardian", "Le Gardien de l'End", 8], ["cataclysm:the_harbinger", "Le Héraut", 8],
];
const donjons = [
  ...DONJONS.map(([s, nom], i) => quete(`donjon/${s}`, { argent: 150, titre: nom, x: (i % 6) * 1.5, y: Math.floor(i / 6) * 1.5 - 3, deps: ["bienvenue"],
    desc: ["Un des grands donjons du monde. Trouve-le, entre, et ressors vivant."],
    taches: [visiter(`donjon/${s}`, s)], recompenses: [xp(`donjon/${s}`, 5), donne(`donjon/${s}`, "minecraft:emerald", 6)] })),
  ...BOSS.map(([e, nom, diamants], i) => quete(`boss/${e}`, { argent: 200 + diamants * 100, titre: `&c${nom}`, x: (i % 6) * 1.5, y: Math.floor(i / 6) * 1.5 + 1, deps: ["bienvenue"], forme: "hexagon",
    desc: ["Un boss redoutable. Venez à plusieurs, avec vos meilleures armes."],
    taches: [tuer(`boss/${e}`, e)], recompenses: [xp(`boss/${e}`, 10 + diamants), donne(`boss/${e}`, "minecraft:diamond", diamants)] })),
];
const chapDonjons = chapitre("donjons", { titre: "&cLes Grands Donjons", icone: "minecraft:netherite_sword", ordre: 3, quetes: donjons });

// ---------------------------------------------------------------- écriture
fs.rmSync(SORTIE, { recursive: true, force: true });
const ecrire = (rel, txt) => { const f = path.join(SORTIE, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, txt + "\n"); };
ecrire("data.snbt", snbt({
  default_autoclaim_rewards: "disabled", default_consume_items: false, default_quest_disable_jei: false, default_quest_shape: "circle",
  default_reward_team: false, disable_gui: false, drop_book_on_death: false, emergency_items_cooldown: 300, grid_scale: D(0.5),
  lock_message: "", pause_game: false, progression_mode: "linear", version: 13,
}));
ecrire("chapter_groups.snbt", snbt({ chapter_groups: [] }));
for (const [fichier, chap] of [cabane, village, chapReliques, chapDonjons]) ecrire(`chapters/${fichier}.snbt`, snbt(chap));
const langTxt = snbt(lang);
ecrire("lang/fr_fr.snbt", langTxt);
ecrire("lang/en_us.snbt", langTxt);
const n = [cabane, village, chapReliques, chapDonjons].reduce((s, [, c]) => s + c.quests.length, 0);
console.log(`${n} quêtes écrites dans ${SORTIE}`);
