// Les missions des habitants de Beauvais : chacun propose les siennes dans sa conversation (fr.cabane.pnj.Missions).
// Appelé par generer.mjs ; écrit data/cabane/missions/<id>.json.
//   qui : le PNJ qui la donne et à qui on la rend ; apres : les missions à finir avant ; quand : -1 tant que le Président
//   règne, 1 une fois Beauvais libéré ; repetable : minutes avant de pouvoir la refaire ; donne : objets remis en l'acceptant ;
//   ftb : la quête du livre cochée quand on la rend ; partie : la partie de l'histoire que l'hôte doit avoir lancée
//   (/histoire partie 2 : le secret de la Mairie). Ce qui suit une mission de la partie 2 (apres) attend forcément aussi.
// Objectifs : apporter (pris en rendant la mission), avoir (gardé), tuer, parler (une question à poser à un autre PNJ :
// il répond, et peut prendre ou donner un objet), succes (un succès du jeu), evenement (« contrat », « mcbeauvais »).
import fs from "node:fs";
import { ftb } from "./pnj.mjs";

const apporter = (cible, nombre = 1) => ({ type: "apporter", cible, nombre });
const avoir = (cible, nombre = 1) => ({ type: "avoir", cible, nombre });
const tuer = (cible, nombre, texte) => ({ type: "tuer", cible, nombre, ...(texte ? { texte } : {}) });
const parler = (cible, question, reponse, plus = {}) => ({ type: "parler", cible, question, reponse, ...plus });
const succes = (cible, texte) => ({ type: "succes", cible, texte });
const evenement = (cible, nombre, texte) => ({ type: "evenement", cible, nombre, texte });
const argent = (n, objets = [], xp = 0) => ({ argent: n, ...(objets.length ? { objets } : {}), ...(xp ? { xp } : {}) });

// les familles de monstres, mods compris : Creeper Overhaul et Enderman Overhaul remplacent ceux de chaque biome
const liste = (...ids) => ids.join("|");
const ZOMBIES = liste("minecraft:zombie", "minecraft:zombie_villager", "minecraft:husk", "minecraft:drowned");
const SQUELETTES = liste("minecraft:skeleton", "minecraft:stray", "minecraft:bogged");
const ARAIGNEES = liste("minecraft:spider", "minecraft:cave_spider");
const CREEPERS = liste("minecraft:creeper", ...["jungle", "bamboo", "desert", "badlands", "hills", "savannah", "mushroom", "swamp", "dripstone",
  "cave", "dark_oak", "spruce", "beach", "snowy", "ocean", "birch"].map((b) => `creeperoverhaul:${b}_creeper`));
const ENDERMEN = liste("minecraft:enderman", ...["badlands", "cave", "coral", "crimson_forest", "dark_oak", "desert", "end", "end_islands",
  "flower_fields", "ice_spikes", "mushroom_fields", "nether_wastes", "savanna", "snowy", "soulsand_valley", "swamp", "warped_forest",
  "windswept_hills"].map((b) => `endermanoverhaul:${b}_enderman`));
const CROCODILES = liste("alexsmobs:crocodile", "alexsmobs:caiman", "naturalist:alligator");
const MUTANTS = liste("mutantmonsters:mutant_zombie", "mutantmonsters:mutant_skeleton", "mutantmonsters:mutant_creeper", "mutantmonsters:mutant_enderman");
const CAVERNES = liste("alexsmobs:centipede_head", "creeperoverhaul:cave_creeper", "creeperoverhaul:dripstone_creeper", "endermanoverhaul:cave_enderman");
const NETHER = liste("alexsmobs:soul_vulture", "alexsmobs:crimson_mosquito", "alexsmobs:bone_serpent", "minecraft:hoglin", "minecraft:ghast",
  "minecraft:magma_cube", "minecraft:piglin_brute", "minecraft:wither_skeleton", "endermanoverhaul:nether_wastes_enderman",
  "endermanoverhaul:crimson_forest_enderman", "endermanoverhaul:warped_forest_enderman", "endermanoverhaul:soulsand_valley_enderman");
const DESERT = liste("minecraft:husk", "alexsmobs:rattlesnake", "alexsmobs:guster", "naturalist:desert_scorpion", "creeperoverhaul:desert_creeper",
  "creeperoverhaul:badlands_creeper", "endermanoverhaul:desert_enderman", "endermanoverhaul:badlands_enderman");
const MER = liste("minecraft:drowned", "alexsmobs:skelewag", "minecraft:guardian", "creeperoverhaul:ocean_creeper", "creeperoverhaul:beach_creeper",
  "endermanoverhaul:coral_enderman");

