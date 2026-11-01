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
/** Livrer : les objets sont pris (ils partent au village). Plusieurs livraisons dans une quête : suffixe différent. */
const livrer = (k, item, n = 1, suffixe = "") => ({ id: id(k + "/t" + suffixe), type: "item", item: objet(item), count: L(n), consume_items: "true" });
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
const cabane = chapitre("la_cabane", { titre: "&6Beauvais", icone: "cabane:telephone", ordre: 0, quetes: [
  quete("bienvenue", { argent: 50, titre: "Bienvenue à Beauvais", sous: "Le début de tout", x: 0, y: 0, forme: "gear", taille: 1.5,
    desc: ["Votre avion a pris feu au-dessus de la jungle, mais vous avez sauté à temps. Vous voilà à Beauvais.", "",
      "Allez écouter &6Marcel le Conteur&r, au feu de camp : il vous racontera l'histoire du village (le chapitre &6L'Histoire de Beauvais&r commence là). Le &6Hejj du Village&r, devant la Grande Cabane, vous expliquera le reste. Puis cochez cette quête."],
    taches: [coche("bienvenue")], recompenses: [xp("bienvenue", 2), donne("bienvenue", "minecraft:cooked_beef", 8)] }),
  quete("telephone", { argent: 50, titre: "Allô ?", sous: "Ton numéro perso", x: 2, y: -1.5, deps: ["bienvenue"],
    desc: ["Appuie sur &eN&r (ou clic droit avec le téléphone) et choisis ton numéro à 3 ou 4 chiffres.", "",
      "Le numéro est gravé dans le téléphone : il ne changera plus. Donne-le à tes potes… ou garde-le secret ! Puis appelle quelqu'un : la voix passe par Simple Voice Chat, où que vous soyez sur la map.", "",
      "Perdu ton téléphone ? Mouloud en vend au Grand Bazar."],
    taches: [succes("telephone", "cabane:premier_appel")], recompenses: [donne("telephone", "minecraft:emerald", 5)] }),
  quete("waystone", { argent: 75, titre: "Pierres de voyage", x: 4, y: -1.5, deps: ["bienvenue"],
    desc: ["La map est immense. Fabrique une &bWaystone&r et pose-la près du village : vous pourrez y revenir de très loin."],
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

// ---------------------------------------------------------------- chapitre : l'histoire de Beauvais (la grande aventure)
const acte = (k, o) => quete("histoire/" + k, { ...o, deps: (o.deps ?? []).map((d) => (d.includes("/") || d === "bienvenue" ? d : "histoire/" + d)) });
const histoire = chapitre("histoire", { titre: "&6&lL'Histoire de Beauvais", icone: "minecraft:writable_book", ordre: 1, quetes: [
  // Acte I : l'arrivée
  acte("conteur", { titre: "&6Marcel le Conteur", sous: "Acte I — Les gens du ciel", x: 0, y: 0, deps: ["bienvenue"], forme: "gear", taille: 1.5, argent: 100,
    desc: ["Au feu de camp, un vieil homme fume la pipe et regarde le ciel. C'est &6Marcel le Conteur&r : il connaît l'histoire de Beauvais mieux que personne.", "",
      "Écoutez-le jusqu'au bout (jusqu'à la prophétie), puis cochez cette quête."],
    taches: [coche("histoire/conteur")], recompenses: [xp("histoire/conteur", 3)] }),
  acte("mefiance", { titre: "Ceux qui se méfient", x: 2, y: -1, deps: ["conteur"], argent: 150,
    desc: ["Les villageois regardent les gens du ciel de travers. Parlez à trois d'entre eux : chacun a une histoire de raid ou de disparition à raconter.", "",
      "Jean, Monique, Thierry, Ginette… choisissez. Puis cochez."],
    taches: [coche("histoire/mefiance")] }),
  acte("president", { titre: "Monsieur le Président", x: 2, y: 1, deps: ["conteur"], argent: 150,
    desc: ["Le Président se tient sur la place, entouré de sa garde masquée. Allez le saluer. Souriez : il sourit beaucoup, lui aussi.", "",
      "Retenez bien ce qu'il dit. Et ce qu'il ne dit pas. Puis cochez."],
    taches: [coche("histoire/president")] }),
  // Acte II : les Pillards du Sud
  acte("pillards", { titre: "Les Pillards du Sud", sous: "Acte II — Ceux qui viennent des marais", x: 4, y: -1, deps: ["mefiance"], argent: 400,
    desc: ["Les Pillards rôdent dans la jungle et brûlent les récoltes. Montrez-leur que Beauvais a de nouveaux protecteurs."],
    taches: [tuer("histoire/pillards", "minecraft:pillager", 10)], recompenses: [xp("histoire/pillards", 4)] }),
  acte("brutes", { titre: "Les brutes à la hache", x: 6, y: -1, deps: ["pillards"], argent: 500,
    desc: ["Les Vindicateurs sont les gros bras des Pillards. Ils frappent fort. Frappez plus fort."],
    taches: [tuer("histoire/brutes", "minecraft:vindicator", 5)], recompenses: [xp("histoire/brutes", 5)] }),
  acte("avantposte", { titre: "L'avant-poste", x: 6, y: 1, deps: ["pillards", "president"], argent: 600,
    desc: ["Trouvez un avant-poste des Pillards. Dans leurs coffres, de l'or… frappé du sceau de la Mairie ?", "",
      "Comment des pillards peuvent-ils être payés par le Président ? Il faudra fouiller la Mairie."],
    taches: [visiter("histoire/avantposte", "minecraft:pillager_outpost")], recompenses: [xp("histoire/avantposte", 5)] }),
  acte("raid", { titre: "&cDéfendre Beauvais", sous: "Quand ils attaqueront…", x: 8, y: -1, deps: ["brutes"], forme: "hexagon", argent: 2000,
    desc: ["Un jour, les Pillards du Sud marcheront sur le village (c'est l'hôte qui décide quand !). Ce jour-là, soyez prêts.", "",
      "Repoussez-les tous, protégez les habitants, puis cochez cette quête."],
    taches: [coche("histoire/raid")], recompenses: [xp("histoire/raid", 8), donne("histoire/raid", "minecraft:totem_of_undying")] }),
  // Acte III : le secret de la Mairie
  acte("mairie", { titre: "La Mairie fermée la nuit", sous: "Acte III — Ce qu'on entend sous la terre", x: 8, y: 1, deps: ["avantposte"], argent: 1000,
    desc: ["La nuit, on entend des machines sous la Mairie. Dans le bureau du Président, une des bibliothèques sonne creux…", "",
      "Trouvez le passage secret, et descendez."],
    taches: [succes("histoire/mairie", "cabane:histoire/laboratoire")], recompenses: [xp("histoire/mairie", 6)] }),
  acte("pierre", { titre: "Le Sujet 07", x: 10, y: 0, deps: ["mairie"], argent: 500,
    desc: ["Au fond du laboratoire, des cellules. Dans l'une d'elles, un homme qui parle encore : Pierre, « parti travailler à la capitale ».", "",
      "Écoutez ce qu'il sait, puis cochez."],
    taches: [coche("histoire/pierre")] }),
  acte("dossier", { titre: "Le dossier « Projet Augmenté »", x: 10, y: 2, deps: ["mairie"], argent: 1500,
    desc: ["Le coffre du laboratoire contient un dossier, le carnet du Président et le rapport du Sujet 07. Tout y est : la potion verte, les Pillards payés, la Salle Zéro.", "",
      "Gardez le dossier : c'est la preuve. Et lisez le carnet…"],
    taches: [avoir("histoire/dossier", "cabane:dossier_secret")], recompenses: [xp("histoire/dossier", 6)] }),
  // Acte IV : les Sept Reliques
  acte("reliques", { titre: "&eLes Sept Reliques", sous: "Acte IV — Aux quatre coins du monde", x: 12, y: 1, deps: ["pierre", "dossier"], forme: "diamond", argent: 3000,
    desc: ["Seules les Sept Reliques peuvent ouvrir la Salle Zéro. Le Président les a fait cacher très loin, dans les pires donjons du monde.", "",
      "Suivez le chapitre &eLes Sept Reliques&r : réunissez-les toutes. Brokk en forgera une clé."],
    taches: [succes("histoire/reliques", "cabane:sept_reliques")],
    recompenses: [donne("histoire/reliques", "cabane:cle_salle_zero"), commande("histoire/reliques", '/tellraw {p} {"text":"Brokk : « Sept reliques, une clé. Allez donc rendre visite à votre Président. »","color":"gold"}')] }),
  // Acte V : la Forteresse du Sud
  acte("forteresse", { titre: "&4La Forteresse du Sud", sous: "Acte V — Le repaire", x: 14, y: 1, deps: ["reliques"], argent: 1000,
    desc: ["À 10 000 blocs plein sud se dresse la Forteresse du Sud, le repaire des Pillards. Au cœur du donjon : la Salle Zéro.", "",
      "Tapez &e/cabane forteresse&r pour ses coordonnées. Pour l'instant elle est scellée : c'est pour plus tard ! L'hôte l'ouvrira quand le moment sera venu.", "",
      "Allez la voir de vos yeux, puis cochez."],
    taches: [coche("histoire/forteresse")] }),
  acte("liberation", { titre: "&6&lLibérer Beauvais", sous: "Le Président Augmenté", x: 16, y: 1, deps: ["forteresse"], forme: "gear", taille: 2, argent: 25000,
    desc: ["Dans la Salle Zéro, utilisez la Clé sur le pupitre. Le Président se réveillera, fusionné avec sa machine.", "",
      "Il est très, très fort. Venez tous ensemble, avec vos meilleures armes, des potions, des totems. Et ne lâchez rien."],
    taches: [succes("histoire/liberation", "cabane:histoire/liberation")],
    recompenses: [xp("histoire/liberation", 30), donne("histoire/liberation", "minecraft:netherite_ingot", 2), donne("histoire/liberation", "minecraft:diamond_block", 2),
      commande("histoire/liberation", '/title {p} title {"text":"Libérateur de Beauvais","color":"gold"}')] }),
  acte("epilogue", { titre: "Le Conseil des Anciens", sous: "Épilogue", x: 18, y: 1, deps: ["liberation"], argent: 2000,
    desc: ["Beauvais est libre. Pierre a retrouvé le feu de camp, et le Conseil des Anciens se réunit à nouveau.", "",
      "Allez écouter Pierre, puis rendez une petite visite à l'ancien président, dans sa cellule sous la Mairie. Il a sûrement une consultation citoyenne à vous proposer."],
    taches: [coche("histoire/epilogue")], recompenses: [donne("histoire/epilogue", "cabane:biere_86", 6)] }),
] });

// ---------------------------------------------------------------- chapitre : le village qui nous héberge
const village = chapitre("le_village", { titre: "&aLe Village", icone: "minecraft:bell", ordre: 2, quetes: [
  quete("village/accueil", { titre: "Le village qui nous héberge", x: 0, y: 0, deps: ["bienvenue"], forme: "gear", taille: 1.3, argent: 50,
    desc: ["Beauvais vous accueille. Les habitants se méfient un peu des gens du ciel : à vous de faire vos preuves.", "", "Allez saluer les villageois, puis cochez cette quête."],
    taches: [coche("village/accueil")] }),
  quete("village/contrat", { titre: "Premier contrat", x: 2, y: -1.5, deps: ["village/accueil"], argent: 100,
    desc: ["Mehdi, de Pôle emploi, affiche au Grand Bazar des contrats qui changent toutes les heures. Remplis-en un !"],
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

// ---------------------------------------------------------------- chapitre : agrandir Beauvais (les chantiers du Hejj)
const chantier = (k, o) => quete("grandir/" + k, { ...o, deps: (o.deps ?? ["plan"]).map((d) => "grandir/" + d) });
const grandir = chapitre("agrandir_beauvais", { titre: "&2Agrandir Beauvais", icone: "minecraft:bricks", ordre: 3, quetes: [
  chantier("plan", { titre: "&6Le grand plan du Hejj", sous: "Faire grandir le village", x: 0, y: 0, forme: "gear", taille: 1.6, deps: [], argent: 200,
    desc: ["« Le cœur du village appartient aux habitants. Vous, installez-vous juste à côté, hors de l'enceinte. Et aidez Beauvais à pousser jusqu'à vous. »", "",
      "Chaque chantier de ce chapitre agrandit le village : livrez les matériaux (ils sont pris), construisez, et le village vous paie.", "",
      "Construisez vos maisons à l'extérieur de l'enceinte, et faites grandir Beauvais vers elles !"],
    taches: [coche("grandir/plan")] }),
  chantier("planches", { titre: "Des planches pour le quartier neuf", x: 2, y: -3, argent: 1500,
    desc: ["Les charpentiers du village ont besoin de bois. Beaucoup de bois."],
    taches: [livrer("grandir/planches", "minecraft:jungle_planks", 256)] }),
  chantier("carriere", { titre: "La carrière", x: 2, y: -1.5, argent: 1500,
    desc: ["Des pierres taillées pour les fondations, les murs et les routes."],
    taches: [livrer("grandir/carriere", "minecraft:stone_bricks", 256)] }),
  chantier("quartier", { titre: "Le quartier neuf", sous: "Trois maisons hors de l'enceinte", x: 4, y: -2.25, deps: ["planches", "carriere"], argent: 3000,
    desc: ["Construisez trois vraies maisons juste à côté du village, hors de l'enceinte : c'est le début du quartier neuf.", "", "Montrez-les au Hejj, puis cochez cette quête."],
    taches: [coche("grandir/quartier")] }),
  chantier("lampadaires", { titre: "Que la lumière soit", x: 2, y: 0, argent: 1500,
    desc: ["Ginette a peur du noir. Le Président aussi, mais il ne le dira pas.", "Livrez des lanternes pour éclairer les nouvelles rues."],
    taches: [livrer("grandir/lampadaires", "minecraft:lantern", 64)] }),
  chantier("ferme", { titre: "Les champs du village", x: 2, y: 1.5, argent: 2000,
    desc: ["Plus d'habitants, plus de bouches à nourrir. Livrez des récoltes pour les nouveaux greniers."],
    taches: [livrer("grandir/ferme", "minecraft:wheat", 256, "/a"), livrer("grandir/ferme", "minecraft:carrot", 64, "/b")] }),
  chantier("marche", { titre: "Le marché couvert", x: 2, y: 3, argent: 2500,
    desc: ["Mouloud rêve d'un marché couvert, avec des toiles de toutes les couleurs. Mouloud rêve beaucoup."],
    taches: [livrer("grandir/marche", "minecraft:white_wool", 64, "/a"), livrer("grandir/marche", "minecraft:red_wool", 32, "/b"), livrer("grandir/marche", "minecraft:spruce_planks", 128, "/c")] }),
  chantier("pont", { titre: "Le pont de la rivière", x: 6, y: -3, deps: ["quartier"], argent: 4000,
    desc: ["Le Président veut un pont « jusqu'à la lune ». Le Hejj propose de commencer par la rivière.", "",
      "Livrez les pierres, construisez un vrai pont sur la rivière la plus proche, puis cochez cette quête."],
    taches: [livrer("grandir/pont", "minecraft:stone_bricks", 192, "/a"), livrer("grandir/pont", "minecraft:oak_log", 64, "/b"), coche("grandir/pont")] }),
  chantier("port", { titre: "Le port", x: 8, y: -3, deps: ["pont"], argent: 5000,
    desc: ["Un vrai port, avec un ponton et des bateaux. Thierry dit que c'est « une preuve ». Thierry a raison.", "",
      "Construisez le port, puis cochez cette quête."],
    taches: [livrer("grandir/port", "minecraft:oak_boat", 6, "/a"), livrer("grandir/port", "minecraft:oak_planks", 256, "/b"), coche("grandir/port")] }),
  chantier("fontaine", { titre: "Une fontaine qui marche", x: 4, y: 0, deps: ["lampadaires"], argent: 1500,
    desc: ["Les fontaines de la place n'ont jamais marché. Thierry s'en plaint depuis dix ans.", "Construisez une belle fontaine avec de l'eau qui coule dans le quartier neuf."],
    taches: [coche("grandir/fontaine")] }),
  chantier("ecole", { titre: "L'école de Ginette", x: 4, y: 1.5, deps: ["ferme"], argent: 2500,
    desc: ["Ginette veut ouvrir une école « pour que les jeunes arrêtent de faire des bêtises ». Il faut des livres."],
    taches: [livrer("grandir/ecole", "minecraft:book", 48, "/a"), livrer("grandir/ecole", "minecraft:bookshelf", 16, "/b")] }),
  chantier("rondpoint", { titre: "Le rond-point du Président", sous: "C'est notre projet !", x: 6, y: 0, deps: ["fontaine"], argent: 3000,
    desc: ["« Une ville moderne, c'est un rond-point. » — Macron, Président de Beauvais.", "",
      "Construisez un rond-point fleuri à l'entrée du quartier neuf, puis cochez cette quête."],
    taches: [livrer("grandir/rondpoint", "minecraft:poppy", 32, "/a"), livrer("grandir/rondpoint", "minecraft:polished_andesite", 64, "/b"), coche("grandir/rondpoint")] }),
  chantier("gare", { titre: "La gare de Beauvais", x: 8, y: 0, deps: ["rondpoint", "port"], argent: 6000,
    desc: ["Avec Create, construisez une gare et une ligne de train qui part du village.", "Livrez des rails pour la première voie, puis cochez la quête quand le premier train roule."],
    taches: [livrer("grandir/gare", "create:track", 128, "/a"), coche("grandir/gare")] }),
  chantier("statue", { titre: "La statue du Président", x: 6, y: 1.5, deps: ["ecole", "marche"], argent: 8000,
    desc: ["Le Président veut une statue. En or. « Petite, mais en or. »", "Livrez l'or, construisez la statue sur la place du quartier neuf, puis cochez cette quête."],
    taches: [livrer("grandir/statue", "minecraft:gold_block", 16, "/a"), coche("grandir/statue")],
    recompenses: [commande("grandir/statue", '/title {p} title {"text":"Ami du Président","color":"gold"}')] }),
  chantier("tour", { titre: "Plus haut que les arbres", sous: "La tour de Beauvais", x: 10, y: -1.5, deps: ["gare"], argent: 8000,
    desc: ["Le Hejj a rêvé d'une tour plus haute que les arbres géants. Construisez-la : au moins 60 blocs de haut."],
    taches: [coche("grandir/tour")] }),
  chantier("commissariat", { titre: "Le commissariat", sous: "Une vraie police pour Beauvais", x: 6, y: -4.5, deps: ["quartier"], argent: 4000,
    desc: ["Avec les Pillards qui rôdent, Beauvais a besoin d'une vraie police (et pas de la garde du Président).", "",
      "Livrez les matériaux, puis construisez un commissariat dans le quartier neuf : un bureau, une cellule, un parking pour la voiture. Cochez quand c'est fini."],
    taches: [livrer("grandir/commissariat", "minecraft:stone_bricks", 128, "/a"), livrer("grandir/commissariat", "minecraft:iron_bars", 32, "/b"), coche("grandir/commissariat")],
    recompenses: [donne("grandir/commissariat", "cabane:gilet_police"), donne("grandir/commissariat", "cabane:menottes")] }),
  chantier("remparts", { titre: "Les remparts", sous: "Contre les raids du sud", x: 8, y: -4.5, deps: ["commissariat"], argent: 6000,
    desc: ["Les raids viennent toujours du sud. Une muraille autour du quartier neuf, avec une porte qui ferme, et les Pillards n'auront qu'à bien se tenir.", "",
      "Livrez les pierres, construisez les remparts, puis cochez."],
    taches: [livrer("grandir/remparts", "minecraft:stone_bricks", 512, "/a"), livrer("grandir/remparts", "minecraft:cobblestone_wall", 64, "/b"), coche("grandir/remparts")] }),
  chantier("taverne", { titre: "La taverne", sous: "Pour fêter les retours d'expédition", x: 4, y: 3, deps: ["marche"], argent: 3000,
    desc: ["Gérard réclame un bar. Le Hejj aussi, discrètement. Un comptoir, des tonneaux, une terrasse… et de quoi remplir les verres.", "",
      "Livrez les tonneaux et les premières bières (des blondes, à brasser ou à acheter chez Mouloud), construisez la taverne, puis cochez.", "",
      "Ensuite, vendez vos boissons : le marché rachète bières, vin et sodas dans ses contrats, et vos étals feront le reste."],
    taches: [livrer("grandir/taverne", "minecraft:barrel", 16, "/a"), livrer("grandir/taverne", "cabane:biere_blonde", 32, "/b"), coche("grandir/taverne")],
    recompenses: [donne("grandir/taverne", "cabane:biere_86", 4)] }),
  chantier("infirmerie", { titre: "L'infirmerie de Nyota", x: 6, y: 3, deps: ["ecole"], argent: 2500,
    desc: ["Les blessés des raids et des expéditions ont besoin de soins. Nyota veut des lits propres et de quoi préparer ses remèdes."],
    taches: [livrer("grandir/infirmerie", "minecraft:white_bed", 6, "/a"), livrer("grandir/infirmerie", "minecraft:glistering_melon_slice", 16, "/b")] }),
  chantier("capitale", { titre: "&6&lBeauvais, capitale de la jungle", x: 12, y: 0, forme: "gear", taille: 2, deps: ["tour", "statue", "gare", "remparts", "taverne"], argent: 20000,
    desc: ["Le petit village est devenu une ville. Les habitants vous saluent enfin dans la rue.", "", "Même Gérard. Même Vegeta (de loin)."],
    taches: [coche("grandir/capitale")],
    recompenses: [donne("grandir/capitale", "minecraft:diamond_block", 2), commande("grandir/capitale", '/title {p} title {"text":"Bâtisseur de Beauvais","color":"gold"}')] }),
] });

// ---------------------------------------------------------------- chapitre : les gens de Beauvais (une petite quête par personne)
const service = (k, o) => quete("gens/" + k, { deps: ["village/accueil"], ...o });
const gens = chapitre("les_gens", { titre: "&bLes gens de Beauvais", icone: "minecraft:cake", ordre: 4, quetes: [
  service("jean", { titre: "Jean se méfie", x: 0, y: -3, argent: 300, desc: ["« Encore un de l'avion… » Jean aime le poisson grillé. Apporte-lui-en, ça l'adoucira peut-être."],
    taches: [livrer("gens/jean", "minecraft:cooked_cod", 16)] }),
  service("jacques", { titre: "Le balai de Jacques", x: 2, y: -3, argent: 250, desc: ["Jacques a cassé son balai à force de balayer. Il lui faut de quoi en faire un neuf."],
    taches: [livrer("gens/jacques", "minecraft:stick", 32, "/a"), livrer("gens/jacques", "minecraft:wheat", 16, "/b")] }),
  service("francis", { titre: "Pas de bruit après minuit", x: 4, y: -3, argent: 300, desc: ["Francis veut des tapis partout « pour étouffer les pas des gens de l'avion »."],
    taches: [livrer("gens/francis", "minecraft:brown_carpet", 32)] }),
  service("louis", { titre: "Les nuages de Louis", x: 6, y: -3, argent: 400, desc: ["Louis regarde les nuages toute la journée. Avec une longue-vue, il les verrait de plus près."],
    taches: [livrer("gens/louis", "minecraft:spyglass", 1)] }),
  service("bernard", { titre: "Le singe de Bernard", x: 8, y: -3, argent: 300, desc: ["Bernard (l'habitant) cherche de quoi nourrir Bernard (le singe). Le singe adore les baies."],
    taches: [livrer("gens/bernard", "minecraft:sweet_berries", 48)] }),
  service("gerard", { titre: "Le tronc de Gérard", x: 0, y: -1.5, argent: 250, desc: ["Quelqu'un s'est assis sur le tronc de Gérard. Il en veut un nouveau. Un beau."],
    taches: [livrer("gens/gerard", "minecraft:jungle_log", 16)] }),
  service("ginette", { titre: "Mange une carotte", x: 2, y: -1.5, argent: 250, desc: ["Ginette trouve que tout le monde est trop maigre. Elle prépare une soupe géante."],
    taches: [livrer("gens/ginette", "minecraft:carrot", 48, "/a"), livrer("gens/ginette", "minecraft:potato", 48, "/b")] }),
  service("monique", { titre: "Les potins de Monique", x: 4, y: -1.5, argent: 300, desc: ["Monique veut tout noter. Absolument tout. Un livre et une plume, vite."],
    taches: [livrer("gens/monique", "minecraft:writable_book", 1)] }),
  service("martine", { titre: "Message pour Vegeta", x: 6, y: -1.5, argent: 200, desc: ["Martine veut que tu dises à Vegeta, près de la tour de guet, que sa coiffure est ridicule.", "Va lui parler (à tes risques), puis coche la quête."],
    taches: [coche("gens/martine")] }),
  service("thierry", { titre: "Faire ses preuves", x: 8, y: -1.5, argent: 500, desc: ["« Ici, faut faire ses preuves. » Thierry veut que tu nettoies la jungle des zombies."],
    taches: [tuer("gens/thierry", "minecraft:zombie", 20)] }),
  service("vegeta", { titre: "&9Ma stupide fierté", sous: "L'entraînement du prince", x: 0, y: 0, argent: 1000, forme: "diamond",
    desc: ["« Ton niveau de puissance est ridicule. Va combattre cinquante monstres, et on en reparlera. Hmph. »"],
    taches: [tuer("gens/vegeta", "minecraft:skeleton", 25, ), tuer("gens/vegeta2", "minecraft:creeper", 10)],
    recompenses: [commande("gens/vegeta", '/title {p} title {"text":"Digne du prince","color":"dark_blue"}')] }),
  service("macron", { titre: "Traverser la rue", sous: "pour trouver du travail", x: 2, y: 0, argent: 500,
    desc: ["« Du travail ? Je traverse la rue et je vous en trouve ! » Le Président veut que tu remplisses trois contrats de Mehdi.", "Fais-le, puis coche la quête."],
    taches: [coche("gens/macron")] }),
  service("mouloud", { titre: "Client fidèle", x: 4, y: 0, argent: 300, desc: ["Mouloud aime les clients fidèles. Achète-lui un booster de cartes (au moins un)."],
    taches: [avoir("gens/mouloud", "cabane:booster")] }),
  service("gribbs", { titre: "Les cailloux peints de Gribbs", x: 6, y: 0, argent: 300, desc: ["Gribbs a besoin de « matière première » pour ses « œuvres d'art ». C'est de la terre cuite colorée."],
    taches: [livrer("gens/gribbs", "minecraft:orange_terracotta", 16, "/a"), livrer("gens/gribbs", "minecraft:cyan_terracotta", 16, "/b")] }),
  service("mehdi", { titre: "Le dossier complet", x: 8, y: 0, argent: 600, desc: ["Pour une fois, ton dossier sera complet. Mehdi demande : du papier, une étiquette et une horloge (« pour la ponctualité »)."],
    taches: [livrer("gens/mehdi", "minecraft:paper", 16, "/a"), livrer("gens/mehdi", "minecraft:name_tag", 1, "/b"), livrer("gens/mehdi", "minecraft:clock", 1, "/c")] }),
  service("grosh", { titre: "Le charbon de Grosh", x: 0, y: 1.5, argent: 400, desc: ["Grosh a « un peu » mangé le charbon de la forge. Brokk est furieux. Remplace-le vite."],
    taches: [livrer("gens/grosh", "minecraft:coal", 64)] }),
  service("ovsie", { titre: "La couronne d'Ovsie", x: 2, y: 1.5, argent: 300, desc: ["Ovsie veut refaire sa couronne de fleurs. Des coquelicots et des pissenlits, s'il te plaît !"],
    taches: [livrer("gens/ovsie", "minecraft:poppy", 16, "/a"), livrer("gens/ovsie", "minecraft:dandelion", 16, "/b")] }),
  service("nyota", { titre: "Les remèdes de Nyota", x: 4, y: 1.5, argent: 500, desc: ["Nyota prépare des remèdes pour les explorateurs qui partent loin."],
    taches: [livrer("gens/nyota", "minecraft:golden_carrot", 8, "/a"), livrer("gens/nyota", "minecraft:glow_berries", 16, "/b")] }),
  service("karim", { titre: "Une carte pour Karim", x: 6, y: 1.5, argent: 400, desc: ["Karim veut une carte des environs dessinée par toi. « Chouf, on verra si tu sais tenir une boussole. »"],
    taches: [livrer("gens/karim", "minecraft:filled_map", 1)] }),
  service("urga", { titre: "Les lingots d'Urga", x: 8, y: 1.5, argent: 600, desc: ["Urga porte des lingots. Elle en veut plus. Toujours plus."],
    taches: [livrer("gens/urga", "minecraft:iron_ingot", 32, "/a"), livrer("gens/urga", "minecraft:gold_ingot", 8, "/b")] }),
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
  desc: ["Les sept reliques sont de retour sur le comptoir.", "", "La Confrérie des Explorateurs renaît : vous êtes les nouveaux Gardiens de Beauvais."],
  taches: [coche("reveil")],
  recompenses: [donne("reveil", "minecraft:elytra"), donne("reveil", "minecraft:diamond", 16),
    commande("reveil", `/title {p} title {"text":"Gardien de Beauvais","color":"gold"}`)] }));
const chapReliques = chapitre("reliques", { titre: "&eLes Sept Reliques", icone: "cabane:scarabee_dor", ordre: 5, quetes: reliques });

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
const chapDonjons = chapitre("donjons", { titre: "&cLes Grands Donjons", icone: "minecraft:netherite_sword", ordre: 6, quetes: donjons });

// ---------------------------------------------------------------- écriture
fs.rmSync(SORTIE, { recursive: true, force: true });
const ecrire = (rel, txt) => { const f = path.join(SORTIE, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, txt + "\n"); };
ecrire("data.snbt", snbt({
  default_autoclaim_rewards: "disabled", default_consume_items: false, default_quest_disable_jei: false, default_quest_shape: "circle",
  default_reward_team: false, disable_gui: false, drop_book_on_death: false, emergency_items_cooldown: 300, grid_scale: D(0.5),
  lock_message: "", pause_game: false, progression_mode: "linear", version: 13,
}));
ecrire("chapter_groups.snbt", snbt({ chapter_groups: [] }));
for (const [fichier, chap] of [cabane, histoire, village, grandir, gens, chapReliques, chapDonjons]) ecrire(`chapters/${fichier}.snbt`, snbt(chap));
const langTxt = snbt(lang);
ecrire("lang/fr_fr.snbt", langTxt);
ecrire("lang/en_us.snbt", langTxt);
const n = [cabane, histoire, village, grandir, gens, chapReliques, chapDonjons].reduce((s, [, c]) => s + c.quests.length, 0);
console.log(`${n} quêtes écrites dans ${SORTIE}`);
