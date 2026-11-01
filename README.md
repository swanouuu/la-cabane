# Beauvais — serveur Minecraft entre potes

Survie moddée en **NeoForge 1.21.1** (la même base que CrazyTown S3). Votre avion prend feu au-dessus de la jungle ; vous sautez en parachute et vous atterrissez à **Beauvais**, un village perdu au milieu d'arbres géants. Des PNJ, une grande quête qui envoie dans des donjons partout sur la map, des téléphones avec numéros perso et vocal, un casino, une police, et un président.

Le modpack et notre mod maison s'appellent **SakaiMod**. Le dépôt et le dossier s'appellent encore `la-cabane` / `mod-cabane` : ça ne se voit pas en jeu.

## Aperçu en jeu

Captures prises automatiquement dans le jeu de test (`docs/apercu/`).

![Beauvais vu du ciel](docs/apercu/beauvais-1-vue-du-ciel.jpg)
![Macron et sa garde](docs/apercu/beauvais-2-macron.jpg)
![La forge de Brokk](docs/apercu/beauvais-3-forge.jpg)
![La police](docs/apercu/beauvais-4-police.jpg)
![La roulette](docs/apercu/beauvais-5-roulette.jpg)
![La roue de la fortune](docs/apercu/beauvais-6-roue.jpg)
![Le blackjack](docs/apercu/beauvais-7-blackjack.jpg)
![Les arbres géants autour du village](docs/apercu/beauvais-8-arbres-geants.jpg)
![La forge de Brokk et de ses orques](docs/apercu/beauvais-9-forge-des-orques.jpg)
![Machine à sous, dés et pile ou face en 3D](docs/apercu/beauvais-10-casino-3d.jpg)
![Karim le Chouf](docs/apercu/beauvais-11-karim.jpg)
![Le ticket PENALTY !](docs/apercu/beauvais-12-ticket-penalty.jpg)
![Le booster, carte par carte](docs/apercu/beauvais-13-booster.jpg)

## Comment ça marche

```
Toi (Prism)  ──publier.bat──►  GitHub (pack/)  ◄──au lancement──  Prism de tes potes
                                     ▲
                                     └──────au démarrage──────  Serveur
```