const MISSIONS = {
  // ------------------------------------------------------------ l'arrivée : le Hejj fait faire le tour du village
  tour_du_village: { qui: "gardien", ordre: 0, titre: "Le tour du village", ftb: ftb("village/accueil"),
    pitch: "Avant tout, va saluer ceux qui font tourner Beauvais. Chacun te dira ce qu'il a à offrir. Marcel au feu de camp, Mouloud au Grand Bazar, Mehdi au tableau des contrats, et Brokk à la forge.",
    objectifs: [
      parler("conteur", "Le Hejj m'envoie vous saluer.", "Le Hejj t'envoie ? Alors assieds-toi. Ici, au feu de camp, je raconte l'histoire de Beauvais à ceux qui veulent l'entendre. Elle est longue, et elle n'est pas finie. Demande-moi de te la raconter, quand tu veux."),
      parler("marchand", "Le Hejj m'envoie vous saluer.", "Un ami du Hejj ? Alors tu es mon ami ! Mon frère ! Ici, au Grand Bazar, téléphone, parachute, étal pour vendre tes trucs… Tout est à vendre. Prix d'ami. Les mêmes, mais je souris."),
      parler("pole_emploi", "Le Hejj m'envoie vous saluer.", "Enchanté. Les contrats sont sur le tableau, derrière moi : bois, fer, blé… Ils changent toutes les heures et le village paie bien. Je vous ouvre un dossier. Il manquera une pièce. Il en manque toujours une."),
      parler("forgeron", "Le Hejj m'envoie vous saluer.", "CLANG. Le vieux t'envoie. Bien. Brokk forge ce que personne sait forger : les armes des grands monstres. Toi rapporter matériaux, toi payer. Toi pas toucher l'enclume."),
    ],
    rappel: "Marcel, Mouloud, Mehdi et Brokk. Prends ton temps : un village, ça se découvre.",
    merci: "Te voilà Beauvaisien. Enfin… presque. Les gens se méfient encore, mais ils t'ont vu. C'est un début. Tiens, de quoi manger.",
    recompense: argent(200, [["minecraft:cooked_beef", 4]]) },

  // ------------------------------------------------------------ Sung Jin-woo : le portail de rang S (seulement quand l'hôte a lancé
  // l'événement, /cabane evenement jinwoo). Chaque étape compte pour tout le groupe (donjon/Donjon, Enigmes, Boss).
  portail_rang_s: { qui: "jinwoo", ordre: 0, evenement_hote: "jinwoo", titre: "Le portail de rang S",
    pitch: "Un portail de rang S s'est ouvert au-dessus de l'avenue. Si personne n'y entre, ce qu'il y a dedans finira par sortir. Entrez-y, trouvez le chemin, ouvrez la porte des sceaux, et abattez le maître du donjon. Venez à cinq. Et revenez vivants : c'est un ordre.",
    objectifs: [
      evenement("portail", 1, "Entrer dans le portail de rang S"),
      evenement("sceaux", 1, "Ouvrir la porte des sceaux"),
      evenement("ugrak", 1, "Vaincre le boss du donjon"),
    ],
    rappel: "Le portail est toujours ouvert. Regroupez-vous près de moi, et dites « On y va ».",
    merci: "Vous êtes revenus. Tous ? … Bien. Un maître de donjon de rang S. Pas mal, pour des gens tombés d'un avion. Vous avez gagné un niveau, et pas seulement sur le papier. Tenez. Et reposez-vous : des portails, il y en aura d'autres.",
    recompense: argent(300, [["minecraft:golden_apple", 2]], 10) },

  // ------------------------------------------------------------ « Où est Pierre ? » : Sylvie cherche son mari
  ou_est_pierre: { qui: "sylvie", ordre: 1, quand: -1, partie: 2, titre: "Où est Pierre ?",
    pitch: "Excusez-moi… Vous n'auriez pas vu mon mari ? Pierre. Il y a trois semaines, il est allé à la Mairie avec une pétition contre les Pillards. Le soir, il n'est pas rentré. Le Président dit qu'il est « parti travailler à la capitale ». Pierre ne serait jamais parti sans embrasser Lucas. Jamais. Je vous en supplie, demandez autour de vous : son frère Jean, Martine qui habite en face de la Mairie… et Gribbs. Gribbs entend tout.",
    objectifs: [
      parler("jean", "Sylvie cherche Pierre… ton frère.", "Pierre ? Mon petit frère… Il est entré à la Mairie un mardi soir, sa pétition sous le bras. Je l'ai attendu devant jusqu'à minuit. Il n'est jamais ressorti. Le lendemain matin, son chapeau était posé sur les marches. Le Président m'a souri et m'a dit : « Il est parti à la capitale. » Quelle capitale ? Pierre déteste les voyages. Il a le mal de mer sur une barque."),
      parler("martine", "Tu as vu Pierre, le soir où il a disparu ?", "Le soir où Pierre a disparu ? Oui… La Mairie était fermée, mais il y avait de la lumière en bas. Sous la terre. Et vers minuit, j'ai entendu quelqu'un crier « Sylvie ! ». J'ai cru que je rêvais. Je n'ai rien dit à personne. J'ai eu peur des gardes. Pardonnez-moi."),
      parler("cousin", "Gribbs, tu sais quelque chose sur Pierre ?", "Pour Pierre, c'est gratuit. Pierre m'achetait mes cailloux peints, lui. Écoute bien : dans le bureau du Président, il y a une bibliothèque qui sonne creux. Une. Si tu cliques sur la bonne, elle pivote. Et derrière, ça descend. Loin. Moi j'y suis pas allé. Moi je suis beau, pas courageux."),
    ],
    rappel: "Jean, Martine, Gribbs… Quelqu'un a forcément vu quelque chose.",
    merci: "Sous la Mairie… Une porte secrète… Mon Dieu. Il est peut-être encore vivant. Je ne peux pas y aller, les gardes me connaissent. Mais vous… vous, vous pourriez.",
    recompense: argent(200) },
  sous_la_mairie: { qui: "sylvie", ordre: 2, quand: -1, apres: ["ou_est_pierre"], titre: "Sous la Mairie",
    pitch: "Trouvez ce passage, je vous en prie. Entrez dans la Mairie, trouvez la bibliothèque qui pivote, et descendez. Si Pierre est là-dessous… dites-lui que Lucas et moi, on l'attend. Tous les soirs. Devant cette porte.",
    objectifs: [
      succes("cabane:histoire/laboratoire", "Trouver le passage secret sous la Mairie"),
      parler("prisonnier", "Pierre ? Sylvie et Lucas vous cherchent !", "Sylvie… Lucas… Ils vont bien ? Ils sont dehors, devant la Mairie ? Tous les soirs ?… (il se cache le visage un moment) … Écoutez. Je ne peux pas sortir maintenant : s'il voit ma cellule vide, il saura. Prenez ça. Je l'écris depuis des semaines, au charbon, sur ce qui me reste de papier. Donnez-la à Sylvie. Et dites à Lucas que son papa tient bon.", { donne: "cabane:lettre_pierre" }),
      apporter("cabane:lettre_pierre"),
    ],
    rappel: "Il est en bas, n'est-ce pas ? Je le sens. Trouvez-le.",
    merci: "(Sylvie déplie la lettre. Ses mains tremblent.)\n« Ma Sylvie. Je suis vivant. Ils m'appellent le Sujet 07. Ils me font boire une potion verte, mais je la recrache dès qu'ils ont le dos tourné. Ne viens pas : c'est trop dangereux. Dis à Lucas que je reviendrai, et que je lui apprendrai à pêcher, comme promis. Je vous aime. — Pierre. »\n… Il est vivant. Il est VIVANT. Merci… merci. Lucas ! Lucas, viens voir !",
    recompense: argent(600, [["minecraft:golden_apple", 2]], 3) },
  le_dessin: { qui: "lucas", ordre: 3, quand: -1, apres: ["sous_la_mairie"], titre: "Le dessin de Lucas",
    pitch: "C'est vrai que mon papa est vivant ? Maman pleure, mais c'est des larmes contentes, elle a dit. Tu peux lui donner mon dessin ? C'est lui, avec une cape, parce que c'est un super-héros. Et le soleil, c'est pour qu'il ait pas froid dans le noir.",
    donne: [["cabane:dessin_lucas", 1]],
    objectifs: [
      parler("prisonnier", "Lucas vous a fait un dessin.", "Un dessin ? … C'est moi, là, avec la cape ? (il rit, puis il ne rit plus) … Dites-lui que je le garde sur mon cœur. Dites-lui que les super-héros reviennent toujours. Toujours.", { prend: "cabane:dessin_lucas" }),
    ],
    rappel: "Tu lui as donné ? Il a aimé la cape ?",
    merci: "Il a dit que les super-héros reviennent toujours ? … Je le savais ! Tiens, c'est pour toi : mes plus belles billes. Enfin, des émeraudes, mais moi je dis des billes. Et les cookies de maman : elle dit que c'est pour « le héros de la famille ». C'est toi, le héros. Enfin, un peu papa aussi.",
    recompense: argent(250, [["minecraft:cookie", 12], ["minecraft:emerald", 3]]) },
  ramenez_le: { qui: "sylvie", ordre: 4, apres: ["sous_la_mairie"], quand: -1, titre: "Ramenez-le-nous",
    pitch: "Pierre dit que c'est trop dangereux de le sortir maintenant. Alors il faut abattre celui qui le garde enfermé. Marcel dit que vous êtes ceux de la prophétie… Si c'est vrai, alors libérez Beauvais. Libérez-le. Et ramenez-le-nous.",
    objectifs: [succes("cabane:histoire/liberation", "Vaincre le Président Augmenté (livre : L'Histoire de Beauvais)")],
    rappel: "Le livre de quêtes vous guidera : « L'Histoire de Beauvais ». Les reliques, la clé, la Salle Zéro… Je vous fais confiance.",
    merci: "Il est rentré. Il est là, au feu de camp, avec Lucas sur les épaules. (Elle vous serre dans ses bras, très fort.) Beauvais vous doit tout. Nous, on vous doit Pierre.",
    recompense: argent(3000, [["minecraft:diamond", 4]], 5) },

  // ------------------------------------------------------------ les habitants (avant dans le livre, « Les gens de Beauvais »)
  jean: { qui: "jean", titre: "Jean se méfie", pitch: "« Encore un de l'avion… » Bon. Si tu veux que je te fasse confiance, apporte-moi du poisson grillé. Beaucoup. Je mange quand je suis triste. Et je suis souvent triste, depuis que mon frère est parti.",
    objectifs: [apporter("minecraft:cooked_cod", 16)], merci: "… Il est bon, ce poisson. Bon. Peut-être que tu n'es pas comme les autres. Peut-être.", recompense: argent(300) },
  jean_crocos: { qui: "jean", apres: ["jean"], titre: "Les crocodiles de la rivière",
    pitch: "Je ne peux plus pêcher. Il y a des crocodiles dans la rivière, et ils me regardent. Avec leurs yeux. Trois, ça suffira. Ils comprendront.",
    objectifs: [tuer(CROCODILES, 3, "Vaincre 3 crocodiles, caïmans ou alligators")],
    merci: "Je peux repêcher ! Pierre aurait adoré voir ça. … Il le verra. Je le sais.", recompense: argent(300) },
  jacques: { qui: "jacques", titre: "Le balai de Jacques", pitch: "J'ai cassé mon balai. À force de balayer. Mon chemin est en danger. Il me faut des bâtons et du blé, que je m'en fasse un neuf. Vite, une feuille vient de tomber.",
    objectifs: [apporter("minecraft:stick", 32), apporter("minecraft:wheat", 16)], merci: "Un balai neuf ! Regarde-moi ce chemin. Impeccable. Tu peux marcher dessus. Doucement.", recompense: argent(250) },
  francis: { qui: "francis", titre: "Pas de bruit après minuit", pitch: "Les gens de l'avion font du bruit. Des pas, partout, tout le temps. Il me faut des tapis. Des tapis partout. Pour étouffer les pas. Trente-deux. Marron, pour que ça ne se voie pas.",
    objectifs: [apporter("minecraft:brown_carpet", 32)], merci: "Ah. Le silence. Enfin. Vous voyez, quand vous voulez, vous êtes des gens bien.", recompense: argent(300) },
  louis: { qui: "louis", titre: "Les nuages de Louis", pitch: "Bon… Je regarde les nuages. Toute la journée. Si j'avais une longue-vue, je les verrais de plus près. Ne le dis à personne.",
    objectifs: [apporter("minecraft:spyglass")], merci: "Oh. OH. Celui-là ressemble vraiment à Mouloud. Même la moustache. Merci… Tu peux le dire à personne.", recompense: argent(400) },
  bernard: { qui: "bernard", titre: "Le singe de Bernard", pitch: "Bernard (le singe) a faim. Bernard (moi) n'a plus rien. Le singe adore les baies. Il en mange quarante-huit par jour. Quarante-huit !",
    objectifs: [apporter("minecraft:sweet_berries", 48)], merci: "BERNARD ! À TABLE ! … Il dit merci. Enfin, il crie. Chez lui, c'est merci.", recompense: argent(300) },
  bernard_foliaath: { qui: "bernard", apres: ["bernard"], titre: "Les plantes qui mordent",
    pitch: "Une plante a essayé de manger Bernard. Le singe. Une plante ! Avec des dents ! Dans la jungle, il y en a d'autres : des Foliaaths. Va leur montrer qui mange qui.",
    objectifs: [tuer("mowziesmobs:foliaath", 3, "Vaincre 3 Foliaaths, les plantes carnivores de la jungle")],
    merci: "Bernard (le singe) ressort enfin. Il mord les plantes, maintenant. Toutes. Même les miennes.", recompense: argent(250) },
  gerard: { qui: "gerard", titre: "Le tronc de Gérard", pitch: "Quelqu'un s'est assis sur mon tronc. MON tronc. Il est fichu. Il m'en faut un nouveau. Un beau. Seize bûches de jungle, et je me fais un trône.",
    objectifs: [apporter("minecraft:jungle_log", 16)], merci: "Un trône. Un vrai. Le premier qui s'assoit dessus, je ronfle sur lui.", recompense: argent(250) },
  ginette: { qui: "ginette", titre: "Mange une carotte", pitch: "Tout le monde est trop maigre dans ce village. Je fais une soupe géante. Il me faut des carottes et des patates. Beaucoup. Et ne me ramène pas des frites, petit malin.",
    objectifs: [apporter("minecraft:carrot", 48), apporter("minecraft:potato", 48)], merci: "Voilà. Ce soir, soupe pour tout le monde. Et toi, tu en reprends deux fois. Sans discuter.", recompense: argent(250, [["minecraft:mushroom_stew", 2]]) },
  monique: { qui: "monique", titre: "Les potins de Monique", pitch: "J'entends tout, à la fontaine. Tout. Mais j'oublie. Il me faudrait un livre et une plume, pour tout noter. Absolument tout. Surtout sur toi.",
    objectifs: [apporter("minecraft:writable_book")], merci: "Parfait. Première page : « Les gens de l'avion. Poli. Drôle de chapeau. À surveiller. » Merci, mon chou.", recompense: argent(300) },
  martine: { qui: "martine", titre: "Message pour Vegeta", pitch: "Vegeta crie près de la tour de guet tous les matins. Dis-lui que sa coupe est ridicule. De ma part. Je veux voir sa tête. Enfin, toi tu la verras. Raconte-moi.",
    objectifs: [parler("vegeta", "Martine trouve ta coupe ridicule.", "QUOI ?! Ma coupe tient toute seule, insecte ! SANS GEL ! … Dis à cette femme que le prince des Saiyans ne se laisse pas insulter par… par… Elle a dit ridicule ? Vraiment ridicule ? … Hmph.")],
    merci: "Il a dit « hmph » ? Hahaha ! Il est vexé. Merci, j'ai ma journée.", recompense: argent(200) },
  thierry: { qui: "thierry", titre: "Faire ses preuves", pitch: "Ici, faut faire ses preuves. Les zombies rôdent autour du village la nuit. Nettoie-moi la jungle : vingt zombies. Après, on parlera.",
    objectifs: [tuer(ZOMBIES, 20, "Vaincre 20 zombies")], merci: "Vingt ? Pas mal, pour quelqu'un tombé du ciel. Bon. T'as fait tes preuves. Un peu.", recompense: argent(500) },
  vegeta: { qui: "vegeta", titre: "Ma stupide fierté", pitch: "Ton niveau de puissance est ridicule. Va combattre des monstres : vingt-cinq squelettes et dix creepers. Quand tu reviendras, on en reparlera. Hmph.",
    objectifs: [tuer(SQUELETTES, 25, "Vaincre 25 squelettes"), tuer(CREEPERS, 10, "Vaincre 10 creepers (ceux de la jungle comptent)")],
    merci: "Hmph. Ton niveau de puissance… a un peu augmenté. Un tout petit peu. Ne prends pas la grosse tête. C'est mon rôle.", recompense: argent(1000, [], 5) },
  macron: { qui: "macron", quand: -1, titre: "Traverser la rue", pitch: "Du travail ? Je traverse la rue et je vous en trouve ! Tenez : allez voir Mehdi et remplissez trois contrats. Vous verrez, c'est une chance. Pour vous. Et en même temps pour moi.",
    objectifs: [evenement("contrat", 3, "Remplir 3 contrats chez Mehdi")], merci: "Vous voyez ? Le plein emploi ! Grâce à moi. Notez-le, Mehdi, notez.", recompense: argent(500) },
  mouloud: { qui: "marchand", titre: "Client fidèle", pitch: "Mon ami ! Mouloud aime les clients fidèles. Va chez Jean-Mi, achète un booster, et montre-le-moi. Pourquoi ? Pour que Jean-Mi sache que c'est moi qui t'envoie. Il me doit une commission.",
    objectifs: [avoir("cabane:booster")], merci: "Parfait ! Jean-Mi me doit dix pour cent. Tiens, ta part. Mouloud est honnête. Presque.", recompense: argent(300) },
  gribbs: { qui: "cousin", titre: "Les cailloux peints de Gribbs", pitch: "J'ai besoin de matière première pour mes œuvres d'art. De la terre cuite. Orange et cyan. C'est très important pour l'art. L'art, c'est très important.",
    objectifs: [apporter("minecraft:orange_terracotta", 16), apporter("minecraft:cyan_terracotta", 16)], merci: "Magnifique. Avec ça, je vais peindre des cailloux exceptionnels. Mouloud va être jaloux.", recompense: argent(300) },
  mehdi: { qui: "pole_emploi", titre: "Le dossier complet", pitch: "Pour une fois, votre dossier sera complet. Il me faut : du papier, une étiquette, et une horloge. Pour la ponctualité. C'est important, la ponctualité. Pas pour moi, pour vous.",
    objectifs: [apporter("minecraft:paper", 16), apporter("minecraft:name_tag"), apporter("minecraft:clock")], merci: "Votre dossier est… complet. C'est la première fois. Je vais l'encadrer. Puis le perdre.", recompense: argent(600) },
  grosh: { qui: "orque_1", titre: "Le charbon de Grosh", pitch: "Grosh a… un peu… mangé le charbon de la forge. Brokk pas content. Brokk très pas content. Toi ramener charbon. Vite. Avant que Brokk voit.",
    objectifs: [apporter("minecraft:coal", 64)], merci: "Brokk a rien vu. Grosh sauvé. Grosh te doit une vie. Ou un charbon. Grosh choisit charbon.", recompense: argent(400) },
  mog: { qui: "orque_2", titre: "Plus chaud !", pitch: "Mog souffle, souffle, souffle. Mais le feu veut plus. Toi ramener bâtons de blaze. Huit. Avec ça, le feu de Brokk brûlera comme le Nether.",
    objectifs: [apporter("minecraft:blaze_rod", 8)], merci: "PLUS CHAUD ! Ça brûle ! Mog content. Mog a plus de sourcils, mais Mog content.", recompense: argent(450) },
  urga: { qui: "orque_3", titre: "Les lingots d'Urga", pitch: "Urga porte des lingots. Urga veut porter plus de lingots. Fer et or. Pour montrer à Grosh qui est la meilleure apprentie.",
    objectifs: [apporter("minecraft:iron_ingot", 32), apporter("minecraft:gold_ingot", 8)], merci: "Urga porte tout. D'une main. Grosh pleure. Urga contente.", recompense: argent(600) },
  brokk_fer: { qui: "forgeron", titre: "Du fer pour Brokk", pitch: "Brokk veut du fer. Vingt-quatre lingots. Pour les outils des nouveaux. Les nouveaux cassent tout.",
    objectifs: [apporter("minecraft:iron_ingot", 24)], merci: "Bon fer. Brokk content. Brokk forge des pioches pour les nouveaux. Ils les casseront.", recompense: argent(350) },
  brokk_cavernes: { qui: "forgeron", apres: ["brokk_fer"], titre: "Les mille-pattes de la mine",
    pitch: "Les mineurs de Brokk ne descendent plus. Il y a des mille-pattes géants, en bas. Grands comme Grosh. Plus méchants que Grosh. Va les écraser.",
    objectifs: [tuer("alexsmobs:centipede_head", 3, "Vaincre 3 mille-pattes des cavernes")],
    merci: "Les mineurs redescendent. Grosh aussi. Grosh avait peur. Grosh dira que non.", recompense: argent(300) },
  brokk_geant: { qui: "forgeron", apres: ["brokk_fer"], titre: "Le géant de fer", pitch: "Sous la terre, il y a un géant de fer. Le Wroughtnaut. Son armure, c'est le meilleur métal du monde. Va le battre. Brokk veut savoir que c'est possible. Brokk veut le métal.",
    objectifs: [tuer("mowziesmobs:ferrous_wroughtnaut", 1, "Vaincre le Wroughtnaut, le géant de fer")], merci: "Tu as battu le géant ?! … Brokk respecte. Brokk respecte beaucoup. Tiens. Du fer, du vrai. Et la forge te fera un prix d'ami. Non. Brokk rigole. Pas de prix d'ami.",
    recompense: argent(2500, [["minecraft:iron_block", 4]], 8) },
  ovsie: { qui: "fee", titre: "La couronne d'Ovsie", pitch: "Ma couronne de fleurs a fané ! Je veux des coquelicots et des pissenlits. Seize de chaque. S'il te plaît, s'il te plaît, s'il te plaît !",
    objectifs: [apporter("minecraft:poppy", 16), apporter("minecraft:dandelion", 16)], merci: "Elle est magnifique ! Je suis magnifique ! Tiens, un petit sort pour toi. Il brille.", recompense: argent(300, [["minecraft:glow_berries", 8]]) },
  nyota: { qui: "guerisseuse", titre: "Les remèdes de Nyota", pitch: "Les explorateurs partent loin et reviennent en morceaux. Il me faut de quoi les soigner : des carottes dorées et des baies lumineuses.",
    objectifs: [apporter("minecraft:golden_carrot", 8), apporter("minecraft:glow_berries", 16)], merci: "Avec ça, je soignerai même les plus têtus. Même Vegeta. Surtout Vegeta.", recompense: argent(500) },
  karim: { qui: "cartographe", titre: "Une carte pour Karim", pitch: "Chouf ! Je veux une carte des environs dessinée par toi. On verra si tu sais tenir une boussole. Une carte remplie, pas une feuille blanche, hein.",
    objectifs: [apporter("minecraft:filled_map")], merci: "Pas mal ! Le nord est en haut, c'est déjà ça. Je l'accroche sur le grand panneau. Dans un coin. En petit.", recompense: argent(400) },
  garde_casque: { qui: "garde_est", titre: "Le casque du garde", pitch: "Le singe de Bernard m'a volé mon casque. Encore. Je ne peux pas monter la garde tête nue, c'est le règlement. Trouve-m'en un en fer, je te revaudrai ça.",
    objectifs: [apporter("minecraft:iron_helmet")], merci: "Parfait, il me va. Si tu vois le singe avec l'ancien, ne le provoque pas. Il est armé. D'un casque.", recompense: argent(250) },
  garde_torches: { qui: "garde_ouest", titre: "Des torches pour la ronde", pitch: "La nuit, la ronde autour de la palissade se fait dans le noir complet. Trente-deux torches, et plus personne ne se cogne dans les arbres. Surtout moi.",
    objectifs: [apporter("minecraft:torch", 32)], merci: "Ah, la lumière ! Je vois mes pieds. Je n'avais pas vu mes pieds depuis des mois.", recompense: argent(150) },
  marcel_dossier: { qui: "conteur", partie: 2, titre: "Le village doit savoir", pitch: "On dit qu'il existe un dossier, sous la Mairie. Le « Projet Augmenté ». Si tu le trouves, apporte-le-moi. Pas pour le garder : pour le lire, au feu de camp, à tout le monde. Le village doit savoir.",
    objectifs: [avoir("cabane:dossier_secret")], merci: "(Marcel lit longtemps, sans rien dire.) … La potion verte. Les Pillards payés. La Salle Zéro. Tout est là. Ce soir, je ne raconterai pas une histoire. Je raconterai la vérité. Garde-le précieusement : c'est la preuve.",
    recompense: argent(500, [], 3) },
  jean_mi: { qui: "vendeur_cartes", titre: "Un client sérieux", pitch: "Tu veux que je te prenne au sérieux ? Range tes cartes dans un classeur. Un vrai collectionneur a un classeur. Montre-moi le tien.",
    objectifs: [avoir("cabane:classeur")], merci: "Un classeur. Bien. Tu n'es plus un touriste. Tu es… un débutant. C'est un progrès. Tiens, un booster. Ne l'ouvre pas devant moi, je ne supporte pas les cris.",
    recompense: argent(50, [["cabane:booster", 1]]) },
};

