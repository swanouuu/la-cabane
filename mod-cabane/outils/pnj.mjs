// Les PNJ de Beauvais : l'entité (Easy NPC, data/cabane/pnj/<nom>.snbt, posée par le mod, HubEvents.PNJ) et sa
// conversation (data/cabane/dialogues/<nom>.json, ouverte par le mod au clic droit : fr.cabane.pnj.Dialogues).
// Chaque page peut avoir plusieurs textes : un est tiré au hasard à chaque fois, ça tourne.
// Les boutons : vers (une autre page), action (boutique, forge, contrats…, voir Dialogues.action), ou rien (au revoir).
// Les missions de chacun (outils/missions.mjs) s'ajoutent toutes seules en haut de sa première page.
// La voix : cabane:voix.<voix>, jouée quand on lui parle (les fichiers sont dans voix/, voir le README).
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { RELIQUES } from "./reliques.mjs";

// les PNJ qui ont des répliques enregistrées dans voix/ (même règle que generer.mjs) : les autres ne jouent rien
const DOSSIER_VOIX = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "voix");
const VOIX = new Set(fs.existsSync(DOSSIER_VOIX) ? fs.readdirSync(DOSSIER_VOIX)
  .map((f) => f.toLowerCase().match(/^([a-z_]+?)(?:[-_ ]?\d+|-.+)?\.([a-z0-9]+)$/)).filter((m) => m && m[2] === "ogg").map((m) => m[1]) : []);

const uuid = (graine) => {
  // UUID fixe par PNJ, pour que les quêtes et les gardes puissent le retrouver
  let h = 2166136261;
  const ints = [];
  for (let i = 0; i < 4; i++) {
    for (const ch of `${graine}#${i}`) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
    ints.push(h | 0);
  }
  return `[I;${ints.join(",")}]`;
};
const snbt = (s) => JSON.stringify(s);
const nomJson = (texte, couleur) => `'${JSON.stringify({ text: texte, color: couleur }).replace(/'/g, "\\'")}'`;

/** L'identifiant d'une quête ou d'un chapitre du livre (le même calcul que outils/generer-quetes.mjs). */
export const ftb = (cle) => {
  const h = crypto.createHash("sha1").update("cabane:" + cle).digest("hex").slice(0, 16).toUpperCase();
  return (parseInt(h[0], 16) & 7).toString(16).toUpperCase() + h.slice(1);
};
const livre = (chapitre) => `livre:${ftb("chapitre/" + chapitre)}`;

const IMMOBILE = `{Type:"MOVE_BACK_TO_HOME",Prio:4,SpeedModifier:0.8},{Type:"LOOK_AT_RESET"},{Type:"LOOK_AT_PLAYER",Prio:7}`;
const balade = (vitesse = 0.55) => `{Type:"RANDOM_STROLL_AROUND_HOME",Prio:5,SpeedModifier:${vitesse}},{Type:"MOVE_BACK_TO_HOME",Prio:4,SpeedModifier:0.8},{Type:"OPEN_DOOR",Prio:6},{Type:"LOOK_AT_PLAYER",Prio:7},{Type:"LOOK_RANDOM_AROUND",Prio:8}`;
const BYE = (texte) => ({ texte });
const RETOUR = { texte: "Retour", vers: "default" };

