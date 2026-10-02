# DJ Link

Application pour suivre un **XDJ-AZ en mode quatre decks** sur Ethernet et envoyer son tempo à une régie lumière. Détection automatique, titres et artistes, waves couleur, position de lecture, pitch, master, sync, boucles et cues lorsqu’ils sont transmis.

Interface à quatre bandes de decks, compteurs de position et zoom commun de **0,5 à 32 secondes**. Le zoom se règle au curseur, avec +/− ou Ctrl + molette sur une wave. Les réglages lumière s’ouvrent dans un panneau séparé.

## Démarrer

Pour construire : macOS, outils de ligne de commande Xcode (`swiftc`), JDK 17 ou supérieur (`javac`, `jlink`), Python 3. Les dépendances Java proviennent de Maven Central ; aucun framework web à installer.

```sh
python3 build-app.py
open 'target/DJ Link.app'
```

On peut ensuite ouvrir directement **DJ Link.app**, ou utiliser **Lancer DJ Link.command**. L’app contient son runtime Java et ne nécessite plus le JDK pour fonctionner. La construction produit une app pour l’architecture du Mac qui la compile. Il s’agit d’une app locale, sans signature Developer ID ni notarisation.

Branche le Mac et le XDJ-AZ au même réseau. Autorise **DJLink** dans Réglages Système → Confidentialité et sécurité → Réseau local si macOS le demande. Les requêtes Lighting sont retentées après une erreur d’envoi, même si l’autorisation arrive après le démarrage. Le mode quatre decks du XDJ-AZ est conservé. Ferme les autres moniteurs PRO DJ LINK qui occupent les ports UDP 50000–50002. Aucune adresse du lecteur n’est codée dans le projet. Si plusieurs AZ sont présents, l’app garde celui déjà sélectionné, sinon le premier dans l’ordre des adresses.

La fenêtre utilise WebKit natif, avec un moteur Java local. L’app arrête le moteur qu’elle a démarré quand on la quitte. Si un moteur DJ Link tourne déjà sur 8080, elle le réutilise sans l’arrêter. Elle relance son propre moteur en cas d’arrêt inattendu.

Le monitoring reste accessible à **http://localhost:8080** et, sur le LAN, à `http://ADRESSE_DU_MAC:8080`. Les réglages de sortie se modifient uniquement depuis localhost sur le Mac serveur. Le monitoring est destiné à un réseau local de confiance, sans authentification.

Pour utiliser le moteur sans fenêtre macOS :

```sh
python3 build.py
java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -jar target/djlink-1.0.0.jar
```

## Windows

Le moteur Java et l’interface sont communs aux deux plateformes. Le lanceur Windows ouvre une fenêtre d’application Edge dédiée, avec son propre profil, sans barre d’adresse. Il ne ferme pas les autres fenêtres Edge. La version macOS utilise WebKit.