// ------------------------------------------------------------ les quêtes de la semaine
// Chaque lundi (heure de Paris), le Hejj change de thème : les mêmes quêtes pour tout le monde, et chacun fait les siennes.
// Les thèmes reviennent chacun leur tour, dans cet ordre. bonus : la prime de qui les rend toutes dans la semaine.
const SEMAINES = [
  { id: "jungle", nom: "La semaine de la jungle", bonus: 800,
    texte: "La jungle autour de Beauvais se réveille. Les plantes mordent, les creepers se cachent dans les feuilles, et les crocodiles remontent la rivière. Cette semaine, on la remet à sa place.",
    quetes: [
      { titre: "Les plantes qui mordent", pitch: "Les Foliaaths, ces fleurs géantes avec des dents, poussent jusqu'à la palissade. Arrache-en cinq.",
        objectifs: [tuer("mowziesmobs:foliaath", 5, "Vaincre 5 Foliaaths")], merci: "Cinq ! Les enfants peuvent de nouveau cueillir des fleurs. Les normales.", recompense: argent(300) },
      { titre: "Les creepers des feuilles", pitch: "Dans la jungle, les creepers sont verts comme les feuilles. On ne les voit qu'au dernier moment. Douze.",
        objectifs: [tuer(CREEPERS, 12, "Vaincre 12 creepers")], merci: "Plus une explosion dans les bananiers. Merci.", recompense: argent(250) },
      { titre: "Le cacao", pitch: "Ginette veut faire des cookies pour tout le village. Il lui faut du cacao : trente-deux fèves.",
        objectifs: [apporter("minecraft:cocoa_beans", 32)], merci: "Ça sent le cookie jusqu'à la Grande Cabane. Ginette dit qu'il n'y en aura pas pour toi. Elle ment.", recompense: argent(200) },
    ] },
  { id: "chasse", nom: "La semaine des chasseurs", bonus: 750,
    texte: "Les nuits sont mauvaises cette semaine. Les monstres rôdent autour de la palissade, et les gardes ne dorment plus. Va chasser, et reviens me le raconter.",
    quetes: [
      { titre: "Nettoyer la jungle", pitch: "Les zombies sortent de partout. Vingt-cinq, et les gardes pourront dormir un peu.",
        objectifs: [tuer(ZOMBIES, 25, "Vaincre 25 zombies")], merci: "Les gardes ont dormi pour la première fois depuis des semaines. Ils ronflent, maintenant.", recompense: argent(250) },
      { titre: "Les archers de la nuit", pitch: "Les squelettes tirent sur tout ce qui bouge. Même sur les poules. Vingt.",
        objectifs: [tuer(SQUELETTES, 20, "Vaincre 20 squelettes")], merci: "Plus une flèche dans les poules. Ginette te remercie. Les poules aussi.", recompense: argent(250) },
      { titre: "Toiles et venin", pitch: "Les araignées tissent jusque sur la Grande Cabane. Chasse-les, et rapporte leur fil : on en fera des filets.",
        objectifs: [tuer(ARAIGNEES, 15, "Vaincre 15 araignées"), apporter("minecraft:string", 16)], merci: "Avec ce fil, on pêchera mieux. Et on dormira mieux.", recompense: argent(250) },
    ] },
  { id: "mine", nom: "La semaine de la mine", bonus: 900,
    texte: "Brokk n'a plus rien à forger, et quand Brokk s'ennuie, il tape sur les murs. Descends à la mine, rapporte du minerai, et fais attention à ce qui vit en bas.",
    quetes: [
      { titre: "Le fer de Brokk", pitch: "Trente-deux lingots de fer et trente-deux charbons. Brokk arrêtera de taper sur les murs.",
        objectifs: [apporter("minecraft:iron_ingot", 32), apporter("minecraft:coal", 32)], merci: "Brokk tape sur l'enclume, maintenant. C'est mieux.", recompense: argent(300) },
      { titre: "Les bêtes des cavernes", pitch: "En bas, il y a des mille-pattes géants, des creepers des grottes et des endermen qui vivent sous terre. Cinq, pour que les mineurs redescendent.",
        objectifs: [tuer(CAVERNES, 5, "Vaincre 5 bêtes des cavernes (mille-pattes, creepers des grottes…)")], merci: "Les mineurs redescendent. En chantant, cette fois.", recompense: argent(300) },
      { titre: "Ce qui brille", pitch: "Tout au fond, il y a ce qui brille. Deux diamants et du lapis, pour les outils des explorateurs.",
        objectifs: [apporter("minecraft:diamond", 2), apporter("minecraft:lapis_lazuli", 16)], merci: "Ça brille. Ne le dis pas à Gribbs.", recompense: argent(350) },
    ] },
  { id: "nether", nom: "La semaine du Nether", bonus: 1000,
    texte: "Mog dit que le feu de la forge s'éteint. Il veut du vrai feu : celui du Nether. Je n'aime pas qu'on y aille… mais Mog pleure, et Mog qui pleure, ça fait de la fumée.",
    quetes: [
      { titre: "Le feu de Mog", pitch: "Les blazes, dans les forteresses : leur feu ne s'éteint jamais. Bats-en six, et rapporte quatre bâtons pour Mog.",
        objectifs: [tuer("minecraft:blaze", 6, "Vaincre 6 blazes"), apporter("minecraft:blaze_rod", 4)], merci: "Mog souffle sur le feu en chantant. C'est pire.", recompense: argent(350) },
      { titre: "Les bêtes du Nether", pitch: "Vautours des âmes, moustiques cramoisis, serpents d'os, hoglins, ghasts… Le Nether en est plein. Huit.",
        objectifs: [tuer(NETHER, 8, "Vaincre 8 bêtes du Nether")], merci: "Huit ! Tu reviens entier. C'est le plus important. Tu sens le soufre, par contre.", recompense: argent(300) },
      { titre: "Le quartz", pitch: "Le Nether est plein de quartz blanc. Rapporte-en trente-deux : la Grande Cabane mérite un sol qui brille.",
        objectifs: [apporter("minecraft:quartz", 32)], merci: "Blanc comme les dents de Macron. Merci.", recompense: argent(250) },
    ] },
  { id: "mer", nom: "La semaine de la mer", bonus: 800,
    texte: "Jean est triste, et quand Jean est triste, il mange du poisson. En plus, les noyés et les skelewags pillent les pêcheurs. Prends une canne et une épée.",
    quetes: [
      { titre: "Le banquet de Jean", pitch: "Seize morues cuites et huit saumons cuits. Pour Jean. Et un peu pour nous.",
        objectifs: [apporter("minecraft:cooked_cod", 16), apporter("minecraft:cooked_salmon", 8)], merci: "Jean a mangé. Jean va mieux. Jean a demandé s'il en restait.", recompense: argent(250) },
      { titre: "Les pilleurs de la mer", pitch: "Les noyés, les skelewags (ces poissons-squelettes qui jouent aux pirates) et les gardiens attaquent les pêcheurs. Dix.",
        objectifs: [tuer(MER, 10, "Vaincre 10 monstres de la mer (noyés, skelewags…)")], merci: "Les pêcheurs ressortent. Ils t'ont gardé un poisson. Un petit.", recompense: argent(300) },
      { titre: "Le trésor du fond", pitch: "Les pêcheurs remontent parfois des trésors. Une coquille de nautile : rapporte-m'en une.",
        objectifs: [apporter("minecraft:nautilus_shell", 1)], merci: "Elle chante la mer, quand on la colle à l'oreille. Merci.", recompense: argent(250) },
    ] },
  { id: "desert", nom: "La semaine du désert", bonus: 800,
    texte: "Les caravanes du désert ne passent plus : serpents à sonnette, scorpions, momies, tornades de sable vivantes… Cette semaine, on rouvre la route.",
    quetes: [
      { titre: "La route des caravanes", pitch: "Douze créatures du désert : momies, serpents à sonnette, scorpions, gusters, creepers des sables…",
        objectifs: [tuer(DESERT, 12, "Vaincre 12 créatures du désert")], merci: "La route est rouverte. La première caravane t'apporte des dattes. Enfin, du sable. Ils ont pas de dattes.", recompense: argent(300) },
      { titre: "Des cactus pour Ovsie", pitch: "Ovsie veut un jardin de cactus. Elle dit que ça pique, comme elle. Seize.",
        objectifs: [apporter("minecraft:cactus", 16)], merci: "Ovsie a planté ses cactus. Et s'est piquée. Elle est ravie.", recompense: argent(200) },
      { titre: "Du sable pour le verre", pitch: "Pour faire du verre, il faut du sable. Beaucoup : cent vingt-huit.",
        objectifs: [apporter("minecraft:sand", 128)], merci: "Il y a du sable partout dans la Grande Cabane, maintenant. Mais on aura des fenêtres.", recompense: argent(200) },
    ] },
  { id: "betes", nom: "La semaine des grandes bêtes", bonus: 1000,
    texte: "Les anciens parlent de bêtes énormes : des crocodiles qui avalent un homme, des dragons de Komodo, et des monstres qui ont muté. Cette semaine, on vérifie si les anciens disent vrai.",
    quetes: [
      { titre: "Les crocodiles", pitch: "Dans les rivières et les marais : crocodiles, caïmans, alligators. Trois, et la rivière sera à nous.",
        objectifs: [tuer(CROCODILES, 3, "Vaincre 3 crocodiles, caïmans ou alligators")], merci: "Jean va pouvoir repêcher. Il te doit un poisson.", recompense: argent(300) },
      { titre: "Le dragon de Komodo", pitch: "Un lézard grand comme un cheval, qui mord et qui empoisonne. Il y en a dans les forêts chaudes. Deux.",
        objectifs: [tuer("alexsmobs:komodo_dragon|naturalist:komodo_dragon", 2, "Vaincre 2 dragons de Komodo")], merci: "Un dragon ! Enfin, un lézard. Un très gros lézard. Bravo.", recompense: argent(300) },
      { titre: "Le mutant", pitch: "Parfois, la nuit, un monstre a muté : un zombie, un squelette, un creeper ou un enderman énorme. Si tu en croises un, bats-le. Si tu peux.",
        objectifs: [tuer(MUTANTS, 1, "Vaincre un monstre mutant")], merci: "Un mutant ?! Tu es fou. Fou et courageux. Le village chantera ton nom. Faux, mais fort.", recompense: argent(400) },
    ] },
  { id: "recoltes", nom: "La semaine des récoltes", bonus: 600,
    texte: "Cette semaine, Beauvais fait ses réserves. La saison des pluies arrive, et Ginette veut nourrir tout le village. Chacun apporte sa part : toi aussi.",
    quetes: [
      { titre: "Le grenier du village", pitch: "Le grenier sonne creux. Rapporte du blé et des carottes.",
        objectifs: [apporter("minecraft:wheat", 64), apporter("minecraft:carrot", 32)], merci: "Le grenier se remplit. Ginette va pouvoir faire sa soupe géante. Encore.", recompense: argent(200) },
      { titre: "Le four commun", pitch: "Le four du village chauffe pour rien. Apporte du pain : on le partagera au feu de camp.",
        objectifs: [apporter("minecraft:bread", 24)], merci: "Ça sent bon jusqu'à la palissade. Gérard est déjà là, assis sur son tronc.", recompense: argent(150) },
      { titre: "Le marché aux fruits", pitch: "Des fruits pour les enfants et les malades : des tranches de pastèque et des baies sucrées.",
        objectifs: [apporter("minecraft:melon_slice", 32), apporter("minecraft:sweet_berries", 16)], merci: "Le singe de Bernard a déjà essayé de les voler. Deux fois. Merci.", recompense: argent(200) },
    ] },
  { id: "ender", nom: "La semaine de l'Ender", bonus: 1000,
    texte: "Des grandes ombres rôdent dans la jungle, la nuit : les endermen, de toutes les couleurs. Ne les regarde pas dans les yeux… sauf si tu es prêt. Cette semaine, tu es prêt.",
    quetes: [
      { titre: "Les grandes ombres", pitch: "Dix endermen, ceux de la jungle comme ceux d'ailleurs. Regarde-les dans les yeux, et sois plus rapide qu'eux.",
        objectifs: [tuer(ENDERMEN, 10, "Vaincre 10 endermen")], merci: "Dix ! Et tu es encore là. Respect.", recompense: argent(350) },
      { titre: "Les perles", pitch: "Huit perles de l'Ender. Karim dit qu'elles servent à trouver des choses très loin.",
        objectifs: [apporter("minecraft:ender_pearl", 8)], merci: "Karim est content. Karim n'est jamais content. Garde ça pour toi.", recompense: argent(250) },
      { titre: "La poudre", pitch: "Douze creepers, et leur poudre. Avant qu'ils fassent sauter la palissade. Encore.",
        objectifs: [tuer(CREEPERS, 12, "Vaincre 12 creepers"), apporter("minecraft:gunpowder", 16)], merci: "Plus un trou dans la palissade cette semaine. Les gardes t'adorent.", recompense: argent(250) },
    ] },
  { id: "batisseurs", nom: "La semaine des bâtisseurs", bonus: 600,
    texte: "Beauvais grandit. Les nouveaux veulent des maisons, les anciens veulent des murs, et Francis veut des tapis. Rapporte des matériaux : le village construira.",
    quetes: [
      { titre: "Du bois de jungle", pitch: "Soixante-quatre bûches de jungle. Les arbres géants en ont à revendre.",
        objectifs: [apporter("minecraft:jungle_log", 64)], merci: "Du bon bois. Ça fera une belle charpente. Ou un nouveau tronc pour Gérard.", recompense: argent(200) },
      { titre: "De la pierre", pitch: "Des briques de pierre, pour les murs. Soixante-quatre.",
        objectifs: [apporter("minecraft:stone_bricks", 64)], merci: "Avec ça, les murs tiendront. Même face aux Pillards.", recompense: argent(200) },
      { titre: "Des fenêtres", pitch: "Des maisons sans fenêtres, c'est des grottes. Trente-deux blocs de verre.",
        objectifs: [apporter("minecraft:glass", 32)], merci: "Monique va pouvoir regarder chez tout le monde. Je ne sais pas si c'est une bonne nouvelle.", recompense: argent(150) },
    ] },
  { id: "village", nom: "La semaine du village", bonus: 600,
    texte: "Cette semaine, pas de monstres, pas de mine : on fait vivre Beauvais. On travaille ensemble, on s'écoute, on se fait des cadeaux. Même toi.",
    quetes: [
      { titre: "Les contrats de Mehdi", pitch: "Mehdi a trop de contrats et pas assez de bras. Remplis-en trois.",
        objectifs: [evenement("contrat", 3, "Remplir 3 contrats de Mehdi")], merci: "Mehdi a classé ton dossier. Dans la pile « bien ». Elle est petite, cette pile.", recompense: argent(250) },
      { titre: "Prendre des nouvelles", pitch: "Va voir Marcel au feu de camp, et Nyota. Écoute-les. Ils parlent souvent, mais on les écoute rarement.",
        objectifs: [
          parler("conteur", "Le Hejj m'envoie écouter une histoire.", "Assieds-toi. Ce soir, je te raconte comment Beauvais a été fondé : par trois personnes, un singe et une erreur de navigation."),
          parler("guerisseuse", "Le Hejj demande si tu vas bien.", "Le Hejj s'inquiète pour moi ? … Dis-lui que oui. Et dis-lui de manger."),
        ], merci: "Marcel a raconté, Nyota va bien. Tu vois : ça ne coûte rien, d'écouter.", recompense: argent(150) },
      { titre: "Un gâteau pour Ginette", pitch: "C'est l'anniversaire de Ginette. Elle dit que non. C'est oui. Apporte-lui… enfin, apporte-moi un gâteau.",
        objectifs: [apporter("minecraft:cake", 1)], merci: "Ginette a pleuré. Puis elle a dit qu'il manquait du sucre. C'est une réussite.", recompense: argent(150) },
    ] },
];

export function genererMissions({ ecrire }) {
  // on repart de zéro : une mission retirée d'ici ne doit pas rester dans le mod
  fs.rmSync(new URL("../src/main/resources/data/cabane/missions/", import.meta.url), { recursive: true, force: true });
  for (const [id, m] of Object.entries(MISSIONS)) {
    const { recompense, ...reste } = m;
    ecrire(`data/cabane/missions/${id}.json`, { ...reste, recompense });
  }
  let n = Object.keys(MISSIONS).length;
  SEMAINES.forEach(({ quetes, ...semaine }, t) => {
    const { id, nom, texte, bonus } = semaine;
    quetes.forEach(({ recompense, ...q }, i) => {
      ecrire(`data/cabane/missions/semaine_${id}_${i + 1}.json`,
        { qui: "gardien", ordre: 500 + t * 10 + i, semaine: { id, nom, texte, bonus }, ...q, recompense });
      n++;
    });
  });
  return n;
}
