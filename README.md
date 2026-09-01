# SoundX — Soundboard de bureau pour Windows

Soundboard native Windows, **sans limite de sons**, en **Java + Swing**.

- **Scan dynamique** du dossier `sounds/` → une grille de boutons générée automatiquement.
- **Raccourcis clavier globaux** (JNativeHook) : les sons se déclenchent même quand
  l'application n'a pas le focus (pendant un jeu, par exemple).
- **Sélection de la sortie audio** (`javax.sound.sampled`) : envoyez le son vers un
  câble audio virtuel (VB-CABLE, VoiceMeeter…) pour l'injecter dans Discord / OBS / un jeu.
- **Renvoi micro (façon Voicemod)** : SoundX peut capturer votre micro et le
  réinjecter dans la **même** sortie, pour que **micro + sons** arrivent ensemble
  dans le câble virtuel.
- **Écoute locale (monitoring)** : chaque son est joué en parallèle sur votre casque,
  pour que vous l'entendiez pendant qu'il part dans le câble.
- **Bouton d'urgence STOP** : bouton UI **et** raccourci global dédié qui coupent
  instantanément toutes les lectures en cours.
- Lecture **WAV** native + **MP3** (JLayer / mp3spi).
- **Thème sombre noir / rouge** (FlatLaf), boutons dessinés sur mesure.
- Réduction dans la zone de notification : l'appli reste active en arrière-plan.

---

## 1. Stack technique

| Élément            | Choix                                                                 |
|--------------------|----------------------------------------------------------------------|
| Langage            | **Java 21 (LTS)** — Java 17 fonctionne aussi (voir §5)               |
| Interface          | **Java Swing** + thème **FlatLaf 3.7.2** (sombre, personnalisé noir/rouge) |
| Audio (sons)       | **`javax.sound.sampled`** (`SourceDataLine` par `Mixer`, 1 son → N sorties) |
| Audio (renvoi micro) | **`TargetDataLine` → `SourceDataLine`** (copie PCM en continu)     |
| Décodage MP3       | **`mp3spi` 1.9.5.4** → tire `jlayer` (JLayer) + `tritonus-share` via SPI |
| Raccourcis globaux | **JNativeHook 2.2.2** (`com.github.kwhat:jnativehook`)              |
| Config             | **Gson 2.11.0** (fichier `soundx-config.json`)                       |
| Build              | **Maven** (+ Maven Wrapper fourni : pas besoin d'installer Maven)   |

---

## 2. Architecture du projet

```
e:\SoundX
├── pom.xml                     Config Maven + dépendances + fat-jar (shade)
├── mvnw / mvnw.cmd             Maven Wrapper (télécharge Maven au 1er appel)
├── .mvn/wrapper/…              Config du wrapper
├── run.bat                     Build + lancement en un double-clic
├── sounds/                     ← déposez ici vos .wav / .mp3
└── src/main/java/com/soundx/
    ├── Main.java               Amorçage : logs, thème, résolution du dossier de données
    │                           stable (%LOCALAPPDATA%\SoundX), adoption d'un sounds/
    │                           existant, migration de config, services, hook, arrêt propre
    │
    ├── audio/
    │   ├── AudioManager.java   Périphériques (sortie + écoute + entrée), routage,
    │   │                       lecture illimitée, stopAll(), renvoi micro
    │   ├── Playback.java       Une lecture = 1 thread poussant le PCM vers 1..N SourceDataLine
    │   │                       (sortie principale + écoute casque)
    │   ├── MicWorker.java      Renvoi micro : TargetDataLine → SourceDataLine en continu
    │   └── AudioDevice.java    Adaptateur Mixer.Info pour les JComboBox
    │
    ├── input/
    │   ├── KeyHookManager.java Enveloppe JNativeHook : bind(combo→action), mode capture
    │   └── KeyCombo.java       Combinaison de touches immuable (modificateurs + code touche)
    │
    ├── config/
    │   ├── AppConfig.java      Modèle JSON (sortie, micro, volumes, raccourcis)
    │   └── ConfigManager.java  Chargement / sauvegarde tolérante (Gson)
    │
    ├── model/
    │   ├── SoundEntry.java     Un son : fichier + raccourci éventuel
    │   └── SoundFiles.java     Extensions audio reconnues + détection de dossier
    │
    └── ui/
        ├── Theme.java          Palette noir/rouge + installation de FlatLaf
        ├── SoundboardUI.java   JFrame principale (grille, SORTIE, MICRO, STOP, tray)
        ├── SoundButton.java    Carte d'un son peinte à la main (états survol / actif)
        ├── AccentButton.java   Bouton plein arrondi (STOP)
        ├── LevelMeter.java     Vu-mètre du signal micro (diagnostic « ma voix passe-t-elle ? »)
        └── KeyCaptureDialog.java  Fenêtre modale de capture d'un raccourci via le hook global
```

### Flux d'exécution

1. `Main` coupe les logs JNativeHook, installe le thème sombre (`Theme.install()`,
   **avant** tout composant Swing), résout le dossier de données stable
   (`%LOCALAPPDATA%\SoundX`), migre une éventuelle ancienne config, charge la config,
   puis résout le dossier des sons (config → `sounds/` existant adopté → défaut stable).
2. `KeyHookManager.start()` installe le hook natif Windows.
3. `Main` enregistre un **shutdown hook** : JNativeHook lance un thread *non-daemon*,
   il **faut** l'`unregister` sinon la JVM ne se termine jamais.
4. `SoundboardUI` scanne le dossier des sons, crée un `SoundButton` par fichier,
   ré-applique les raccourcis mémorisés, remplit les listes de périphériques
   (sortie + écoute + micro — un vrai micro nommé est préféré au « Pilote de capture
   audio principal », qui suit le défaut Windows et est souvent muet), et relance le
   renvoi micro si `config.micPassthrough`. Un `javax.swing.Timer` alimente le
   vu-mètre `Signal` (`AudioManager.getMicLevel()` ← crête calculée par `MicWorker`).
5. Clic bouton → `AudioManager.play(file)` : décodage (WAV direct, MP3 via SPI) →
   ouverture d'**une `SourceDataLine` par sortie active** (SORTIE + ÉCOUTE si elle
   vise un autre périphérique) → un seul thread `Playback` écrit chaque bloc PCM sur
   toutes les lignes. Si une ligne échoue, les autres continuent.