- `pack/` : la liste des mods (format [packwiz](https://packwiz.infra.link)), les configs et le livre de quêtes. C'est la seule chose que tes potes et le serveur téléchargent.
- `mod-cabane/` : notre mod maison (téléphone, village, PNJ, casino, police…).
- `outils/` : les scripts. Les fichiers `.bat` à la racine sont là pour lancer ces scripts en double-cliquant.

## Pour tes potes (une seule fois)

1. Installer [Prism Launcher](https://prismlauncher.org) et se connecter avec son compte Microsoft (il faut posséder Minecraft Java).
2. Importer `SakaiMod.zip` dans Prism (Ajouter une instance → Importer).
3. Lancer. Les mods se téléchargent tout seuls, et à chaque lancement ils se mettent à jour.

Prévoir 8 Go de RAM pour l'instance (réglé par défaut).

## Pour toi

| Fichier | À quoi il sert |
|---|---|
| `installer-outils.bat` | Installe Java 21, Go et packwiz dans `.outils/` (seulement sur un nouveau PC). |
| `compiler.bat` | Compile le mod maison et le met dans le pack. |
| `publier.bat` | Compare les mods de **ton** instance Prism avec le pack, met le pack à jour et l'envoie sur GitHub. `publier.bat --essai` montre seulement ce qui changerait. |
| `construire-instance.bat` | Refait `dist/SakaiMod.zip`, à envoyer aux potes. |
| `tester-en-jeu.bat` | Lance le jeu de développement avec tous les mods du pack et la dernière version du mod maison (compilée au passage). Le compte est un compte de test hors ligne : pas de serveur en ligne avec. |

**Ajouter ou retirer un mod** : fais-le dans ton Prism, puis double-clique sur `publier.bat` **avant** de relancer le jeu. Sinon, au lancement, le pack remet les mods comme avant. Les mods qui ne sont que sur CurseForge doivent être ajoutés à la main :

```bash
cd pack
../.outils/gopath/bin/packwiz.exe curseforge add <nom-du-mod>
```

## Le départ : la salle d'embarquement et `/start`

- Tant que l'hôte n'a pas lancé la partie, tout le monde attend dans la **salle d'embarquement**, au bout de la passerelle de l'avion (mode aventure, on ne casse rien).
- Quand tout le monde est là, l'hôte tape **`/start`** : la porte s'ouvre, chacun se retrouve dans l'avion à 270 blocs de haut, avec un parachute. Il n'y a plus qu'à sauter.
- Ensuite, on réapparaît toujours dans l'avion… sauf si on a dormi dans un lit.
- **En solo**, toutes les commandes (`/start`, `/cabane…`) marchent toujours, même sans avoir activé la triche. **Sur un serveur**, elles restent réservées aux admins.

## Le village de Beauvais

Au tout premier démarrage du serveur, le mod cherche une grande jungle (biomes géants : jungle, savane, désert, volcans, neige) et y construit Beauvais, en grand (130 blocs de large) :

- **au centre**, une grande place pavée avec quatre fontaines et le **grand panneau de la carte** ;
- **au nord**, la **Grande Cabane**, la base des joueurs : grande salle, cuisine, bibliothèque, coffres, **mur des reliques**, dortoir, atelier ;
- **à l'ouest**, le **Grand Bazar** : le comptoir de Mouloud, le tableau des contrats de Mehdi (les contrats de l'heure s'affichent au-dessus, avec le compte à rebours ; clic droit pour livrer), et des emplacements d'étals ;
- **à l'est**, la rue : des maisons usées par le temps, un chantier, la **forge de Brokk** ;
- **au sud**, les champs, le puits, l'enclos, le feu de camp, la tour de guet et la **grande porte** ;
- des **terrains à vendre** : une borne « À VENDRE » avec le prix (maisons 5 000 $, parcelles 8 000 $, grands terrains commerciaux 20 000 $). Clic droit deux fois pour acheter ; la borne affiche ensuite le nom du propriétaire.

Les PNJ (Easy NPC) :

- **Marcel le Conteur**, au feu de camp : c'est par lui que commence l'histoire de Beauvais ;
- **le Hejj du Village**, le sage, devant la Grande Cabane (il donnera les grandes quêtes) ;
- **Karim le Chouf**, au grand panneau, qui donne les cartes au trésor ;
- **Mouloud le Marchand** et son cousin **Gribbs**, au Grand Bazar ;
- **Mehdi de Pôle emploi**, au tableau des contrats ;
- **Brokk le Forgeron**, et sa **forge spéciale** : il fabrique des armes et armures rares des mods (lance Umvuthana, épée d'acier noir, hache du Wroughtnaut, gantelet de la Terre, masque du Soleil, forge infernale…). Il faut lui rapporter les matériaux et payer le travail ; lui seul sait les faire. Les recettes sont dans `mod-cabane/src/main/resources/data/cabane/forge/` ;
- **Ovsie la Fée**, **Nyota la Guérisseuse**, les gardes de la grande porte ;
- **les habitants** : Jean, Jacques, Francis, Louis, Bernard, Gérard, Ginette, Monique, Martine et Thierry. Ils se baladent autour de chez eux et se méfient des nouveaux ;
- **Vegeta**, qui s'entraîne près de la tour de guet (« ma stupide fierté… ») ;
- les **orques de Brokk** (Grosh, Mog et Urga), qui travaillent dans sa forge ;
- **Macron, Président de Beauvais**, sur la place, et ses **quatre gardes présidentiels**. Ils sont invincibles : qui frappe le Président (ou un garde) est pris en chasse pendant deux minutes. Ils finissent par se calmer.

Chaque PNJ a plusieurs répliques, tirées au hasard à chaque fois qu'on lui parle. On peut les frapper (ils rougissent et reculent), mais ils ne meurent jamais. Quand le mod est mis à jour, les PNJ du village sont remplacés tout seuls au démarrage suivant.

Autour du village, aucune structure (village, donjon, ruine…) ne s'installe dans un rayon de 400 blocs : le spawn reste propre. Et dans toutes les jungles du monde poussent des **arbres géants** au tronc de 4 x 4.

Commandes admin :

- `/start` lance la partie (l'hôte seulement) ;
- `/cabane placer` reconstruit le village à tes pieds et y déplace le spawn ;
- `/cabane pnj` fait revenir les PNJ ;
- `/cabane garde <joueur>` lâche la garde présidentielle sur quelqu'un ;
- `/cabane raid [nombre]` lance un **raid des Pillards du Sud** sur le village (6 par défaut, un ravageur à partir de 8, un évocateur à partir de 12) ;
- `/cabane forteresse` donne les coordonnées de la Forteresse du Sud (tout le monde peut la taper) ; `/cabane forteresse ouvrir` lève la grande herse le jour du combat final, `fermer` la rescelle, `construire` la reconstruit ;
- `/cabane mairie` reconstruit la Mairie et son laboratoire (si besoin).

## L'histoire de Beauvais

Le chapitre de quêtes **L'Histoire de Beauvais** raconte la grande aventure, en cinq actes :

1. **Les gens du ciel.** Marcel le Conteur raconte : Beauvais était un village paisible, gouverné par le Conseil des Anciens et protégé par les Sept Reliques. Puis un homme en costume est arrivé, s'est fait Président, a suspendu le Conseil… et les Reliques ont disparu. Depuis, les **Pillards du Sud** ravagent le village et des gens disparaissent. Voilà pourquoi les villageois se méfient des étrangers. Mais une prophétie dit que « ceux qui tomberont du ciel en feu » rendront Beauvais à son peuple.
2. **Les Pillards du Sud.** On les chasse, on trouve leurs avant-postes… et l'or de la Mairie dans leurs coffres. Les **raids** sur le village, c'est l'hôte qui les lance quand il veut (`/cabane raid`).
3. **Le secret de la Mairie.** La Mairie du Président, sur la rive est de la place : salle du conseil, bureau, tableaux. Dans le bureau, une bibliothèque sonne creux : un clic, elle pivote, et une échelle descend vers le **laboratoire secret** (les « sujets » dans leurs tubes, le carnet du Président, le dossier « Projet Augmenté »). Au fond, dans sa cellule, **Pierre**, le Sujet 07, qui en sait long.
4. **Les Sept Reliques**, très loin, toutes au-delà de la zone pré-générée (de 3 500 à 7 000 blocs) : tout est à explorer. Avec les sept, Brokk forge la **Clé de la Salle Zéro**.
5. **La Forteresse du Sud**, à un peu plus de 10 000 blocs plein sud du village (sur la map fournie : **X -1472, Y 66, Z 18224** ; en jeu, `/cabane forteresse` donne ses coordonnées) : une citadelle noire de 116 blocs de côté sur un terrain dégagé, une avenue qui mène à la grande herse, des tours, un donjon de 55 blocs de haut, et au cœur la **Salle Zéro**. **Pour l'instant elle est scellée** : qui essaie d'entrer est reconduit dehors (« c'est pour plus tard ! »). Le jour venu, l'hôte tape `/cabane forteresse ouvrir`, et on y va tous ensemble : la Clé sur le pupitre réveille **le Président Augmenté** (le Héraut de Cataclysm, trois fois plus solide, avec des vagues de gardes augmentés). Quand il tombe, Beauvais est libéré : il finit dans une cellule de son propre laboratoire, Pierre retrouve le feu de camp.

## Le bar : bières, vin et sodas

- **L'alcool** : bière blonde, bière brune, la fameuse **8.6**, vin rouge, pastis, rhum. Chaque verre fait monter l'**ivresse** : le hoquet, puis la tête qui tourne, la caméra qui tangue, la démarche qui titube… et le trou noir si on abuse. Certaines donnent un petit bonus (la 8.6 et le rhum rendent plus fort, le vin soigne un peu, le pastis protège du feu).
- **Les sodas** (coca, fanta, ice tea pêche) : un coup de fouet (vitesse, saut, régénération), et ils font redescendre d'un cran.
- Tout s'achète chez Mouloud, et tout se fabrique : bouteille vide + blé + sucre (blonde), + cacao (brune), + sucre, sucre et œil d'araignée fermenté (8.6), trois baies sucrées (vin), sucre + deux fougères (pastis), cannes à sucre (rhum) ; pépite de fer + deux sucres + cacao, colorant orange ou glace pour les canettes.
- **Pour les vendre** : le marché rachète bières, vin et coca dans ses contrats, et chacun peut tenir son étal (ou ouvrir la taverne du village, une quête d'« Agrandir Beauvais »).

## Le téléphone

- Chaque nouveau joueur en reçoit un ; les suivants s'achètent chez Mouloud (pas de recette). Touche **N** ou clic droit : la première fois, on choisit tout de suite son numéro.
- **La SIM est gravée dans le téléphone** : la première fois, tu choisis un numéro à 3 ou 4 chiffres, et il ne changera plus. Un autre numéro, c'est un autre téléphone. Le téléphone garde sa mémoire (contacts, récents, photos, compte Piaf) : si on te le vole, on peut se faire passer pour toi.
- Compose un numéro → ça sonne chez l'autre (les joueurs autour l'entendent aussi). On peut appeler **en numéro masqué**. Vous vous entendez partout sur la map grâce à Simple Voice Chat.
- Les applis, façon iPhone : **Clavier**, **Contacts**, **Récents**, **Messages**, **Piaf** (le réseau social), **Appareil photo** et **Photos**, **Portefeuille**, **Réglages**.
- **Messages** : des SMS entre numéros, comme sur un vrai téléphone (bulles bleues et grises, notification à l'arrivée, pastille rouge sur l'icône pour les non lus). On peut joindre une photo de sa galerie : elle arrive dans la galerie de l'autre. Les conversations restent dans le téléphone (sa SIM), comme les contacts.
- **L'appareil photo** : la **molette** change de mode, **PHOTO** (à plat), **PORTRAIT** (debout) ou **SELFIE** (debout, la caméra te filme de face) ; le **clic molette** règle le **retardateur** (3, 5 ou 10 s). Avec le retardateur, le téléphone reste posé là où tu as déclenché : tu as le temps d'aller te mettre dans le cadre (avec tes potes), et la photo est prise depuis cet endroit.
- **Photos** : touche une photo pour l'ouvrir, puis **Imprimer**, **Envoyer par message** ou **Publier sur Piaf**.
- **Imprimer** : tu choisis la taille sur le mur, bloc par bloc (de 1 x 1 à 8 x 8), avec un aperçu ; la photo est rognée au format choisi, jamais déformée. Il faut une feuille de papier pour 4 blocs. La photo imprimée s'accroche comme un tableau (clic droit sur un mur plein) ; on la décroche en la frappant.

## L'argent, la boutique et le casino

- La monnaie : les SakaiDollars ($). On en gagne avec les contrats de Mehdi, on se paie entre joueurs avec `/payer`.
- La boutique de Mouloud vend le téléphone, les boosters de cartes (animations de révélation, la carte la plus rare toujours en dernier), les puffs et la chicha, les étals, la police… et le casino.
- **Le casino** coûte cher (4 000 à 12 000 $ la table) : c'est le gérant qui l'installe. Chaque jeu a sa **caisse**, qui paie les gagnants et garde les mises.
- **Remplir une caisse** : le joueur qui a posé la machine (ou un admin) s'accroupit et fait clic droit dessus. Un écran s'ouvre : on y met de l'argent, ou on récupère tout. Si la caisse ne peut pas payer le plus gros gain possible, la mise est refusée (« le gérant doit la remplir »).
- **Tout est en 3D** : la machine à sous (rouleaux qui défilent, levier), la roulette, la roue de la fortune, la table de dés (les dés roulent), le pile ou face (la pièce saute) et le blackjack avec son croupier. On choisit sa mise sur l'écran, puis on regarde le tirage en vrai sur la table, comme tout le monde autour.
- **Les grandes tables** : la **roulette** (2 x 2) avec son cylindre qui tourne en vrai et la bille qui tombe dans la case, la **roue de la fortune** (5 x 5) avec ses lumières et son feu d'artifice au jackpot, et le **blackjack** (3 x 2) avec un croupier : jusqu'à trois joueurs assis, les cartes de chacun sont posées sur le tapis.
- Les **tickets à gratter**, à gratter vraiment à la souris : trois modèles tirés au hasard, BEAUVAIS MILLIONS (trois montants identiques), PENALTY ! (3 buts sur 5 tirs) et MORPION D'OR (3 étoiles alignées).
- Les **boosters** s'ouvrent carte par carte, la plus rare en dernier.
- Les **puffs** et la **chicha** ont un goût surprise (Love 66, Lady Killer, Double Pomme, Blue Mist… 24 goûts), avec la fumée de la bonne couleur.

## La police

Chez Mouloud : les **menottes** (clic droit sur un joueur : il est menotté et suit son policier ; encore un clic pour le libérer), le **pistolet** (munitions infinies, une balle toutes les 0,7 s, aucun dégât : la cible brille et ralentit), le **gilet de police** (il s'enfile comme un plastron, par-dessus les habits : on garde son skin) et la **voiture de police** (sans essence, quatre places, ZQSD pour conduire, **H** pour la sirène, le gyrophare tourne dès qu'on conduit, accroupi + frapper pour la ranger).

## Les émotes et les petits mods marrants

- **Emotecraft** : ses émotes de base (backflip, applaudir, pleurer, facepalm, kazotsky kick…) plus les nôtres : le Dab, la T-pose, les muscles, le floss, « bof », le salut militaire et le headbang (`outils/emotes.mjs`).
- **PoopSky** : pour faire caca. Voilà.

## Les voix des PNJ

Chaque PNJ dit une réplique enregistrée quand on lui parle (s'il en a). Pour ajouter des voix :

1. Enregistre-toi (téléphone, micro du PC…), une réplique par fichier, quelques secondes chacune. Les voix drôles sont les bienvenues.
2. Nomme chaque fichier avec le nom du PNJ, puis un numéro : `macron-1`, `macron-2`, `vegeta-fierte`, `gardien-1`… S'il en a plusieurs, il en dit une au hasard.
3. Mets-les dans le dossier `voix/` à la racine du projet, au format `.ogg` (Audacity sait exporter en OGG, gratuit). Sinon, envoie-les-moi dans n'importe quel format et je les convertis.
4. Lance `node mod-cabane/outils/generer.mjs`, puis `compiler.bat` et `publier.bat`.

Les noms des PNJ : `gardien` (le Hejj), `cartographe` (Karim), `marchand` (Mouloud), `cousin` (Gribbs), `pole_emploi` (Mehdi), `forgeron` (Brokk), `orque` (les orques), `fee` (Ovsie), `guerisseuse` (Nyota), `garde_est`, `garde_ouest`, `macron`, `garde_president`, `vegeta`, `conteur` (Marcel), `prisonnier` (Pierre), et les habitants `jean`, `jacques`, `francis`, `louis`, `bernard`, `gerard`, `ginette`, `monique`, `martine`, `thierry`.

## Les skins des personnages

Les skins des personnages principaux sont dessinés par `mod-cabane/outils/persos.mjs`, avec plusieurs versions pour chacun. Les aperçus sont dans `docs/skins/` : choisis une version (par exemple `karim_b`), change-la dans la liste `CHOIX` en bas du fichier, puis relance `node mod-cabane/outils/persos.mjs`.

## Les quêtes (FTB Quests)

- **Beauvais** : l'arrivée, le premier coup de fil, les Waystones, le sac à dos, la cuisine, Create.
- **L'Histoire de Beauvais** : la grande aventure, de Marcel le Conteur jusqu'à la libération (voir plus haut).
- **Agrandir Beauvais** : le grand chantier du Hejj. On s'installe juste à côté du village, hors de l'enceinte, et on le fait grandir : le quartier neuf, le pont, le port, la gare, le marché couvert, l'école de Ginette, le rond-point et la statue du Président, le **commissariat**, les **remparts** contre les raids, la **taverne**, l'infirmerie de Nyota… jusqu'à « Beauvais, capitale de la jungle ».
- **Les gens de Beauvais** : une petite quête par habitant (le balai de Jacques, le singe de Bernard, l'entraînement de Vegeta, le dossier complet de Mehdi…).
- **Les Sept Reliques** : pour chaque relique, un indice qui donne une carte au trésor, puis le donjon à trouver, puis la relique (cachée dans le coffre au trésor de ce donjon). Avec les sept, on obtient « Le Réveil de la Cabane » et des élytres.
- **Les Grands Donjons** : 12 donjons géants à visiter et 12 boss (Cataclysm, Mowzie's Mobs).

Pour modifier les quêtes, utilise le livre en jeu (en op, mode édition), ou modifie `outils/generer-quetes.mjs` puis lance `node outils/generer-quetes.mjs`.

## Les mods principaux

- **Monde** : Terralith + Tectonic (relief), YUNG's (temples, donjons, forteresses), When Dungeons Arise, IDAS, Dungeons & Taverns, Towns & Towers, CTOV, MVS, Structory, Explorify, Cataclysm, Lootr (butin par joueur).
- **Animaux** : Naturalist, Critters & Companions, Mowzie's Mobs, Ecologics, Creeper/Enderman Overhaul, WilderNature, Mutant Monsters.
- **Façon CrazyTown** : Create, Simple Planes, Woodwalkers, MrCrayfish Furniture, Handcrafted, Macaw's, Supplementaries, Sophisticated Backpacks/Storage, Waystones, Emotecraft, Camera Mod, Simple Hats, Etched, Goblin Traders, Farmer's Delight, Carry On, Comforts.
- **Confort** : Simple Voice Chat, Sound Physics, JEI, Jade, Xaero's Minimap et World Map, AppleSkin, Open Parties and Claims, Sodium, Iris et les mods d'optimisation.

La liste complète est dans `pack/mods/`.

## Le serveur

On choisira l'hébergeur plus tard. Il devra accepter nos propres mods (donc pas Aternos) et ouvrir le **port UDP 24454** pour Simple Voice Chat.

**La map est prête à l'avance** : `dist/Beauvais - monde.zip` contient le monde avec Beauvais déjà construit, ses PNJ, et 3 000 blocs dans chaque direction déjà générés (jungle géante autour du village, désert, badlands, savane, neige et volcans plus loin) (pas d'attente au premier lancement, tout le monde voit la même map). La Mairie, son laboratoire et la Forteresse du Sud (scellée) y sont déjà construits. Au-delà des 3 000 blocs, le monde continue de se générer normalement quand on explore. Pour l'installer sur le serveur : décompresser le zip dans le dossier du serveur, puis dans `server.properties` :

```
level-name=monde-beauvais
level-type=minecraft:large_biomes
level-seed=lacabane2026
```

Sans ce zip, le serveur construit le village tout seul au premier démarrage (une à deux minutes).
