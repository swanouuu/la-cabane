// Les PNJ de Beauvais (Easy NPC) : fichiers data/cabane/pnj/<nom>.snbt, posés par le mod (HubEvents.PNJ).
// Chaque dialogue peut avoir plusieurs textes : Easy NPC en tire un au hasard à chaque fois, ça tourne.
// La voix : SoundData DIALOG_OPEN → cabane:voix.<nom> (les fichiers sont dans voix/, voir le README).

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

const IMMOBILE = `{Type:"MOVE_BACK_TO_HOME",Prio:4,SpeedModifier:0.8},{Type:"LOOK_AT_RESET"},{Type:"LOOK_AT_PLAYER",Prio:7}`;
const balade = (vitesse = 0.55) => `{Type:"RANDOM_STROLL_AROUND_HOME",Prio:5,SpeedModifier:${vitesse}},{Type:"MOVE_BACK_TO_HOME",Prio:4,SpeedModifier:0.8},{Type:"OPEN_DOOR",Prio:6},{Type:"LOOK_AT_PLAYER",Prio:7},{Type:"LOOK_RANDOM_AROUND",Prio:8}`;

export function genererPnj(ecrire) {
  /**
   * fichier, nom, couleur, peau (textures/entity/pnj/<peau>.png), voix (cabane:voix.<voix>), echelle,
   * objectifs, tags, mains ([main droite, main gauche]), dialogues [{label, titre, texte (ou tableau = au hasard), boutons}].
   */
  function pnj({ fichier, nom, couleur, peau = fichier, voix = fichier, echelle, objectifs = IMMOBILE, tags = [], mains, dialogues }) {
    const entrees = dialogues.map((d) => `{
        Label:${snbt(d.label)},
        Name:${snbt(d.titre)},
        Texts:[${[].concat(d.texte).map((t) => `{Text:${snbt(t)}}`).join(",")}],
        Buttons:[${d.boutons.map((b) => `{
          Label:${snbt(b.label)},
          Name:${snbt(b.texte)},
          Type:${snbt(b.vers || b.commande ? "ACTION" : "CLOSE")}${b.vers ? `,
          Actions:[{Type:"OPEN_NAMED_DIALOG",Cmd:${snbt(b.vers)}}]` : b.commande ? `,
          Actions:[{Type:"COMMAND",Cmd:${snbt(b.commande)},ExecAsUser:1b,PermLevel:0},{Type:"CLOSE_DIALOG"}]` : ""}
        }`).join(",")}]
      }`).join(",");
    const objets = mains ? `
  HandItems:[${mains.map((m) => (m ? `{id:${snbt(m)},count:1}` : "{}")).join(",")}],` : "";
    ecrire(`data/cabane/pnj/${fichier}.snbt`, `{
  id:"easy_npc:humanoid",
  UUID:${uuid(fichier)},
  CustomName:${nomJson(nom, couleur)},
  CustomNameVisible:1b,
  PersistenceRequired:1b,
  Tags:[${["cabane_pnj", ...tags].map(snbt).join(",")}],
  EasyNPCVersion:3,
  VariantType:"STEVE",
  SkinData:{Type:"RESOURCE_LOCATION",Texture:${snbt(`cabane:textures/entity/pnj/${peau}.png`)}},${echelle ? `
  ModelData:{Scale:{Root:[${echelle}f,${echelle}f,${echelle}f]}},` : ""}
  SoundData:{SoundDataSet:[{Type:"DIALOG_OPEN",Name:${snbt(`cabane:voix.${voix}`)},Volume:1.0f,Pitch:1.0f,Enabled:1b}]},
  ObjectiveData:{ObjectiveDataSet:[${objectifs}]},${objets}
  ActionData:{ActionEventSet:{ON_INTERACTION:[{Type:"OPEN_DEFAULT_DIALOG"}]}},
  DialogData:{
    Type:"STANDARD",
    DialogDataSet:[${entrees}]
  },
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
    ], boutons: [{ label: "aider", texte: "Comment aider ?", vers: "aider" }, { label: "maison", texte: "Où s'installer ?", vers: "maison" },
      { label: "reliques", texte: "Et les reliques ?", vers: "reliques" }, { label: "bye", texte: "À plus tard" }] },
    { label: "aider", titre: "Agrandir Beauvais", texte: [
      "Le village doit grandir : des maisons, un marché, un pont, un port… Ouvrez votre livre de quêtes, chapitre « Agrandir Beauvais ». Chaque chantier fini, le village vous paie.",
      "Mehdi affiche des contrats toutes les heures au Grand Bazar, et chaque habitant a un petit service à vous demander. Regardez le livre de quêtes : tout y est.",
    ], boutons: [{ label: "bye", texte: "On s'y met !" }] },
    { label: "maison", titre: "Vos maisons", texte: [
      "Le cœur du village appartient aux habitants. Vous, installez-vous juste à côté, hors de l'enceinte : construisez vos maisons, vos fermes, vos usines. Et aidez Beauvais à pousser jusqu'à vous.",
      "Les terrains à vendre, dans le village, sont pour les commerces. Pour vivre, sortez par la grande porte et choisissez un coin de jungle. Pas trop près de Gérard.",
    ], boutons: [{ label: "bye", texte: "Compris" }] },
    { label: "reliques", titre: "Les Sept Reliques", texte: "Sept reliques reposaient sur le comptoir. Elles ont été cachées dans les plus grands donjons du monde, parfois très loin d'ici. Ramenez-les, et le village se réveillera. Karim, au grand panneau, a les cartes.",
      boutons: [{ label: "bye", texte: "J'y vais !" }] },
  ] });

  pnj({ fichier: "cartographe", nom: "Karim le Chouf", couleur: "aqua", peau: "karim", dialogues: [
    { label: "default", titre: "Karim", texte: [
      "Chouf, chouf ! Des cartes, encore des cartes ! Regarde le grand panneau derrière moi : c'est tout le monde connu.",
      "J'ai étudié la magie des cartes pendant quarante ans. Résultat : je sais plier une carte. Dans le bon sens. Parfois.",
      "Chaque relique a sa carte. Fais les quêtes d'indice dans ton livre et je te les donne. Chouf !",
      "Ma barbe ? Elle pousse vers le nord. Comme ça je ne me perds jamais.",
      "Les gens croient que je suis un sorcier. Je suis cartographe. Bon… un peu sorcier aussi.",
    ], boutons: [{ label: "conseil", texte: "Un conseil ?", vers: "conseil" }, { label: "bye", texte: "Merci Karim" }] },
    { label: "conseil", titre: "Conseil", texte: [
      "Les citadelles du ciel et les fonderies sont très loin. Pose des Waystones en chemin, et ne pars jamais sans lit.",
      "Au nord, la neige. Au sud, le désert. À l'est, des volcans. Et ici, la jungle. Prends un manteau ET de l'eau.",
    ], boutons: [{ label: "bye", texte: "Noté !" }] },
  ] });

  pnj({ fichier: "marchand", nom: "Mouloud le Marchand", couleur: "green", peau: "mouloud", dialogues: [
    { label: "default", titre: "Mouloud", texte: [
      "Bienvenue au Grand Bazar ! Ici tout est à vendre. Même le comptoir, si tu payes assez.",
      "Mon ami ! Mon frère ! Mon client préféré ! … C'est quoi ton nom déjà ?",
      "Prix d'ami aujourd'hui. Les prix d'ami, c'est les mêmes, mais je souris en plus.",
      "Des puffs, des boosters, des voitures de police… et un téléphone pour appeler ta mère. Elle s'inquiète.",
      "Mon cousin Gribbs dit qu'il a de meilleurs prix. Mon cousin Gribbs vend des cailloux peints.",
    ], boutons: [{ label: "boutique", texte: "Voir la boutique", commande: "cabane boutique" }, { label: "bye", texte: "À plus" }] },
  ] });

  pnj({ fichier: "cousin", nom: "Gribbs, cousin de Mouloud", couleur: "dark_aqua", peau: "gribbs", dialogues: [
    { label: "default", titre: "Gribbs", texte: [
      "Psst ! Toi ! Tu veux une montre ? … J'ai pas de montre. Mais si t'en veux une, je peux t'en trouver une.",
      "Mouloud, c'est mon cousin. Enfin, cousin… on a le même oncle. Enfin, oncle… c'est un voisin.",
      "Je vends des secrets. Premier secret gratuit : Macron a peur des poules.",
      "Si un garde te demande, tu m'as jamais vu. Si Mouloud te demande, je suis très beau.",
    ], boutons: [{ label: "boutique", texte: "Voir la boutique", commande: "cabane boutique" }, { label: "bye", texte: "Euh… à plus" }] },
  ] });

  pnj({ fichier: "pole_emploi", nom: "Mehdi de Pôle emploi", couleur: "blue", peau: "mehdi", dialogues: [
    { label: "default", titre: "Mehdi", texte: [
      "Bonjour, Pôle emploi, Mehdi, enchanté. Vous avez rendez-vous ? … Non ? Bon. Les contrats sont affichés au-dessus du tableau, ils changent toutes les heures.",
      "Prenez un ticket. Il n'y a pas de tickets. Prenez-en un quand même.",
      "Votre profil m'intéresse : vous savez porter des choses ? Parfait. Le village a besoin de bois, de fer et de blé.",
      "Je travaille ici depuis l'avant-dernier avion. Le dernier, il est en feu, c'est le vôtre.",
      "On recrute ! Clic droit sur le tableau pour livrer, accroupi pour voir le détail. Et souriez, ça compte pour le dossier.",
    ], boutons: [{ label: "contrats", texte: "Voir les contrats", commande: "cabane contrats" }, { label: "dossier", texte: "Et mon dossier ?", vers: "dossier" }, { label: "bye", texte: "Merci Mehdi" }] },
    { label: "dossier", titre: "Votre dossier", texte: [
      "Votre dossier ? Il manque une pièce justificative. Il en manque toujours une.",
      "Votre dossier est en cours de traitement. Il est en cours de traitement depuis mardi. Quel mardi ? Bonne question.",
    ], boutons: [{ label: "bye", texte: "D'accord…" }] },
  ] });

  // ------------------------------------------------------------ Brokk l'ogre et ses orques
  pnj({ fichier: "forgeron", nom: "Brokk le Forgeron", couleur: "red", peau: "brokk", echelle: 1.5, mains: ["minecraft:mace", null], dialogues: [
    { label: "default", titre: "Brokk", texte: [
      "CLANG. Brokk forge. Toi regarde. Toi touche pas l'enclume.",
      "Je sais faire ce que personne d'autre ne sait faire : les armes des grands monstres, les reliques de feu. Rapporte les matériaux, paie le travail.",
      "Mes orques travaillent bien. Sauf Grosh. Grosh mange le charbon.",
      "Un jour, je forgerai une épée si grande qu'il faudra deux villages pour la porter.",
      "Tu as des bras de brindille. Reviens quand tu pourras soulever un lingot.",
    ], boutons: [{ label: "forge", texte: "La forge spéciale", commande: "cabane forge" }, { label: "travail", texte: "Du travail ?", vers: "travail" }, { label: "bye", texte: "À plus, Brokk" }] },
    { label: "travail", titre: "Le travail", texte: "Le Hejj a un livre de quêtes plein de chantiers. Et Mehdi a ses contrats. Moi, je veux du fer. Beaucoup de fer.",
      boutons: [{ label: "contrats", texte: "Voir les contrats", commande: "cabane contrats" }, { label: "bye", texte: "Compris" }] },
  ] });
  const orques = [
    ["orque_1", "Grosh, apprenti de Brokk", "minecraft:iron_pickaxe", ["Grosh pas manger charbon. … Grosh un peu manger charbon.", "Marteau. Enclume. Marteau. Enclume. Grosh aime son travail.", "Toi pas regarder Grosh comme ça."]],
    ["orque_2", "Mog, souffleur de forge", "minecraft:blaze_rod", ["Plus chaud ! Toujours plus chaud ! Brokk aime quand ça brûle.", "Mog souffle sur le feu depuis trois jours. Mog a plus de souffle.", "Attention, ça pique. Tout pique ici."]],
    ["orque_3", "Urga, la porteuse de lingots", "minecraft:iron_ingot", ["Urga porte trois lingots d'une main. Toi, combien ?", "Brokk dit Urga meilleure apprentie. Grosh dit pas vrai. Grosh a tort.", "Pose pas tes pieds sur les lingots, humain."]],
  ];
  for (const [fichier, nom, objet, textes] of orques) {
    pnj({ fichier, nom, couleur: "dark_green", voix: "orque", echelle: 1.15, mains: [objet, null], objectifs: balade(0.4), dialogues: [
      { label: "default", titre: nom.split(",")[0], texte: textes, boutons: [{ label: "bye", texte: "Bon courage" }] },
    ] });
  }

  pnj({ fichier: "fee", nom: "Ovsie la Fée", couleur: "light_purple", peau: "ovsie", echelle: 0.6, objectifs: balade(0.7), dialogues: [
    { label: "default", titre: "Ovsie", texte: [
      "Coucou ! Tu collectionnes les cartes ? Les boosters se vendent chez Mouloud, et un classeur garde toute ta collection.",
      "Je brille la nuit. Le jour aussi, mais personne ne remarque.",
      "Si tu trouves une carte légendaire, je te fais un câlin. Un petit. Je suis petite.",
      "Le casino ? C'est mal. … Tu m'emmènes ?",
    ], boutons: [{ label: "casino", texte: "Et le casino ?", vers: "casino" }, { label: "bye", texte: "Merci Ovsie !" }] },
    { label: "casino", titre: "Le casino", texte: "Roulette, roue de la fortune, blackjack… Mouloud vend les tables : à vous de construire le plus beau casino de la jungle ! Le gérant remplit la caisse, accroupi + clic droit.",
      boutons: [{ label: "bye", texte: "On s'y met" }] },
  ] });

  pnj({ fichier: "guerisseuse", nom: "Nyota la Guérisseuse", couleur: "dark_green", peau: "nyota", dialogues: [
    { label: "default", titre: "Nyota", texte: [
      "Assieds-toi près du feu, voyageur. Ici on raconte les histoires des donjons lointains… et de ceux qui n'en sont jamais revenus.",
      "Une feuille de jungle sur une coupure, et ça repart. Sur une jambe cassée, c'est plus compliqué.",
      "Le Président m'a demandé une tisane pour le courage. Je lui ai donné de l'eau chaude. Ça a marché.",
      "Dors dans un lit avant de partir loin : sinon tu repars de l'avion. Et l'avion brûle.",
    ], boutons: [{ label: "bye", texte: "Merci Nyota" }] },
  ] });

  for (const [cote, peau] of [["est", "garde_porte_1"], ["ouest", "garde_porte_2"]]) {
    pnj({ fichier: "garde_" + cote, nom: "Garde de Beauvais", couleur: "yellow", peau, voix: "garde_" + cote, mains: ["minecraft:trident", null], dialogues: [
      { label: "default", titre: "Garde", texte: [
        "Halte, voyageur ! … Ah, tu viens de l'avion en feu. Entre : le Hejj t'attend devant la Grande Cabane.",
        "Rien à signaler. Sauf un singe qui m'a volé mon casque hier. Si tu le vois, il s'appelle Bernard. Pas l'habitant, le singe.",
        "Je garde cette porte depuis dix ans. Personne n'a jamais essayé d'entrer par le mur. Heureusement.",
        "Le soir, on entend des choses dans la jungle. Le plus souvent c'est Gérard qui ronfle.",
      ], boutons: [{ label: "bye", texte: "Merci !" }] },
    ] });
  }

  // ------------------------------------------------------------ Vegeta, qui s'entraîne près de la tour de guet
  pnj({ fichier: "vegeta", nom: "Vegeta", couleur: "dark_blue", objectifs: IMMOBILE, dialogues: [
    { label: "default", titre: "Vegeta", texte: [
      "Hmph. Ma stupide fierté m'empêche de te dire bonjour. Alors je te dis : dégage, insecte.",
      "Je suis le prince de tous les Saiyans ! … Ici on m'appelle « le monsieur qui crie près de la tour », mais bon.",
      "Ma stupide fierté… voilà ce qui m'a perdu. Ça, et le blackjack de Mouloud.",
      "Ton niveau de puissance ? Ridicule. Même Ovsie te met une raclée.",
      "Kakarot ! … Ah non. C'est toi. Quelle déception.",
      "Je m'entraîne sous une gravité trois cents fois supérieure. Bon, c'est un sac de sable. Mais il est lourd.",
      "Plus de huit mille ?! … Ah, c'est le prix d'une parcelle chez Mouloud. Je m'emporte.",
    ], boutons: [{ label: "fierte", texte: "Pourquoi tant de fierté ?", vers: "fierte" }, { label: "bye", texte: "D'accord, prince…" }] },
    { label: "fierte", titre: "La fierté", texte: [
      "Ma stupide fierté, c'est tout ce qui me reste ! Et ma coupe de cheveux. Elle tient toute seule. Sans gel.",
      "Un prince ne demande jamais son chemin. C'est pour ça que je suis à Beauvais depuis six ans.",
    ], boutons: [{ label: "bye", texte: "Respect, prince" }] },
  ] });

  // ------------------------------------------------------------ les habitants : ils se baladent et se méfient des nouveaux
  const habitants = [
    ["jean", "Jean", ["Hm. Encore un de l'avion. Ici on n'aime pas trop les nouveaux, faut le temps qu'on vous connaisse.", "Mon grand-père a planté cet arbre. Mon père l'a coupé. Moi je le regarde. C'est une tradition.", "Tu marches trop vite. Ici, on marche lentement. Ça énerve les touristes, c'est le but."], "Bonne journée quand même"],
    ["jacques", "Jacques", ["Tu marches sur mon chemin, là. Je l'ai balayé ce matin.", "J'ai balayé ce chemin trois fois aujourd'hui. Trois. Fois.", "Le Président m'a promis une médaille du meilleur balayeur. J'attends toujours."], "Pardon, Jacques"],
    ["francis", "Francis", ["Les gens de l'avion, ils arrivent, ils achètent les terrains, et après ça fait du bruit jusqu'à minuit.", "Je vous ai à l'œil. L'autre œil est sur Gribbs.", "Si vous construisez à côté de chez moi, faites une jolie maison. Pas un truc en terre."], "Promis, on sera sages"],
    ["louis", "Louis", ["Tu cherches quoi ? Le Bazar, c'est par là. La forge, c'est par là. Et moi, je suis occupé.", "Occupé à quoi ? Ça te regarde pas.", "Bon… je regardais les nuages. Celui-là ressemble à Mouloud."], "D'accord, d'accord"],
    ["bernard", "Bernard", ["De mon temps, on n'avait pas de téléphone. On criait. Et ça marchait très bien.", "Mon singe s'appelle Bernard aussi. C'est pratique quand on crie.", "Les jeunes, avec leur Piaf… moi j'ai des vrais piafs. Ils font caca partout."], "C'est noté, Bernard"],
    ["gerard", "Gérard", ["Le feu de camp, c'est MA place. Tu peux t'asseoir, mais pas sur ce tronc-là.", "Je ronfle ? Moi ? Jamais. C'est la jungle qui ronfle.", "Vegeta crie tous les matins à six heures. Un jour je vais crier plus fort."], "Pas de souci"],
    ["ginette", "Ginette", ["Mon petit, tu as une tête à faire des bêtises. Je te surveille.", "Essuie tes pieds avant d'entrer chez les gens. Même chez Brokk. Surtout chez Brokk.", "Tu manges assez ? Tu as l'air maigre. Mange une carotte."], "Oui madame"],
    ["monique", "Monique", ["Ah, vous voilà, vous. On parle de vous à la fontaine, vous savez.", "Pas en bien, pas en mal… On attend de voir.", "Ginette dit que vous êtes poli. Moi je trouve que vous avez un drôle de chapeau."], "On verra bien"],
    ["martine", "Martine", ["Le Président passe sur la place tous les jours. Ne vous approchez pas trop : ses gardes n'ont aucun humour.", "J'ai voté pour le Hejj. Il n'était pas candidat. J'ai voté quand même.", "Si vous voyez Vegeta, dites-lui que sa coupe est ridicule. De ma part."], "Merci du conseil"],
    ["thierry", "Thierry", ["Moi, j'étais là avant l'avion, avant tout le monde. Faut faire ses preuves, ici.", "Les preuves, c'est simple : un pont, un port, et une fontaine qui marche.", "Tu veux un conseil ? Le blackjack, c'est la banque qui gagne. Toujours. Sauf moi, une fois."], "Je ferai mes preuves"],
  ];
  for (const [fichier, nom, textes, reponse] of habitants) {
    pnj({ fichier, nom, couleur: "gray", objectifs: balade(), dialogues: [
      { label: "default", titre: nom, texte: textes, boutons: [{ label: "bye", texte: reponse }] },
    ] });
  }

  // ------------------------------------------------------------ le Président de Beauvais et sa garde rapprochée
  pnj({ fichier: "macron", nom: "Macron, Président de Beauvais", couleur: "gold", objectifs: balade(0.4), tags: ["cabane_macron"], dialogues: [
    { label: "default", titre: "Le Président", texte: [
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
    ], boutons: [{ label: "gardes", texte: "Et vos gardes ?", vers: "gardes" }, { label: "projet", texte: "Votre projet ?", vers: "projet" }, { label: "bye", texte: "Au revoir, Monsieur le Président" }] },
    { label: "gardes", titre: "La garde", texte: [
      "Ma garde rapprochée : quatre guerriers d'élite de la jungle. Je vous le dis avec bienveillance : ne me touchez pas.",
      "Ils sont invincibles. Je leur ai demandé comment. Ils ont dit : « en même temps ». Je n'ai pas compris non plus.",
    ], boutons: [{ label: "bye", texte: "Compris" }] },
    { label: "projet", titre: "Le grand projet", texte: [
      "Mon grand projet : un pont jusqu'à la lune. On commence par un pont jusqu'à la rivière. Le Hejj a les plans, dans votre livre de quêtes.",
      "Faire de Beauvais la plus grande ville de la jungle. Avec un port, une gare, et un rond-point. Surtout un rond-point.",
    ], boutons: [{ label: "bye", texte: "Ambitieux" }] },
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
      ], boutons: [{ label: "bye", texte: "Pardon" }] }] });
  }
}