Pour le **PC de régie Windows 11 x64**, télécharge [DJLink-Windows.zip](https://github.com/GaspardMenou/djlink/releases/tag/v1.0.0-beta.1), extrais toute l’archive dans un dossier local puis lance **DJLink\DJLink.exe**. Java est inclus : aucun JDK ni Python à installer. Microsoft Edge doit être présent. Branche le PC sur le même switch que le XDJ-AZ et autorise l’application sur le réseau privé si le pare-feu le demande. Ferme les autres moniteurs PRO DJ LINK qui occupent les ports 50000–50002.

Pour compiler toi-même sur **Windows 10/11**, installe Python 3 et un JDK 17+ avec `javac`, `jlink` et `jpackage` dans PATH. Puis, dans PowerShell :

```powershell
./build-windows.ps1
```

Le résultat est **target/windows/DJLink/DJLink.exe** et **target/DJLink-Windows.zip**. Garde tout le dossier DJLink : l’EXE dépend de son runtime et de ses ressources, déjà intégrés. Il ne nécessite ni Python ni JDK sur le PC destinataire. Les logs sont dans `%LOCALAPPDATA%\DJLink\logs`.

Le workflow **Windows app** construit aussi l’archive Windows et exécute les vérifications Java/JavaScript dans GitHub Actions. Le résultat est disponible dans ses artefacts. La compilation, le packaging jpackage et les vérifications Java/JavaScript ont réussi sur le runner Windows x64 de GitHub. **Le lancement Edge, le MIDI Windows et l’accès au lecteur restent à valider sur le PC de régie.** Pour les sorties MIDI, choisis un port de l’interface ou un bus virtuel existant ; le pilote IAC est spécifique à macOS. Le pare-feu doit autoriser DJ Link sur le réseau local privé.

## Lumière

Les sorties sont désactivées au démarrage. Dans **Régie lumière**, choisis la source du tempo, puis le port et le format MIDI.

- **Daslight 5** : MIDI Clock (24 ticks par beat). Sur Mac, active un bus du pilote IAC dans Configuration audio et MIDI, sélectionne ce bus ici et active son entrée dans Daslight, avec `MIDI Clock Sync` comme source BPM. Entre ordinateurs, utilise une session MIDI réseau ou une interface MIDI.
- **grandMA2** : une note MIDI par beat. Mappe le canal et la note dans `Setup → Remote Input Setup → MIDI Remotes`, type CMD, vers `Learn SpecialMaster 3.1` pour le Speed Master 1. Active les MIDI Remotes. La console doit recevoir cette entrée MIDI ; l’app ne crée pas de connexion native grandMA2 sur Ethernet.
- **OSC** : IPv4 et port UDP configurables. `/djlink/bpm` (float), `/djlink/playing` et `/djlink/deck` (int) à 5 Hz ; `/djlink/beat` (int 1–4) à chaque beat reçu. Il faut un mapping dans le destinataire. OSC n’est pas une entrée native grandMA2.

Le tempo automatique suit le deck master annoncé. Pause, perte de données pendant deux secondes ou BPM invalide coupent la clock. Aucun master n’est inventé. Le compteur OSC compte les envois, sans accusé de réception UDP. Le MIDI reste soumis à la précision de l’ordonnanceur du système ; ce n’est pas une horloge matérielle.

## Waves, boucles et réseau

Les données viennent de PRO DJ LINK et du dbserver du lecteur. Le handshake Lighting permet de demander les états du mode quatre decks. La prise en charge de l’AZ est expérimentale.

- Les bornes exactes de boucle sont utilisées lorsqu’elles sont transmises. Sinon, deux retours identiques dans la grille permettent d’estimer les bornes en beats entiers : **≈ et cadre pointillé**. Les boucles manuelles non alignées et celles inférieures à un beat ne sont pas déterminées précisément par cette estimation.
- Le compteur `h:m:s.ms` affiche une position de morceau. Il est signalé comme estimé lorsqu’il provient de la grille ; ce n’est pas une sortie SMPTE. Les hot cues ne sont affichés que lorsqu’ils sont réellement fournis.
- Le lissage **Direct / Normal / Fort** réduit les petits écarts de position. Les corrections de vitesse sont limitées à ±15 % en Normal et ±8 % en Fort pour empêcher les paquets groupés de secouer la wave. Seeks détectés, changement de morceau, pause et reverse se recalent immédiatement. Les retours de boucle utilisent le modulo. Une interruption est extrapolée au maximum une seconde, puis figée ; la dernière wave reste visible. Le lissage n’altère pas les beats MIDI/OSC.
- Si une récupération de wave a échoué au démarrage, elle est retentée. Sans données disponibles, l’interface l’indique. L’application ne reçoit ni audio ni stems, et ne reconstitue pas les gestes du jog qui ne sont pas transmis par le lecteur.
- La découverte reste active et la connexion est retentée automatiquement après un échec de démarrage. Les caches sont réinitialisés lors d’un changement de lecteur.

## Diagnostic réseau et logs

Le bouton **Réseau** ouvre les compteurs par deck : âge du dernier état, intervalle moyen, gigue, plus grand intervalle, nombre de retards et trous de séquence. Le panneau distingue aussi les retards de beats et une estimation des beats absents, calculée à partir du tempo annoncé et des sauts de phase. Elle est suspendue pour les boucles non confirmées ou non multiples de quatre beats. Ce compteur ne prouve pas une perte sur le câble. Les retards sont des intervalles supérieurs à 500 ms (ou 2,5 fois l’intervalle moyen). Les compteurs de séquence ne sont exploités qu’après vingt incréments unitaires observés. Un intervalle irrégulier ne prouve pas une perte réseau : le lecteur ou le système peuvent aussi retarder l’envoi ou la réception.

Un résumé et les événements anormaux sont enregistrés en JSONL toutes les cinq secondes, hors du thread de réception UDP. Les logs contiennent les timings et les numéros de deck, sans titres ni adresses. Dans l’app : `~/Library/Application Support/DJLink/logs`. Avec le moteur en ligne de commande : `data/`, ou le dossier défini par `-Ddjlink.dataDir=...`. Un fichier par jour, rotation à 10 Mo avec une archive précédente par jour. Une capture temporaire des paquets traités peut être demandée depuis le loopback par `POST /api/capture` avec `{"seconds":20}` (5–60 secondes). Elle enregistre les octets et timestamps reçus dans le log local pour analyser la cadence ; elle n’observe pas les datagrammes qui n’atteignent pas l’app et ne prouve donc pas à elle seule leur perte sur le réseau. Les captures peuvent contenir des identifiants de morceau ; elles restent locales.

Les logs sont exclus du dépôt et ne sont pas téléchargeables sur le réseau ; leur chemin est affiché uniquement depuis le Mac serveur.

## API et vérification

`GET /api/state`, `/api/bpm`, `/api/waveform/1` à `/4`, `/api/midi`. `POST /api/settings` accepte du JSON depuis le loopback avec Host/Origin localhost:8080 ou 127.0.0.1:8080. Les paramètres sont validés et ne sont pas conservés entre deux démarrages.

```sh
./check.sh
```

Vérifications sans framework : décodage AZ/USB 2, BPM, source master, pause et données périmées, encodage OSC, validation des entrées, estimation de boucle, reset de morceau et lissage (jitter, seek, reverse, wrap et interruption). Node.js est facultatif pour le moteur mais nécessaire pour exécuter la vérification JavaScript.

## Sources et licence

Projet indépendant d’AlphaTheta et de TimecodeLink, sous **EPL-2.0**. Voir [THIRD_PARTY.md](THIRD_PARTY.md) pour les dépendances et les trois correctifs apportés à Beat Link. Les bibliothèques conservent leurs licences respectives.

Références : [Beat Link](https://github.com/Deep-Symmetry/beat-link), [analyse PRO DJ LINK](https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html), [Daslight 5](https://www.daslight.com/en/daslight5), [grandMA2 MIDI Remotes](https://help.malighting.com/grandMA2/en/help/key_remote_control_input.html).