6. Renvoi micro : `MicWorker` ouvre une `TargetDataLine` sur le micro choisi + une
   `SourceDataLine` sur **la sortie courante**, et recopie le PCM en boucle (~20 ms
   de latence). Il **suit automatiquement** tout changement de sortie. Un compteur de
   génération garantit que seul le dernier réglage l'emporte ; la (re)config se fait
   hors EDT.
7. Appui d'un raccourci → `KeyHookManager` retrouve l'action dans sa `Map<KeyCombo,…>`
   et l'exécute sur un thread dédié (jamais sur le thread du hook).
8. STOP (bouton ou raccourci) → `AudioManager.stopAll()` : `stop()/flush()` sur toutes
   les lignes de **sons** (le renvoi micro n'est **pas** coupé).

---

## 3. Prérequis

- **JDK 21** (Temurin/Adoptium recommandé) : <https://adoptium.net/temurin/releases/?version=21>
  Vérifier : `java -version` doit afficher `21.x`.
- Une connexion Internet **au premier build** (téléchargement de Maven + dépendances).
- **Maven n'a pas besoin d'être installé** : le wrapper `mvnw.cmd` s'en charge.
  (Si vous avez déjà Maven ≥ 3.9, remplacez `mvnw.cmd` par `mvn` dans les commandes.)

---

## 4. Compiler et exécuter (Windows)

### Option A — le plus simple

- **`run.bat`** : compile **seulement si nécessaire**, puis lance l'appli.
- **`build.bat`** : force une recompilation complète (`clean package`).

### Option B — en ligne de commande (PowerShell ou cmd, depuis `e:\SoundX`)

```bat
REM 1. Construire le jar exécutable "tout-en-un"
mvnw.cmd clean package

REM 2. Lancer
java -jar target\soundx-1.0.0.jar
```

Le fichier produit est **`target\soundx-1.0.0.jar`** (~2,4 Mo, dépendances incluses,
y compris les DLL natives de JNativeHook pour x86 / x64 / ARM).

### Option C — développement rapide (sans packager)

```bat
mvnw.cmd compile exec:java
```

### Où sont mes sons et mes réglages ?

**Pas** dans `target\` (qui est effacé à chaque `clean package`), mais dans un
emplacement **stable par utilisateur** :

```
%LOCALAPPDATA%\SoundX\
├── soundx-config.json   (sortie, écoute, micro, volumes, raccourcis, dossier des sons)
└── sounds\              (dossier des sons par défaut)
```

- Recompiler ou remplacer le `.jar` **ne touche jamais** à ce dossier.
- Menu **Fichier ▸ Ouvrir le dossier des sons** pour y accéder,
  **Fichier ▸ Choisir le dossier des sons…** (ou le bouton *Dossier des sons…*)
  pour en désigner un autre — le choix est mémorisé.
- **Astuce portable** : si un dossier `sounds\` **déjà rempli** existe à côté du
  `.jar` (ou dans `e:\SoundX\`) au tout premier lancement, SoundX l'adopte et s'en
  souvient. Utile pour garder les sons versionnés avec le projet.

### Distribution

Copiez **`soundx-1.0.0.jar`** où vous voulez, double-clic (ou `javaw -jar …`).
Réglages et sons vont dans `%LOCALAPPDATA%\SoundX\` — communs à toutes les copies.

---

## 5. Utiliser Java 17 au lieu de 21

Dans `pom.xml`, remplacez :

```xml
<maven.compiler.release>21</maven.compiler.release>
```

par `17`. Le code n'utilise aucune API postérieure à Java 17 (FlatLaf 3.7 est
compatible Java 8+).

---

## 6. Mode d'emploi de l'application

| Action                              | Comment                                                            |
|-------------------------------------|-------------------------------------------------------------------|
| Jouer un son                        | Clic gauche sur la carte                                           |
| Assigner un raccourci global        | Clic **droit** sur la carte → *Assigner un raccourci global…*      |
| Effacer un raccourci                | Clic droit → *Effacer le raccourci*                                |
| Raccourci STOP global               | Barre du bas → *Définir…* (Ctrl/Alt/Shift + touche recommandé)     |
| Couper tout le son                  | Bouton **STOP** rouge, ou le raccourci STOP, où que vous soyez     |
| Changer la sortie audio             | Bloc **SORTIE** (choisir p. ex. `CABLE Input (VB-Audio…)`)         |
| Entendre aussi les sons au casque   | Bloc **ÉCOUTE** : cocher *Écouter aussi dans* + choisir le casque  |
| Renvoyer le micro vers la sortie    | Bloc **MICRO** : choisir l'entrée + cocher *Renvoyer le micro…* ; le vu-mètre *Signal* doit bouger quand vous parlez |
| Régler les volumes                  | Curseurs *Volume sons*, *Volume écoute*, *Volume micro*            |
| Ajouter des sons                    | *Fichier ▸ Ouvrir le dossier des sons*, y copier les fichiers → *Rescanner* |
| Changer de dossier de sons          | *Fichier ▸ Choisir le dossier des sons…* (choix mémorisé)          |
| Laisser tourner en arrière-plan     | Fermer la fenêtre → l'appli va dans la zone de notification        |
| Quitter vraiment                    | Menu *Fichier → Quitter*, ou clic droit sur l'icône de la barre    |

Toutes les préférences (sortie, écoute, micro, volumes, raccourcis) sont enregistrées
dans `soundx-config.json` et rechargées au démarrage suivant.

### Bloc ÉCOUTE — s'entendre soi-même

Par défaut, chaque son est joué **à la fois** sur la SORTIE (le câble → Discord) **et**
sur le périphérique d'ÉCOUTE (votre casque). Vous entendez donc toujours vos sons.

- L'ÉCOUTE est **ignorée automatiquement** si elle pointe sur le même périphérique
  que la SORTIE (sinon vous entendriez le son en double).
- Laissez l'ÉCOUTE sur *« Sortie par défaut du système »* si votre casque est déjà
  le périphérique de lecture Windows par défaut ; sinon choisissez-le explicitement.
- Le curseur *Volume écoute* ne règle que votre retour local — il n'affecte pas ce
  qu'entend Discord.
- Cela ne concerne **que les sons de la soundboard**. Pour vous entendre parler, c'est
  votre casque qui capte directement votre voix (le renvoi micro, lui, ne va que dans
  le câble, sans latence ajoutée sur votre oreille).

### Configuration type Discord (micro + sons ensemble)

C'est le montage « façon Voicemod » : Discord n'entend qu'**un seul** micro, donc
il faut que votre voix **et** les sons passent par le même câble.

1. Installez **VB-CABLE** : <https://vb-audio.com/Cable/> (redémarrez Windows).
2. Dans **SoundX** :
   - bloc **SORTIE** → `CABLE Input (VB-Audio Virtual Cable)` ;
   - bloc **MICRO** → votre **vrai** micro nommé (ex. `Microphone (Logitech PRO X…)`).
     **N'utilisez pas** *« Pilote de capture audio principal »* : il suit le
     périphérique d'enregistrement Windows par défaut (souvent réglé sur le câble →
     silence). SoundX choisit un vrai micro tout seul, mais vérifiez.
   - cochez **« Renvoyer le micro vers la sortie »**. Le statut passe à
     *« Renvoi micro actif »* et le **vu-mètre *Signal* bouge quand vous parlez**.
     S'il reste vide : mauvais micro, ou accès micro refusé
     (*Paramètres Windows ▸ Confidentialité et sécurité ▸ Microphone ▸ « Autoriser
     les applications de bureau à accéder au micro »*).
   - bloc **ÉCOUTE** → votre casque (ou *« Sortie par défaut »* s'il l'est déjà),
     case *« Écouter aussi dans »* cochée → vous entendez les sons pendant qu'ils
     partent dans le câble.
3. Dans **Discord** → *Paramètres → Voix et vidéo* → **Périphérique d'entrée** =
   `CABLE Output (VB-Audio Virtual Cable)`.
   Pensez à baisser la *sensibilité d'entrée* ou à utiliser *Push-to-talk* si besoin.

> Votre voix : elle est captée normalement par votre casque (retour direct, aucune
> latence) et, via le renvoi micro, réinjectée dans le câble pour Discord.
> Le renvoi micro ajoute ~20–40 ms **uniquement côté Discord**, pas dans votre oreille.
> Si un jour vous voulez un vrai mixeur (plusieurs entrées/sorties, retour réglé au
> casque), passez à **VoiceMeeter** — SoundX s'y branche comme n'importe quel périphérique.

---

## 7. Notes spécifiques Windows

- **DLL native JNativeHook** : elle est extraite automatiquement dans le dossier temp
  au lancement. Certains antivirus peuvent la mettre en quarantaine (elle installe un
  *low-level keyboard hook*, technique légitime mais surveillée) → ajoutez une exception
  si nécessaire.
- **Jeu lancé en administrateur** : un processus non élevé ne reçoit pas les touches
  d'une fenêtre élevée. Si vos raccourcis ne marchent pas *dans* le jeu, lancez SoundX
  **en tant qu'administrateur**.
- **Les raccourcis ne sont pas « consommés »** : la touche continue d'arriver au jeu.
  Choisissez des combinaisons peu utilisées (p. ex. `Ctrl+Alt+1`, touches `F13`–`F24`,
  pavé numérique).
- **Plusieurs sons simultanés (+ renvoi micro)** : le mélange est fait par le pilote
  Windows. La plupart des périphériques (et VB-CABLE) l'acceptent ; un périphérique en
  **mode exclusif** peut refuser une 2ᵉ ligne → décochez *« Autoriser les applications
  à prendre le contrôle exclusif »* dans les propriétés du périphérique.
- **Renvoi micro** : SoundX ouvre le micro en continu. Si une autre application le
  monopolise en exclusif, le renvoi peut échouer (voir le statut sous le bloc MICRO).
- **Formats** : `.wav`, `.aif/.aiff`, `.au` nativement ; `.mp3` via JLayer/mp3spi (inclus).
  Pour l'OGG, ajoutez `com.googlecode.soundlibs:vorbisspi` dans `pom.xml` (même principe SPI).

---

## 8. Dépannage

| Symptôme                                              | Piste                                                                 |
|------------------------------------------------------|----------------------------------------------------------------------|
| `UnsupportedAudioFileException` sur un MP3           | Vérifier que `mp3spi` est bien dans le jar (build via `mvnw package`, pas un simple `javac`). |
| Aucun bouton / mes sons ont « disparu »               | La barre d'état affiche le dossier lu (par défaut `%LOCALAPPDATA%\SoundX\sounds`). *Fichier ▸ Ouvrir le dossier des sons*, y déposer les fichiers, *Rescanner*. Ils n'y sont plus jamais effacés par un rebuild. |
| Les raccourcis ne se déclenchent pas dans un jeu    | Lancer SoundX en administrateur ; éviter les combinaisons captées par le jeu. |
| La JVM ne se ferme pas                                | Toujours quitter via *Fichier → Quitter* (le shutdown hook retire le hook natif). |
| Le périphérique choisi reste muet                     | Il ne supporte pas le format → SoundX bascule sur la sortie par défaut (voir console). Tester un autre périphérique. |
| Discord n'entend pas ma voix                          | Bloc MICRO : le **vu-mètre *Signal* bouge-t-il quand vous parlez ?** Non → mauvaise entrée sélectionnée (évitez *« Pilote de capture audio principal »* et *« CABLE Output »*), **ou** accès micro bloqué : *Paramètres Windows ▸ Confidentialité ▸ Microphone ▸ Autoriser les applications de bureau*. Oui, mais Discord n'entend rien → SORTIE = `CABLE Input`, Discord entrée = `CABLE Output`. |
| Le vu-mètre *Signal* reste vide                       | Le pilote *« Pilote de capture audio principal »* renvoie souvent du silence pur : choisissez un micro **nommé** dans la liste. Sinon, vérifiez l'accès micro Windows et que le micro n'est pas coupé (touche mute du casque). |
| Discord n'entend pas les sons                         | SORTIE doit pointer sur `CABLE Input` (le même câble que l'entrée Discord). |
| *« Renvoi micro impossible : aucun format commun »*   | Le micro et la sortie n'ont pas de format 16 bits partagé (rare). Essayer une autre entrée, ou 44,1 kHz dans les propriétés Windows du périphérique. |
| Je n'entends pas les sons dans mon casque             | Bloc **ÉCOUTE** : cocher *« Écouter aussi dans »* et choisir votre casque (≠ du périphérique SORTIE). |
| J'entends les sons en double / en écho                | ÉCOUTE et SORTIE pointent sur le même périphérique → décochez l'ÉCOUTE ou changez-en un. |
| Build : `release version 21 not supported`            | JDK trop ancien : installer le JDK 21, ou passer `pom.xml` à `17`.    |

---

## 9. Licences des dépendances

- JNativeHook — GNU GPL v3 **ou** GNU LGPL v3 (le LGPL permet le *linking*).
- JLayer / mp3spi / tritonus-share — LGPL.
- Gson — Apache 2.0.
- FlatLaf — Apache 2.0.

À prendre en compte avant toute redistribution.