export function genererPnj(ecrire) {
  /**
   * fichier, nom, couleur, peau (textures/entity/pnj/<peau>.png), voix (cabane:voix.<voix>), echelle, objectifs, tags,
   * mains ([main droite, main gauche]), qui (le nom pour les missions, le même pour toutes ses versions),
   * dialogues [{label, titre, texte (ou tableau = au hasard), boutons, ftb (quête du livre cochée en arrivant ici)}].
   */
  function pnj({ fichier, id = fichier, qui = id, nom, couleur, type = "humanoid", variante = "STEVE", rendu, peau = fichier, voix = fichier, echelle, objectifs = IMMOBILE, tags = [], mains, dialogues }) {
    ecrire(`data/cabane/dialogues/${fichier}.json`, {
      nom, couleur, voix: VOIX.has(voix) ? voix : "", qui,
      pages: Object.fromEntries(dialogues.map((d) => [d.label, {
        titre: d.titre,
        textes: [].concat(d.texte),
        boutons: d.boutons.map((b) => ({ texte: b.texte, ...(b.vers ? { vers: b.vers } : {}), ...(b.action ? { action: b.action } : {}) })),
        ...(d.ftb ? { ftb: ftb(d.ftb) } : {}),
      }])),
    });
    // une fée vole : Easy NPC plante le serveur si on lui demande d'ouvrir les portes
    if (type === "fairy") objectifs = objectifs.replace(/,?\{Type:"OPEN_DOOR",Prio:\d+\}/, "");
    const objets = mains ? `
  HandItems:[${mains.map((m) => (m ? `{id:${snbt(m)},count:1}` : "{}")).join(",")}],` : "";
    ecrire(`data/cabane/pnj/${fichier}.snbt`, `{
  id:${snbt("easy_npc:" + type)},
  UUID:${uuid(id)},
  CustomName:${nomJson(nom, couleur)},
  CustomNameVisible:1b,
  PersistenceRequired:1b,
  Tags:[${["cabane_pnj", "pnj." + fichier, ...tags].map(snbt).join(",")}],
  EasyNPCVersion:3,
  VariantType:${snbt(variante)},
  SkinData:${rendu ? `{Type:"DEFAULT"}` : `{Type:"RESOURCE_LOCATION",Texture:${snbt(`cabane:textures/entity/pnj/${peau}.png`)}}`},${rendu ? `
  RenderData:{Type:"CUSTOM_ENTITY",EntityType:${snbt(rendu)}},` : ""}${echelle ? `
  ModelData:{Scale:{Root:[${echelle}f,${echelle}f,${echelle}f]}},` : ""}
  ObjectiveData:{ObjectiveDataSet:[${objectifs}]},${objets}
  Status:{finalized:1b}
}
`);
  }

  // ------------------------------------------------------------ le sage et les grandes figures du village
  pnj({ fichier: "gardien", nom: "Le Hejj du Village", couleur: "gold", peau: "hejj", dialogues: [
    { label: "default", titre: "Le Hejj", texte: [
      "Vous avez vu ça ? Votre avion a pris feu au-dessus de la jungle ! Beauvais vous accueille… à condition de l'aider à grandir.",
      "Ah, les gens du ciel. Asseyez-vous. Non, pas là, c'est le banc de Gérard.",
      "Un village, c'est comme un arbre géant : il pousse lentement, mais il faut l'arroser tous les jours. Vous êtes l'arrosoir.",
      "J'ai rêvé d'un Beauvais avec un port, un pont, une tour plus haute que les arbres. Mon rêve a besoin de vos bras.",
      "Le Président dit que c'est lui qui décide. Je le laisse dire. C'est moi qui sais où sont les clés.",
      "J'étais du Conseil des Anciens, avant. Maintenant je suis… le vieux sur le porche. Va voir Marcel, au feu de camp : il raconte l'histoire mieux que moi.",
      "Chut. Pas trop fort. Les gardes du Président ont de grandes oreilles sous leurs masques.",
    ], boutons: [{ texte: "Ta demande du jour ?", action: "hejj_demande" },
      { texte: "Les grands chantiers du village", action: livre("agrandir_beauvais") },
      { texte: "Comment aider ?", vers: "aider" }, { texte: "Où s'installer ?", vers: "maison" },
      { texte: "Et les reliques ?", vers: "reliques" }, BYE("À plus tard")] },
    { label: "aider", titre: "Agrandir Beauvais", texte: [
      "Le village doit grandir : des maisons, un marché, un pont, un port… Les grands chantiers sont dans le livre de quêtes : je te l'ouvre si tu veux. Chaque chantier fini, le village te paie.",
      "Chaque habitant a un petit service à te demander : parle-leur, ceux qui ont besoin de toi ont un « ! » doré. Mehdi, au Grand Bazar, a des contrats qui changent toutes les heures. Et moi, j'ai ma demande du jour.",
    ], boutons: [{ texte: "Ouvrir les grands chantiers", action: livre("agrandir_beauvais") },
      { texte: "Ta demande du jour ?", action: "hejj_demande" }, RETOUR, BYE("On s'y met !")] },
    { label: "maison", titre: "Vos maisons", texte: [
      "Le cœur du village appartient aux habitants. Vous, installez-vous juste à côté, hors de l'enceinte : construisez vos maisons, vos fermes, vos usines. Et aidez Beauvais à pousser jusqu'à vous.",
      "Les terrains à vendre, derrière le boulevard, sont pour les commerces : clic droit sur leur borne pour acheter. Pour vivre, sortez par la grande porte et choisissez un coin de jungle. Pas trop près de Gérard.",
    ], boutons: [RETOUR, BYE("Compris")] },
    { label: "reliques", titre: "Les Sept Reliques", texte: "Sept reliques reposaient sur le comptoir. Elles ont été cachées dans les plus grands donjons du monde, parfois très loin d'ici. Ramenez-les, et le village se réveillera. Karim, au grand panneau, vend les cartes qui y mènent.",
      boutons: [{ texte: "Ouvrir le chapitre des reliques", action: livre("reliques") }, RETOUR, BYE("J'y vais !")] },
  ] });

  pnj({ fichier: "cartographe", nom: "Karim le Chouf", couleur: "aqua", peau: "karim", dialogues: [
    { label: "default", titre: "Karim", texte: [
      "Chouf, chouf ! Des cartes, encore des cartes ! Regarde le grand panneau derrière moi : c'est tout le monde connu.",
      "J'ai étudié la magie des cartes pendant quarante ans. Résultat : je sais plier une carte. Dans le bon sens. Parfois.",
      "Chaque relique a sa carte. Je te la vends, et la croix rouge te mène droit au donjon. Chouf !",
      "Ma barbe ? Elle pousse vers le nord. Comme ça je ne me perds jamais.",
      "Les gens croient que je suis un sorcier. Je suis cartographe. Bon… un peu sorcier aussi.",
    ], boutons: [{ texte: "Les cartes des reliques", vers: "cartes" }, { texte: "Le matériel d'explorateur", action: "boutique:pnj.cartographe" },
      { texte: "Un conseil ?", vers: "conseil" }, BYE("Merci Karim")] },
    { label: "cartes", titre: "Les cartes au trésor", texte: "Chaque relique dort dans un donjon lointain, à des milliers de pas d'ici. Ma carte te mène droit dessus : 300 $ la carte. Prévois un lit, à manger, et une Waystone. Chouf !",
      boutons: [...RELIQUES.map((r) => ({ texte: `${r.lieu} (${r.distance} blocs)`, action: `tresor:${r.cle}:${r.distance}:300` })), RETOUR] },
    { label: "conseil", titre: "Conseil", texte: [
      "Les citadelles du ciel et les fonderies sont très loin. Pose des Waystones en chemin, et ne pars jamais sans lit.",
      "Au nord, la neige. Au sud, le désert. À l'est, des volcans. Et ici, la jungle. Prends un manteau ET de l'eau.",
    ], boutons: [RETOUR, BYE("Noté !")] },
  ] });

  pnj({ fichier: "marchand", nom: "Mouloud le Marchand", couleur: "green", peau: "mouloud", dialogues: [
    { label: "default", titre: "Mouloud", texte: [
      "Bienvenue au Grand Bazar ! Ici tout est à vendre. Même le comptoir, si tu payes assez.",
      "Mon ami ! Mon frère ! Mon client préféré ! … C'est quoi ton nom déjà ?",
      "Prix d'ami aujourd'hui. Les prix d'ami, c'est les mêmes, mais je souris en plus.",
      "Un téléphone, un parachute, un étal pour vendre tes trucs… et des lunettes de soleil. Pour avoir l'air de quelqu'un.",
      "Les cartes à collectionner ? Va voir Jean-Mi, sur la grande allée. Moi, je vends des choses utiles.",
      "Mon cousin Gribbs dit qu'il a de meilleurs prix. Mon cousin Gribbs vend des cailloux peints.",
    ], boutons: [{ texte: "Voir la boutique", action: "boutique" }, BYE("À plus")] },
  ] });

  pnj({ fichier: "cousin", nom: "Gribbs, cousin de Mouloud", couleur: "dark_aqua", peau: "gribbs", dialogues: [
    { label: "default", titre: "Gribbs", texte: [
      "Psst ! Toi ! Tu veux une montre ? … J'en ai une, cette fois. Elle donne l'heure. Parfois.",
      "Mouloud, c'est mon cousin. Enfin, cousin… on a le même oncle. Enfin, oncle… c'est un voisin.",
      "Je vends des secrets. 15 $ le secret. Des vrais. Presque tous.",
      "Si un garde te demande, tu m'as jamais vu. Si Mouloud te demande, je suis très beau.",
    ], boutons: [{ texte: "Montre-moi ta marchandise", action: "boutique:pnj.cousin" }, { texte: "Un secret (15 $)", action: "payer:15:secret" },
      BYE("Euh… à plus")] },
    { label: "secret", titre: "Un secret de Gribbs", texte: [
      "Le Président a peur des poules. C'est vrai. Ne lui dis pas que c'est moi.",
      "Dans le bureau du Président, à la Mairie, une bibliothèque sonne creux. Une seule. Clique sur la bonne… et descends.",
      "Le Fourgue, derrière la tour de guet, rachète tout. TOUT. Même ce que tu ne devrais pas avoir.",
      "Karim vend les cartes des donjons à reliques. Il dit que c'est de la magie. C'est du papier.",
      "Nyota soigne même la gueule de bois. Je dis ça, je dis rien.",
      "Brokk forge des armes de monstres. Apporte-lui les matériaux, et beaucoup d'argent. Beaucoup.",
      "Les sept reliques ouvrent une salle. La Salle Zéro, très loin au sud. Ne me demande pas comment je le sais.",
      "Mehdi change ses contrats toutes les heures. Les mieux payés partent vite.",
      "Sylvie attend devant la Mairie tous les soirs. Son Pierre n'est jamais ressorti. Moi, je dis rien. Mais je regarde.",
      "Le Hejj a une demande différente chaque jour. Il paie bien, pour un vieux sur un porche.",
    ], boutons: [{ texte: "Un autre secret (15 $)", action: "payer:15:secret" }, RETOUR, BYE("Merci Gribbs")] },
  ] });

  pnj({ fichier: "pole_emploi", nom: "Mehdi de Pôle emploi", couleur: "blue", peau: "mehdi", dialogues: [
    { label: "default", titre: "Mehdi", texte: [
      "Bonjour, Pôle emploi, Mehdi, enchanté. Vous avez rendez-vous ? … Non ? Bon. Les contrats sont affichés au-dessus du tableau, ils changent toutes les heures.",
      "Prenez un ticket. Il n'y a pas de tickets. Prenez-en un quand même.",
      "Votre profil m'intéresse : vous savez porter des choses ? Parfait. Le village a besoin de bois, de fer et de blé.",
      "Je travaille ici depuis l'avant-dernier avion. Le dernier, il est en feu, c'est le vôtre.",
      "On recrute ! Je vous montre les contrats de l'heure. Et souriez, ça compte pour le dossier.",
    ], boutons: [{ texte: "Voir les contrats", action: "contrats" }, { texte: "Et mon dossier ?", vers: "dossier" }, BYE("Merci Mehdi")] },
    { label: "dossier", titre: "Votre dossier", texte: [
      "Votre dossier ? Il manque une pièce justificative. Il en manque toujours une.",
      "Votre dossier est en cours de traitement. Il est en cours de traitement depuis mardi. Quel mardi ? Bonne question.",
    ], boutons: [{ texte: "Voir les contrats", action: "contrats" }, RETOUR, BYE("D'accord…")] },
  ] });

  // ------------------------------------------------------------ Brokk l'ogre et ses orques
  pnj({ fichier: "forgeron", nom: "Brokk le Forgeron", couleur: "red", type: "orc_warrior", variante: "ORC_WARRIOR", rendu: "mowziesmobs:ferrous_wroughtnaut", dialogues: [
    { label: "default", titre: "Brokk", texte: [
      "CLANG. Brokk forge. Toi regarde. Toi touche pas l'enclume.",
      "Je sais faire ce que personne d'autre ne sait faire : les armes des grands monstres, les reliques de feu. Rapporte les matériaux, paie le travail.",
      "Épées, haches, arcs, armures… Brokk forge tout. Et Brokk signe ce qu'il forge : une pièce de Brokk, ça se voit.",
      "Les armes des boss, Brokk sait les refaire. Pas besoin d'aller battre Ignis : rapporte du feu de blaze, du magma, de la netherite. Brokk fait le reste.",
      "Tu commandes, tu regardes. Brokk tape, Brokk trempe, Brokk te tend la pièce. Personne touche l'enclume pendant ce temps.",
      "Mes orques travaillent bien. Sauf Grosh. Grosh mange le charbon.",
      "Un jour, je forgerai une épée si grande qu'il faudra deux villages pour la porter.",
      "Tu as des bras de brindille. Reviens quand tu pourras soulever un lingot.",
    ], boutons: [{ texte: "Armes et armures", action: "forge" }, { texte: "Les reliques des boss", action: "forge:relique" },
      { texte: "Du travail ?", vers: "travail" }, BYE("À plus, Brokk")] },
    { label: "travail", titre: "Le travail", texte: "Brokk veut du fer. Beaucoup de fer. Et le métal du géant de fer, sous la terre. Si Brokk a du travail pour toi, il te le dit tout de suite, en haut. Sinon, va voir les contrats de Mehdi.",
      boutons: [{ texte: "Voir les contrats", action: "contrats" }, RETOUR, BYE("Compris")] },
  ] });
  const orques = [
    ["orque_1", "Grosh, apprenti de Brokk", "minecraft:iron_pickaxe", ["Grosh pas manger charbon. … Grosh un peu manger charbon.", "Marteau. Enclume. Marteau. Enclume. Grosh aime son travail.", "Toi pas regarder Grosh comme ça."]],
    ["orque_2", "Mog, souffleur de forge", "minecraft:blaze_rod", ["Plus chaud ! Toujours plus chaud ! Brokk aime quand ça brûle.", "Mog souffle sur le feu depuis trois jours. Mog a plus de souffle.", "Attention, ça pique. Tout pique ici."]],
    ["orque_3", "Urga, la porteuse de lingots", "minecraft:iron_ingot", ["Urga porte trois lingots d'une main. Toi, combien ?", "Brokk dit Urga meilleure apprentie. Grosh dit pas vrai. Grosh a tort.", "Pose pas tes pieds sur les lingots, humain."]],
  ];
  for (const [fichier, nom, objet, textes] of orques) {
    pnj({ fichier, nom, couleur: "dark_green", voix: "orque", echelle: 1.15, mains: [objet, null], objectifs: balade(0.4), dialogues: [
      { label: "default", titre: nom.split(",")[0], texte: textes, boutons: [{ texte: "La forge de Brokk", action: "forge" }, BYE("Bon courage")] },
    ] });
  }

  pnj({ fichier: "fee", nom: "Ovsie la Fée", couleur: "light_purple", type: "fairy", variante: "GREEN", rendu: "mowziesmobs:lantern", objectifs: `{Type:"LOOK_AT_RESET"},{Type:"LOOK_AT_PLAYER"}`, dialogues: [
    { label: "default", titre: "Ovsie", texte: [
      "Coucou ! Tu collectionnes les cartes ? Jean-Mi vend les boosters, sur la grande allée, et un classeur garde toute ta collection.",
      "Je brille la nuit. Le jour aussi, mais personne ne remarque.",
      "Si tu trouves une carte légendaire, je te fais un câlin. Un petit. Je suis petite.",
      "Le casino ? C'est mal. … Tu m'emmènes ?",
      "Je vends des petits sorts en bouteille. Ça brille, ça pétille, et parfois ça marche.",
    ], boutons: [{ texte: "Tes petits sorts", action: "boutique:pnj.fee" }, { texte: "Et le casino ?", vers: "casino" }, BYE("Merci Ovsie !")] },
    { label: "casino", titre: "Le casino", texte: "Roulette, roue de la fortune, blackjack… Les tables, c'est l'hôte qui les donne : à vous de construire le plus beau casino de la jungle ! Le gérant remplit la caisse, accroupi + clic droit.",
      boutons: [RETOUR, BYE("On s'y met")] },
  ] });

  pnj({ fichier: "guerisseuse", nom: "Nyota la Guérisseuse", couleur: "dark_green", peau: "nyota", dialogues: [
    { label: "default", titre: "Nyota", texte: [
      "Assieds-toi près du feu, voyageur. Ici on raconte les histoires des donjons lointains… et de ceux qui n'en sont jamais revenus.",
      "Une feuille de jungle sur une coupure, et ça repart. Sur une jambe cassée, c'est plus compliqué.",
      "Le Président m'a demandé une tisane pour le courage. Je lui ai donné de l'eau chaude. Ça a marché.",
      "Dors dans un lit avant de partir loin : sinon tu repars de l'avion. Et l'avion brûle.",
      "Tu titubes ? Tu as trop bu, ou trop fumé ? Viens là, que je te soigne.",
    ], boutons: [{ texte: "Soigne-moi (40 $)", action: "soigner" }, { texte: "Tes remèdes", action: "boutique:pnj.guerisseuse" },
      { texte: "Une histoire ?", vers: "histoire" }, BYE("Merci Nyota")] },
    { label: "histoire", titre: "Les histoires du feu", texte: [
      "Il y a un géant de fer sous la terre. Brokk rêve de son métal. Ceux qui l'ont croisé parlent tout bas, après.",
      "Au sud, très loin, un donjon de pierre noire : le PLATO 6Z. Les Pillards y vont, et en reviennent avec de l'or de la Mairie. Étrange, non ?",
      "Pierre, le mari de Sylvie, venait ici chaque soir. Il racontait des blagues nulles. Il me manque, ses blagues nulles.",
    ], boutons: [RETOUR, BYE("Merci Nyota")] },
  ] });

  for (const [cote, peau] of [["est", "garde_porte_1"], ["ouest", "garde_porte_2"]]) {
    pnj({ fichier: "garde_" + cote, nom: "Garde de Beauvais", couleur: "yellow", peau, voix: "garde_" + cote, mains: ["minecraft:trident", null], dialogues: [
      { label: "default", titre: "Garde", texte: [
        "Halte, voyageur ! … Ah, tu viens de l'avion en feu. Entre : le Hejj t'attend devant la Grande Cabane.",
        "Rien à signaler. Sauf un singe qui m'a volé mon casque hier. Si tu le vois, il s'appelle Bernard. Pas l'habitant, le singe.",
        "Je garde cette porte depuis dix ans. Personne n'a jamais essayé d'entrer par le mur. Heureusement.",
        "Le soir, on entend des choses dans la jungle. Le plus souvent c'est Gérard qui ronfle.",
      ], boutons: [{ texte: "Où trouver… ?", vers: "plan" }, BYE("Merci !")] },
      { label: "plan", titre: "Le plan du village", texte: "En remontant la grande allée vers le nord : la boutique de cartes de Jean-Mi à gauche. En face, la Mairie. Au bout, la place et le grand panneau de Karim. À l'ouest de la place, le Grand Bazar (Mouloud, Mehdi). À l'est, la rue, jusqu'à la forge de Brokk. Au nord, la Grande Cabane et le Hejj. Le feu de camp, Marcel et Nyota : au sud-ouest de la place.",
        boutons: [RETOUR, BYE("Merci !")] },
    ] });
  }

  // ------------------------------------------------------------ Marcel, le conteur du feu de camp : l'histoire de Beauvais
  pnj({ fichier: "conteur", nom: "Marcel le Conteur", couleur: "gold", dialogues: [
    { label: "default", titre: "Marcel", texte: [
      "Ah, des gens du ciel… Asseyez-vous près du feu. Vous voulez savoir pourquoi les gens d'ici vous regardent de travers ? C'est une longue histoire.",
      "Bonsoir, voyageur. Le feu est chaud, la nuit est longue. Une histoire ?",
    ], boutons: [{ texte: "Raconte-moi Beauvais", vers: "h1" }, { texte: "Le livre de l'histoire", action: livre("histoire") }, BYE("Une autre fois")] },
    { label: "h1", titre: "Le temps des Anciens", texte: "Il y a longtemps, Beauvais était le village le plus paisible de la jungle. Les arbres géants nous protégeaient, la rivière nous nourrissait, et le Conseil des Anciens décidait de tout, ici, autour de ce feu. Sur le comptoir de la Grande Cabane reposaient les Sept Reliques : tant qu'elles étaient là, aucun malheur n'entrait au village.",
      boutons: [{ texte: "Et ensuite ?", vers: "h2" }] },
    { label: "h2", titre: "L'homme en costume", texte: "Un jour, un homme en costume est arrivé. Il parlait bien, il souriait beaucoup, il promettait tout : la prospérité, la sécurité… et en même temps, l'inverse. Le Conseil l'a écouté. Puis il a pris le titre de Président, s'est entouré d'une garde masquée, et le Conseil n'a plus jamais été réuni.",
      boutons: [{ texte: "Et ensuite ?", vers: "h3" }] },
    { label: "h3", titre: "Les Pillards du Sud", texte: "Peu après, les Pillards sont arrivés du sud, des marais. Ils brûlent les récoltes, vident les greniers, et repartent avant l'aube. Le Président dit qu'il nous protège. Mais sa garde ne protège que lui. Et une nuit, les Sept Reliques ont disparu. Personne n'a rien vu. Personne n'a rien entendu.",
      boutons: [{ texte: "Et ensuite ?", vers: "h4" }] },
    { label: "h4", titre: "La Mairie", texte: "Depuis, la Mairie est fermée la nuit. Certains disent qu'on entend des machines sous la terre. Et des gens disparaissent : « partis travailler à la capitale », dit le Président. Le dernier, c'est Pierre, le mari de Sylvie. Personne n'est jamais revenu de la capitale. On ne sait même pas où elle est, la capitale.",
      boutons: [{ texte: "Et ensuite ?", vers: "h5" }] },
    { label: "h5", titre: "La prophétie", ftb: "histoire/conteur", texte: "Alors oui, on se méfie des étrangers : tous ceux qui sont venus ont fini par voler, ou par trahir. Mais une vieille prophétie dit : « Quand le feu tombera du ciel, ceux qui en sortiront rendront Beauvais à son peuple. » Votre avion est tombé en feu… Faites vos preuves. Le livre de quêtes vous guidera : « L'Histoire de Beauvais ».",
      boutons: [{ texte: "Ouvrir « L'Histoire de Beauvais »", action: livre("histoire") }, BYE("Merci, Marcel")] },
  ] });

  // ------------------------------------------------------------ Pierre, enfermé dans le laboratoire sous la Mairie
  pnj({ fichier: "prisonnier", nom: "Pierre, le disparu", couleur: "gray", dialogues: [
    { label: "default", titre: "Pierre", ftb: "histoire/pierre", texte: [
      "Vous… vous n'êtes pas des gardes ? Écoutez-moi : le Président ne protège personne. Les Pillards du Sud, c'est lui qui les envoie. Il leur dit quand frapper.",
      "Il m'a enfermé parce que je posais trop de questions. Les autres… il leur fait boire la potion verte. Après, ils ne parlent plus.",
      "Sylvie… Lucas… Est-ce qu'ils savent que je suis là ? Est-ce qu'ils me cherchent encore ?",
    ], boutons: [{ texte: "Le projet AUGMENTÉ ?", vers: "projet" }, { texte: "Comment te sortir de là ?", vers: "sortir" }, BYE("Tiens bon, Pierre")] },
    { label: "projet", titre: "Le projet AUGMENTÉ", texte: "Il veut devenir plus fort que sa garde, plus fort que tout le village. Sa machine l'attend dans la Salle Zéro, au cœur du PLATO 6Z, chez ses amis les Pillards : 10 000 pas plein sud. Seules les Sept Reliques peuvent ouvrir la Salle Zéro, alors il les a fait cacher aux quatre coins du monde, dans les pires donjons. Rapportez-les : Brokk saura en forger une clé.",
      boutons: [RETOUR, BYE("On va les trouver")] },
    { label: "sortir", titre: "Pas encore", texte: "Pas encore. S'il voit ma cellule vide, il saura qu'on est venus. Libérez d'abord Beauvais. Je tiendrai. Et prenez le dossier dans le coffre, là-bas : tout y est écrit.",
      boutons: [RETOUR, BYE("Tiens bon, Pierre")] },
  ] });
  pnj({ fichier: "pierre_libre", nom: "Pierre, le revenant", couleur: "green", peau: "prisonnier", voix: "prisonnier", dialogues: [
    { label: "default", titre: "Pierre", texte: [
      "Libre ! Je respire enfin l'air de la jungle. Merci, les gens du ciel. Marcel n'arrête pas de raconter votre combat.",
      "Le Conseil des Anciens va se réunir à nouveau. Et cette fois, c'est vous qu'on écoutera.",
      "Je suis allé voir l'ancien président dans ma vieille cellule. Il m'a demandé un référendum. J'ai refusé.",
      "Sylvie a gardé ma lettre sous son oreiller. Et Lucas a accroché son dessin au-dessus de mon lit. Le super-héros, c'est moi, paraît-il.",
      "Ce soir, j'apprends à pêcher à Lucas. Je lui avais promis. Dans ma lettre.",
    ], boutons: [BYE("Content de te voir dehors")] },
  ] });
  pnj({ fichier: "macron_prison", nom: "Macron, ancien président", couleur: "gray", peau: "macron", voix: "macron", dialogues: [
    { label: "default", titre: "L'ancien président", ftb: "histoire/epilogue", texte: [
      "Je n'ai jamais dit ça. Et si je l'ai dit, c'était sorti de son contexte.",
      "C'était… en même temps une erreur et un succès.",
      "Je demande un référendum. Non ? Une consultation citoyenne, alors ?",
      "Je traverse la cellule et je ne trouve pas de travail. Ça ne marche pas dans ce sens-là.",
    ], boutons: [BYE("Bonne retraite")] },
  ] });

  // ------------------------------------------------------------ Sylvie et Lucas : la femme et le fils de Pierre
  pnj({ fichier: "sylvie", nom: "Sylvie, la femme de Pierre", couleur: "light_purple", dialogues: [
    { label: "default", titre: "Sylvie", texte: [
      "Excusez-moi… Vous n'auriez pas vu mon mari ? Pierre. Grand, un chapeau de paille, il parle trop fort. Il est entré dans cette Mairie il y a trois semaines. Il n'en est jamais ressorti.",
      "Tous les soirs, je viens ici avec Lucas. On attend. Le Président passe devant nous et il sourit. Il sourit toujours.",
      "« Parti travailler à la capitale. » Pierre ne serait jamais parti sans embrasser son fils. Jamais.",
    ], boutons: [{ texte: "Parle-moi de Pierre", vers: "pierre" }, BYE("Courage, Sylvie")] },
    { label: "pierre", titre: "Pierre", texte: "Il est pêcheur, et il fait les blagues les plus nulles de Beauvais. Il avait promis à Lucas de lui apprendre à pêcher. Le jour où il a disparu, il était allé porter une pétition au Président : trente signatures contre les Pillards. Trente. Il était si fier.",
      boutons: [RETOUR, BYE("On va le retrouver")] },
  ] });
  pnj({ fichier: "lucas", nom: "Lucas, le fils de Pierre", couleur: "aqua", echelle: 0.6, dialogues: [
    { label: "default", titre: "Lucas", texte: [
      "T'as pas vu mon papa ? Il a un chapeau. Et il fait des blagues nulles. Mais moi je rigole quand même.",
      "Maman dit qu'il est à la capitale. Mais la capitale c'est où ? Personne sait.",
      "Quand je serai grand, je serai garde. Comme ça je pourrai entrer dans la Mairie. Et je le chercherai moi-même.",
    ], boutons: [BYE("On va le retrouver, promis")] },
  ] });
  pnj({ fichier: "sylvie_libre", qui: "sylvie", nom: "Sylvie, la femme de Pierre", couleur: "light_purple", peau: "sylvie", voix: "sylvie", dialogues: [
    { label: "default", titre: "Sylvie", texte: [
      "Il est là. Il est vraiment là, au coin du feu, avec ses blagues nulles. Je ne m'en lasserai jamais.",
      "Vous voulez dîner avec nous ? Pierre a pêché. Enfin, Lucas a pêché, Pierre a regardé.",
      "Je passe devant la Mairie, maintenant, et je souris. C'est mon tour.",
    ], boutons: [BYE("Bonne soirée, Sylvie")] },
  ] });
  pnj({ fichier: "lucas_libre", qui: "lucas", nom: "Lucas, le fils de Pierre", couleur: "aqua", peau: "lucas", voix: "lucas", echelle: 0.6, objectifs: balade(0.5), dialogues: [
    { label: "default", titre: "Lucas", texte: [
      "MON PAPA EST RENTRÉ ! Il avait gardé mon dessin dans sa poche ! Tout froissé !",
      "Papa m'apprend à pêcher. J'ai attrapé une botte. Il dit que ça compte.",
      "Quand je serai grand, je serai comme toi. Je sauverai des gens. Et je mangerai des gâteaux tous les jours.",
    ], boutons: [BYE("Salut champion")] },
  ] });

  // ------------------------------------------------------------ Vegeta, qui s'entraîne près de la tour de guet
  pnj({ fichier: "vegeta", tags: ["ambiance_vegeta"], nom: "Vegeta", couleur: "dark_blue", objectifs: IMMOBILE, dialogues: [
    { label: "default", titre: "Vegeta", texte: [
      "Hmph. Ma stupide fierté m'empêche de te dire bonjour. Alors je te dis : dégage, insecte.",
      "Je suis le prince de tous les Saiyans ! … Ici on m'appelle « le monsieur qui crie près de la tour », mais bon.",
      "Ma stupide fierté… voilà ce qui m'a perdu. Ça, et le blackjack.",
      "Ton niveau de puissance ? Ridicule. Même Ovsie te met une raclée.",
      "Kakarot ! … Ah non. C'est toi. Quelle déception.",
      "Je m'entraîne sous une gravité trois cents fois supérieure. Bon, c'est un sac de sable. Mais il est lourd.",
      "Plus de huit mille ?! … Ah, c'est le prix d'une parcelle. Je m'emporte.",
    ], boutons: [{ texte: "Pourquoi tant de fierté ?", vers: "fierte" }, BYE("D'accord, prince…")] },
    { label: "fierte", titre: "La fierté", texte: [
      "Ma stupide fierté, c'est tout ce qui me reste ! Et ma coupe de cheveux. Elle tient toute seule. Sans gel.",
      "Un prince ne demande jamais son chemin. C'est pour ça que je suis à Beauvais depuis six ans.",
    ], boutons: [RETOUR, BYE("Respect, prince")] },
  ] });
  // Jean-Mi Collector, la boutique de cartes de la grande allée : il sait tout, il juge tout
  pnj({ fichier: "vendeur_cartes", nom: "Jean-Mi Collector", couleur: "light_purple", tags: ["vendeur_cartes"], dialogues: [
    { label: "default", titre: "Jean-Mi", texte: [
      "Ah. Un client. Laisse-moi deviner : tu ne sais même pas ce qu'est une carte holographique.",
      "Bienvenue dans le seul vrai commerce culturel de Beauvais. Ne touche pas la vitrine. Ni le comptoir. Ni moi.",
      "Un booster, c'est cinq cartes. La meilleure arrive toujours en dernier. C'est comme ça, c'est la loi.",
      "J'ai une légendaire sous plastique depuis quatre ans. Elle ne sort pas. Elle ne sortira jamais.",
    ], boutons: [{ texte: "Je veux des boosters", action: "boutique:vendeur_cartes" },
      { texte: "Un conseil de pro ?", vers: "conseil" }, BYE("Merci Jean-Mi")] },
    { label: "conseil", titre: "Jean-Mi", texte: [
      "Range tes cartes dans un classeur, clic droit avec le classeur en main. Une carte qui traîne dans un inventaire, c'est une carte qui souffre.",
      "Les rares brillent, les épiques tremblent, les légendaires assombrissent tout. Si ton écran devient noir, ne crie pas. Pleure en silence.",
    ], boutons: [RETOUR, BYE("Noté")] },
  ] });

  // le Fourgue, dans l'ombre derrière la tour de guet : le marché noir
  pnj({ fichier: "fourgue", nom: "Le Fourgue", couleur: "dark_gray", tags: ["fourgue"], dialogues: [
    { label: "default", titre: "Le Fourgue", texte: [
      "Psst. T'as pas vu de flics en venant ? … Bon. Qu'est-ce que tu veux ?",
      "Je vends des graines. Pour le jardinage. Rien d'autre. Le jardinage.",
      "Tu ramènes la marchandise, je paye cash. Pas de questions, pas de reçu.",
      "La police de Beauvais me cherche depuis des mois. Ils cherchent mal.",
      "Un conseil d'ami : la descente, c'est pire que la montée. Mais bon, c'est pas mon problème.",
    ], boutons: [{ texte: "Montre-moi ce que t'as", action: "fourgue" },
      { texte: "J'ai de la marchandise", action: "fourgue_vendre" },
      { texte: "Comment on cuisine ?", vers: "labo" }, BYE("J'ai rien vu")] },
    { label: "labo", titre: "Le Fourgue", texte: [
      "Les graines, tu les plantes sur de la terre labourée, comme du blé. La weed donne des têtes, la coca des feuilles. Un joint, c'est une tête et du papier. Pour le reste, il te faut mon labo : 4 têtes donnent du hash, 6 feuilles et de la poudre à canon donnent de la coke, et la meth… redstone, sucre, poudre de blaze. Elle explose une fois sur cinq. Cuisine dehors.",
    ], boutons: [RETOUR, BYE("Compris")] },
  ] });

  // l'hôte a armé l'événement : Vegeta sent Kakarot arriver
  pnj({ fichier: "vegeta_arme", tags: ["ambiance_vegeta"], id: "vegeta", peau: "vegeta", voix: "vegeta", nom: "Vegeta", couleur: "dark_red", objectifs: IMMOBILE, dialogues: [
    { label: "default", titre: "Vegeta", texte: [
      "Il est là. Je le sens… Kakarot. Allez chercher tout le monde. TOUT le monde.",
      "Ma capsule s'est écrasée ici en le poursuivant. Aujourd'hui, il vient à moi. Réunissez-vous autour de moi, et parlez-moi.",
      "Vous tremblez ? Normal. Ce qui va se passer dans le ciel de Beauvais, personne ne l'a jamais vu.",
    ], boutons: [BYE("On arrive !")] },
  ] });
  // après le combat, si on a aidé Goku : le M a disparu, Vegeta est vexé mais reconnaissant
  pnj({ fichier: "vegeta_goku", tags: ["ambiance_vegeta"], id: "vegeta", peau: "vegeta", voix: "vegeta", nom: "Vegeta", couleur: "dark_blue", objectifs: IMMOBILE, dialogues: [
    { label: "default", titre: "Vegeta", texte: [
      "Le M de Babidi a disparu. Ne vous faites pas d'idées : je ne vous dois rien. … Merci.",
      "Kakarot est reparti s'entraîner. Il a dit que Beauvais était « un super coin pour pique-niquer ». Idiot.",
      "Si vous racontez à quelqu'un que vous m'avez sauvé, je fais exploser votre maison. Gentiment.",
    ], boutons: [BYE("Promis, prince")] },
  ] });
  // après le combat, si on a aidé Vegeta : le Prince a battu Kakarot, il est intenable
  pnj({ fichier: "vegeta_vegeta", tags: ["ambiance_vegeta"], id: "vegeta", peau: "majin_vegeta", voix: "vegeta", nom: "Vegeta", couleur: "dark_purple", objectifs: IMMOBILE, dialogues: [
    { label: "default", titre: "Majin Vegeta", texte: [
      "J'AI BATTU KAKAROT ! Avec un peu d'aide. Très peu. Presque rien.",
      "Le M ? Je le garde. Ça me va bien, non ?",
      "Vous avez choisi le bon camp. Le camp du Prince. Vous aurez une place d'honneur dans mon futur royaume de Beauvais.",
    ], boutons: [BYE("Vive le Prince !")] },
  ] });

  // ------------------------------------------------------------ les habitants : ils se baladent et se méfient des nouveaux
  const habitants = [
    ["jean", "Jean", ["Mon petit frère est parti « à la capitale ». Il n'a même pas pris son chapeau. Il ne sortait jamais sans son chapeau.", "Hm. Encore un de l'avion. Ici on n'aime pas trop les nouveaux, faut le temps qu'on vous connaisse.", "Mon grand-père a planté cet arbre. Mon père l'a coupé. Moi je le regarde. C'est une tradition.", "Tu marches trop vite. Ici, on marche lentement. Ça énerve les touristes, c'est le but."], "Bonne journée quand même"],
    ["jacques", "Jacques", ["Les Pillards sont passés mardi. Ils ont tout piétiné. Même mon chemin. MON chemin.", "Tu marches sur mon chemin, là. Je l'ai balayé ce matin.", "J'ai balayé ce chemin trois fois aujourd'hui. Trois. Fois.", "Le Président m'a promis une médaille du meilleur balayeur. J'attends toujours."], "Pardon, Jacques"],
    ["francis", "Francis", ["Le dernier étranger, il a dormi trois nuits chez moi, et il est reparti avec ma vaisselle. Alors excusez-moi si je compte mes cuillères.", "Les gens de l'avion, ils arrivent, ils achètent les terrains, et après ça fait du bruit jusqu'à minuit.", "Je vous ai à l'œil. L'autre œil est sur Gribbs.", "Si vous construisez à côté de chez moi, faites une jolie maison. Pas un truc en terre."], "Promis, on sera sages"],
    ["louis", "Louis", ["La nuit, j'entends des machines sous la place. Ma femme dit que c'est mon ventre. C'est pas mon ventre.", "Tu cherches quoi ? Le Bazar, c'est par là. La forge, c'est par là. Et moi, je suis occupé.", "Occupé à quoi ? Ça te regarde pas.", "Bon… je regardais les nuages. Celui-là ressemble à Mouloud."], "D'accord, d'accord"],
    ["bernard", "Bernard", ["Les Pillards viennent du sud, des marais. Ils sentent la vase et la poudre. Le singe les entend venir avant tout le monde.", "De mon temps, on n'avait pas de téléphone. On criait. Et ça marchait très bien.", "Mon singe s'appelle Bernard aussi. C'est pratique quand on crie.", "Les jeunes, avec leur Piaf… moi j'ai des vrais piafs. Ils font caca partout."], "C'est noté, Bernard"],
    ["gerard", "Gérard", ["Le feu de camp, c'était là que le Conseil se réunissait. Avant. Maintenant c'est juste moi et mon tronc.", "Le feu de camp, c'est MA place. Tu peux t'asseoir, mais pas sur ce tronc-là.", "Je ronfle ? Moi ? Jamais. C'est la jungle qui ronfle.", "Vegeta crie tous les matins à six heures. Un jour je vais crier plus fort."], "Pas de souci"],
    ["ginette", "Ginette", ["Ma voisine a disparu. « Partie à la capitale », qu'ils disent. Elle avait quatre-vingt-sept ans.", "Mon petit, tu as une tête à faire des bêtises. Je te surveille.", "Essuie tes pieds avant d'entrer chez les gens. Même chez Brokk. Surtout chez Brokk.", "Tu manges assez ? Tu as l'air maigre. Mange une carotte. Pas des frites, une carotte."], "Oui madame"],
    ["monique", "Monique", ["On dit qu'il y a une porte secrète à la Mairie. On dit aussi que Gribbs est honnête. On dit beaucoup de choses.", "Ah, vous voilà, vous. On parle de vous à la fontaine, vous savez.", "Pas en bien, pas en mal… On attend de voir.", "Ginette dit que vous êtes poli. Moi je trouve que vous avez un drôle de chapeau."], "On verra bien"],
    ["martine", "Martine", ["Le Président vient faire des discours chaque matin. Le soir, la Mairie est fermée, mais il y a de la lumière… en bas.", "Le Président passe sur la place tous les jours. Ne vous approchez pas trop : ses gardes n'ont aucun humour.", "J'ai voté pour le Hejj. Il n'était pas candidat. J'ai voté quand même.", "Si vous voyez Vegeta, dites-lui que sa coupe est ridicule. De ma part."], "Merci du conseil"],
    ["thierry", "Thierry", ["Quand les Pillards arrivent, la garde du Président protège… le Président. Nous, on se débrouille avec des fourches.", "Moi, j'étais là avant l'avion, avant tout le monde. Faut faire ses preuves, ici.", "Les preuves, c'est simple : un pont, un port, et une fontaine qui marche.", "Tu veux un conseil ? Le blackjack, c'est la banque qui gagne. Toujours. Sauf moi, une fois."], "Je ferai mes preuves"],
  ];
  for (const [fichier, nom, textes, reponse] of habitants) {
    pnj({ fichier, nom, couleur: "gray", objectifs: balade(), dialogues: [
      { label: "default", titre: nom, texte: textes, boutons: [BYE(reponse)] },
    ] });
  }

  // ------------------------------------------------------------ le Président de Beauvais et sa garde rapprochée
  pnj({ fichier: "macron", nom: "Macron, Président de Beauvais", couleur: "gold", objectifs: balade(0.4), tags: ["cabane_macron"], dialogues: [
    { label: "default", titre: "Le Président", ftb: "histoire/president", texte: [
      "Mes chers compatriotes de Beauvais ! Je suis votre Président. Et votre maire. Et, en même temps, un peu les deux.",
      "Du travail ? Je traverse la place et je vous en trouve ! Mehdi, au Grand Bazar, en a plein.",
      "C'est notre projeeeet ! … Pardon. Je m'emporte quand il y a du monde.",
      "Je suis le maître des horloges. Celle de la Grande Cabane retarde de cinq minutes, mais c'est voulu.",
      "Beauvais sera une start-up nation. Une jungle-up nation. Notez-le, Mehdi, notez.",
      "Les gens qui ne sont rien, ça n'existe pas à Beauvais. Ici, même Gribbs est quelque chose. On ne sait pas quoi.",
      "Le blackjack coûte un pognon de dingue. Et pourtant, les gens jouent. C'est fascinant.",
      "Pensez printemps ! … Ici c'est la jungle, il fait toujours printemps. Ça marche à tous les coups.",
      "Qu'ils viennent me chercher ! … Non, les gardes, rasseyez-vous, c'était une image.",
      "Poudre de perlimpinpin ! Ovsie m'en a vendu un sac. Ça ne fait rien du tout.",
      "Gaulois réfractaire ? Vous ? Non… Vous avez plutôt une tête de Beauvaisien coopératif.",
      "Tout va très bien à Beauvais. Grâce à moi. Répétez après moi : tout va très bien.",
      "Les Pillards du Sud ? Je m'en occupe. Personnellement. Enfin, ma garde s'en occupe. Enfin… on y pense.",
      "Ceux qui disparaissent ? Ils sont partis travailler à la capitale. C'est une chance pour eux.",
      "La Mairie est fermée la nuit. Pour votre sécurité. Ne cherchez pas à comprendre.",
      "Les Sept Reliques ? Des superstitions de vieux. Laissez-les où elles sont. Très loin.",
      "C'est notre projet ! Le projet AUG… le projet. Juste le projet. Circulez.",
    ], boutons: [{ texte: "Et vos gardes ?", vers: "gardes" }, { texte: "Votre projet ?", vers: "projet" },
      { texte: "Où est Pierre ?", vers: "pierre" }, BYE("Au revoir, Monsieur le Président")] },
    { label: "gardes", titre: "La garde", texte: [
      "Ma garde rapprochée : quatre guerriers d'élite de la jungle. Je vous le dis avec bienveillance : ne me touchez pas.",
      "Ils sont invincibles. Je leur ai demandé comment. Ils ont dit : « en même temps ». Je n'ai pas compris non plus.",
    ], boutons: [RETOUR, BYE("Compris")] },
    { label: "projet", titre: "Le grand projet", texte: [
      "Mon grand projet : un pont jusqu'à la lune. On commence par un pont jusqu'à la rivière. Le Hejj a les plans, dans votre livre de quêtes.",
      "Faire de Beauvais la plus grande ville de la jungle. Avec un port, une gare, et un rond-point. Surtout un rond-point.",
    ], boutons: [RETOUR, BYE("Ambitieux")] },
    { label: "pierre", titre: "Le Président", texte: [
      "Pierre ? Quel Pierre ? Ah, ce Pierre-là. Parti travailler à la capitale. Une belle opportunité. Je l'ai personnellement… accompagné.",
      "Pierre… Pierre… Il y a tellement de Pierre. Celui à la pétition ? Ah. Oui. Il réfléchit. Longuement. Très profondément. Circulez.",
    ], boutons: [RETOUR, BYE("Hmm…")] },
  ] });
  for (let i = 1; i <= 4; i++) {
    pnj({ fichier: "garde_president_" + i, nom: "Garde présidentiel", couleur: "dark_red", peau: "garde_" + i, voix: "garde_president",
      tags: ["cabane_garde"], mains: ["mowziesmobs:spear", null],
      objectifs: `{Type:"FOLLOW_ENTITY_BY_UUID",Prio:3,SpeedModifier:1.1,TargetEntityUUID:${uuid("macron")},StartDistance:5.0f,StopDistance:2.5f},{Type:"LOOK_AT_PLAYER",Prio:7},{Type:"LOOK_RANDOM_AROUND",Prio:8}`,
      dialogues: [{ label: "default", titre: "Garde", texte: [
        "Circulez. Le Président ne reçoit pas sans rendez-vous.",
        "Si vous levez la main sur lui, vous aurez affaire à nous quatre.",
        "…",
        "Je ne parle pas pendant le service. … Zut.",
      ], boutons: [BYE("Pardon")] }] });
  }
}
