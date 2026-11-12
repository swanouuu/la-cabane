# L'installeur du pack : installe Prism Launcher (version portable, à part de tout le reste), y met notre instance et
# pose un raccourci sur le Bureau. Les mods se téléchargent et se mettent à jour au lancement (packwiz).
# Modèle lu par outils/construire-installeur.mjs, qui remplace les @@...@@ et le colle dans un .bat.
# Relancer l'installeur ne perd rien : ni le compte Microsoft, ni les réglages du joueur.
# Pour un essai : SAKAIMOD_DOSSIER (où installer) et SAKAIMOD_BUREAU (où poser le raccourci).

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Nom = '@@NOM@@'
$PrismVersion = '@@PRISM_VERSION@@'
$PrismUrl = '@@PRISM_URL@@'
$PrismSha = '@@PRISM_SHA@@'
$Serveur = '@@SERVEUR@@'
$MemoireMax = @@MEMOIRE@@
$Contenu = '@@CONTENU@@'

function Etape($texte) {
    Write-Host ''
    Write-Host "==> $texte" -ForegroundColor Cyan
}

function Ecrire-SansBom($chemin, $texte) {
    [System.IO.File]::WriteAllText($chemin, $texte, (New-Object System.Text.UTF8Encoding($false)))
}

function Vider($dossier) {
    if (Test-Path -LiteralPath $dossier) { Remove-Item -LiteralPath $dossier -Recurse -Force }
}

