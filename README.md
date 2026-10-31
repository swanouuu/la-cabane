# La Cabane — serveur Minecraft entre potes

Survie moddée en **NeoForge 1.21.1** (la même base que CrazyTown S3). Grande map savane, une cabane abandonnée au centre de la map comme base principale, des PNJ, une grande quête qui envoie dans des donjons partout sur la map, et des téléphones avec numéros perso et vocal.

Nom provisoire : remplace « La Cabane » dans `outils/config.json` et `pack/pack.toml` quand vous aurez trouvé le vrai nom.

## Comment ça marche

```
Toi (Prism)  ──publier.bat──►  GitHub (pack/)  ◄──au lancement──  Prism de tes potes
                                     ▲
                                     └──────au démarrage──────  Serveur
```

- `pack/` : la liste des mods (format [packwiz](https://packwiz.infra.link)), les configs et le livre de quêtes. C'est la seule chose que tes potes et le serveur téléchargent.
- `mod-cabane/` : notre mod maison (téléphone, centre de map, reliques).
- `outils/` : les scripts. Les fichiers `.bat` à la racine sont là pour lancer ces scripts en double-cliquant.

## Pour tes potes (une seule fois)

1. Installer [Prism Launcher](https://prismlauncher.org) et se connecter avec son compte Microsoft (il faut posséder Minecraft Java).
2. Importer `La Cabane.zip` dans Prism (Ajouter une instance → Importer).
3. Lancer. Les mods se téléchargent tout seuls, et à chaque lancement ils se mettent à jour.

Prévoir 8 Go de RAM pour l'instance (réglé par défaut).

## Pour toi

| Fichier | À quoi il sert |
|---|---|
| `installer-outils.bat` | Installe Java 21, Go et packwiz dans `.outils/` (seulement sur un nouveau PC). |
| `compiler.bat` | Compile le mod maison et le met dans le pack. |
| `publier.bat` | Compare les mods de **ton** instance Prism avec le pack, met le pack à jour et l'envoie sur GitHub. `publier.bat --essai` montre seulement ce qui changerait. |
| `construire-instance.bat` | Refait `dist/La Cabane.zip`, à envoyer aux potes. |

**Ajouter ou retirer un mod** : fais-le dans ton Prism, puis double-clique sur `publier.bat` **avant** de relancer le jeu. Sinon, au lancement, le pack remet les mods comme avant. Les mods qui ne sont que sur CurseForge doivent être ajoutés à la main :

```bash
cd pack
../.outils/gopath/bin/packwiz.exe curseforge add <nom-du-mod>
```

## Le centre de map

Au tout premier démarrage du serveur, le mod cherche une savane près du centre du monde et y construit :

- **la cabane** : lit, cuisine, cheminée qui fume, bibliothèque, journal du Gardien sur le pupitre, et le **mur des reliques** (7 cadres) ;
- **le feu de camp** avec des bancs, les **lampadaires**, le **tableau des quêtes** ;
- **le puits**, le **vieux potager**, la **tour de guet en ruine** avec un **feu de signal** dont la fumée se voit de loin, et un coffre au sommet ;
- les **PNJ** : le Vieux Gardien, Mira la Cartographe et Bako le Marchand (Easy NPC). Tu peux modifier leurs dialogues, leur skin, et plus tard ajouter des voix, directement en jeu.

Le spawn est placé devant le feu de camp. Commandes admin :

- `/cabane placer` reconstruit le centre de map à tes pieds et y déplace le spawn ;
- `/cabane pnj` fait revenir les PNJ.

## Les quêtes (FTB Quests)

- **La Cabane** : l'arrivée, le premier coup de fil, les Waystones, le sac à dos, la cuisine, Create.
- **Les Sept Reliques** : pour chaque relique, un indice qui donne une carte au trésor, puis le donjon à trouver, puis la relique (cachée dans le coffre au trésor de ce donjon). Avec les sept, on obtient « Le Réveil de la Cabane » et des élytres.
- **Les Grands Donjons** : 12 donjons géants à visiter et 12 boss (Cataclysm, Mowzie's Mobs).

Pour modifier les quêtes, utilise le livre en jeu (en op, mode édition), ou modifie `outils/generer-quetes.mjs` puis lance `node outils/generer-quetes.mjs`.

## Le téléphone

- Touche **N** (ou clic droit avec le téléphone). La première fois, chacun choisit son numéro à **3 ou 4 chiffres**.
- Compose un numéro → ça sonne chez l'autre (les joueurs autour l'entendent aussi). Il décroche, et vous vous entendez partout sur la map grâce à Simple Voice Chat.
- Il faut avoir le téléphone dans l'inventaire. Recette : vitre, fer, redstone, cuivre. Chaque nouveau joueur en reçoit un.
- Commandes : `/telephone numero|appeler|decrocher|raccrocher`. Pour les admins : `/telephone annuaire`.

## Les mods principaux

- **Monde** : Terralith + Tectonic (relief), YUNG's (temples, donjons, forteresses), When Dungeons Arise, IDAS, Dungeons & Taverns, Towns & Towers, CTOV, MVS, Structory, Explorify, Cataclysm, Lootr (butin par joueur).
- **Animaux** : Naturalist, Critters & Companions, Mowzie's Mobs, Ecologics, Creeper/Enderman Overhaul, WilderNature, Mutant Monsters.
- **Façon CrazyTown** : Create, Simple Planes, Woodwalkers, MrCrayfish Furniture, Handcrafted, Macaw's, Supplementaries, Sophisticated Backpacks/Storage, Waystones, Emotecraft, Camera Mod, Simple Hats, Etched, Goblin Traders, Farmer's Delight, Carry On, Comforts.
- **Confort** : Simple Voice Chat, Sound Physics, JEI, Jade, Xaero's Minimap et World Map, AppleSkin, Open Parties and Claims, Sodium, Iris et les mods d'optimisation.

La liste complète est dans `pack/mods/`.

## Le serveur

On choisira l'hébergeur plus tard. Il devra accepter nos propres mods (donc pas Aternos) et ouvrir le **port UDP 24454** pour Simple Voice Chat.