try {
    Write-Host "Installation de $Nom" -ForegroundColor Green

    $racine = if ($env:SAKAIMOD_DOSSIER) { $env:SAKAIMOD_DOSSIER } else { Join-Path $env:LOCALAPPDATA $Nom }
    $bureau = if ($env:SAKAIMOD_BUREAU) { $env:SAKAIMOD_BUREAU } else { [Environment]::GetFolderPath('Desktop') }
    $prism = Join-Path $racine 'Prism'
    $exe = Join-Path $prism 'prismlauncher.exe'
    $temp = Join-Path $racine 'temp'

    $ouvert = Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe }
    if ($ouvert) { throw "Le jeu ou le launcher $Nom est ouvert. Ferme-le, puis relance cet installeur." }

    Vider $temp
    New-Item -ItemType Directory -Force -Path $prism, $temp | Out-Null

    $libre = (Get-PSDrive -Name $racine.Substring(0, 1)).Free
    if ($libre -lt 5GB) { throw "Il faut au moins 5 Go libres sur le disque $($racine.Substring(0, 2))." }

    # ---- Prism Launcher (seulement s'il manque ou si la version a changé)
    $marque = Join-Path $prism 'version-installee.txt'
    $installee = if (Test-Path -LiteralPath $marque) { (Get-Content -LiteralPath $marque -Raw).Trim() } else { '' }
    if (($installee -ne $PrismVersion) -or -not (Test-Path -LiteralPath $exe)) {
        Etape "Téléchargement de Prism Launcher $PrismVersion"
        $zip = Join-Path $temp 'prism.zip'
        Invoke-WebRequest -Uri $PrismUrl -OutFile $zip -UseBasicParsing
        $sha = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sha -ne $PrismSha) { throw 'Le fichier téléchargé est abîmé ou a été modifié. Réessaie un peu plus tard.' }

        Etape 'Installation de Prism Launcher'
        $extrait = Join-Path $temp 'prism'
        Expand-Archive -LiteralPath $zip -DestinationPath $extrait -Force
        $source = $extrait
        $enfants = @(Get-ChildItem -LiteralPath $extrait)
        if (($enfants.Count -eq 1) -and $enfants[0].PSIsContainer) { $source = $enfants[0].FullName }
        Get-ChildItem -LiteralPath $source | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $prism -Recurse -Force }
        if (-not (Test-Path -LiteralPath $exe)) { throw "prismlauncher.exe est introuvable après l'installation." }
        Ecrire-SansBom $marque $PrismVersion
    }

    # Le mode portable garde tout dans ce dossier, sans toucher à un autre Prism déjà installé
    $portable = Join-Path $prism 'portable.txt'
    if (-not (Test-Path -LiteralPath $portable)) { Ecrire-SansBom $portable '' }

    # Les réglages de Prism : français, Java géré tout seul, et il se ferme avec le jeu
    $cfg = Join-Path $prism 'prismlauncher.cfg'
    if (-not (Test-Path -LiteralPath $cfg)) {
        Ecrire-SansBom $cfg ((@(
            '[General]',
            'ConfigVersion=1.2',
            'Language=fr',
            'UseSystemLocale=false',
            'ApplicationTheme=dark',
            'IconTheme=pe_colored',
            'AutomaticJavaDownload=true',
            'AutomaticJavaSwitch=true',
            'UserAskedAboutAutomaticJavaDownload=true',
            'CloseAfterLaunch=true',
            'QuitAfterGameStop=true',
            'LowMemWarning=false',
            ''
        )) -join "`r`n")
    }
    else {
        # Sans ça, Prism refuse de lancer le jeu dès que la mémoire demandée n'est pas libre à cet instant
        $reglages = [System.IO.File]::ReadAllText($cfg)
        if ($reglages -notmatch '(?m)^LowMemWarning=') {
            Ecrire-SansBom $cfg ($reglages -replace '(?m)^\[General\]\r?$', "[General]`r`nLowMemWarning=false")
        }
    }

    # ---- notre instance
    Etape "Préparation de $Nom"
    $paquet = Join-Path $temp 'contenu.zip'
    [System.IO.File]::WriteAllBytes($paquet, [Convert]::FromBase64String($Contenu))
    $contenuDir = Join-Path $temp 'contenu'
    Expand-Archive -LiteralPath $paquet -DestinationPath $contenuDir -Force

    $instance = Join-Path (Join-Path $prism 'instances') $Nom
    $minecraft = Join-Path $instance 'minecraft'
    New-Item -ItemType Directory -Force -Path $minecraft | Out-Null
    $source = Join-Path $contenuDir 'instance'

    # Toujours à jour : la version du jeu et l'outil de mise à jour
    Copy-Item -LiteralPath (Join-Path $source 'mmc-pack.json') -Destination $instance -Force
    Copy-Item -LiteralPath (Join-Path $source 'minecraft\packwiz-installer-bootstrap.jar') -Destination $minecraft -Force

    # Seulement la première fois : ce que le jeu et le joueur modifient ensuite
    foreach ($fichier in 'minecraft\options.txt', 'minecraft\servers.dat') {
        $de = Join-Path $source $fichier
        $vers = Join-Path $instance $fichier
        if ((Test-Path -LiteralPath $de) -and -not (Test-Path -LiteralPath $vers)) { Copy-Item -LiteralPath $de -Destination $vers }
    }

    # La mémoire : ce que le PC peut donner, sans dépasser le réglage du pack
    $totalMo = [int][math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB)
    $memoire = [math]::Max(4096, [math]::Min($MemoireMax, $totalMo - 4096))
    $cfgInstance = Join-Path $instance 'instance.cfg'
    $texte = [System.IO.File]::ReadAllText((Join-Path $source 'instance.cfg'))
    if (-not (Test-Path -LiteralPath $cfgInstance)) {
        Ecrire-SansBom $cfgInstance ($texte -replace 'MaxMemAlloc=\d+', "MaxMemAlloc=$memoire")
    }
    else {
        # Déjà installé : on garde ses réglages, mais la commande de mise à jour des mods doit toujours être la bonne
        $ligne = [regex]::Match($texte, '(?m)^PreLaunchCommand=.*$').Value.TrimEnd("`r")
        $actuel = [System.IO.File]::ReadAllText($cfgInstance)
        $repare = [regex]::Replace($actuel, '(?m)^PreLaunchCommand=.*$', [System.Text.RegularExpressions.MatchEvaluator] { param($m) $ligne })
        # Une installation réglée trop haut redescend : Prism refuse de lancer le jeu si la mémoire demandée n'est pas libre
        $reglee = [regex]::Match($repare, '(?m)^MaxMemAlloc=(\d+)')
        if ($reglee.Success -and ([int]$reglee.Groups[1].Value -gt $memoire)) {
            $repare = $repare -replace '(?m)^MaxMemAlloc=\d+', "MaxMemAlloc=$memoire"
        }
        Ecrire-SansBom $cfgInstance $repare
    }

    $icones = Join-Path $prism 'icons'
    New-Item -ItemType Directory -Force -Path $icones | Out-Null
    Copy-Item -LiteralPath (Join-Path $source 'cabane.png') -Destination $icones -Force
    # Un nom propre au logo : Windows garde en cache l'image d'un chemin qu'il connaît déjà
    $ico = Join-Path $racine "$Nom-logo.ico"
    Copy-Item -LiteralPath (Join-Path $contenuDir 'raccourci.ico') -Destination $ico -Force

    # ---- le raccourci : il ouvre directement le jeu (et rejoint le serveur s'il est connu)
    Etape 'Création du raccourci sur le Bureau'
    $arguments = "-l `"$Nom`""
    if ($Serveur) { $arguments += " -s `"$Serveur`"" }
    $shell = New-Object -ComObject WScript.Shell
    $lien = $shell.CreateShortcut((Join-Path $bureau "$Nom.lnk"))
    $lien.TargetPath = $exe
    $lien.Arguments = $arguments
    $lien.WorkingDirectory = $prism
    $lien.IconLocation = "$ico,0"
    $lien.Description = $Nom
    $lien.Save()

    Vider $temp

    Write-Host ''
    Write-Host 'C''est installé !' -ForegroundColor Green
    Write-Host "Double-clique sur le raccourci $Nom sur ton Bureau."
    Write-Host 'La première fois : connecte ton compte Microsoft (il faut avoir Minecraft Java),'
    Write-Host 'puis laisse les mods se télécharger, ça peut prendre plusieurs minutes.'
    if ($totalMo -lt 8192) {
        Write-Host ''
        Write-Host "Attention : ton PC a peu de mémoire ($([math]::Round($totalMo / 1024)) Go). Le jeu risque de ramer, ferme tout le reste avant de jouer." -ForegroundColor Yellow
    }
}
catch {
    Write-Host ''
    Write-Host "L'installation n'a pas abouti :" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
}
