# Graph Report - DJlink  (2026-10-15)

## Corpus Check
- 22 files · ~89,149 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 6 file(s) not represented in the graph (top: (none) 2, .command 1, .icns 1)

## Summary
- 315 nodes · 612 edges · 11 communities (5 shown, 6 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 73 edges (avg confidence: 0.84)
- Token cost: non mesuré pour les agents de cette session. Extraction AST locale ; aucun appel API externe.

## Community Hubs (Navigation)
- Moteur PRO DJ LINK
- Construction et intégrations lumière
- Lanceurs, caches et contrôles
- Dépendances Java et lancement
- Cycle de vie macOS
- Simulateur HTTP des tests
- Régressions du moteur
- API, diagnostic et lissage
- Interface et rendu waves
- Ordonnancement MIDI Clock
- Identité visuelle

## God Nodes (most connected - your core abstractions)
1. `DjLink` - 61 edges
2. `DJ Link` - 29 edges
3. `Exchange` - 23 edges
4. `AppDelegate` - 15 edges
5. `RegressionCheck` - 15 edges
6. `NetworkStats` - 10 edges
7. `CdjStatus` - 9 edges
8. `render()` - 8 edges
9. `LoopWindow` - 7 edges
10. `DeckMotion` - 7 edges

## Surprising Connections (you probably didn't know these)
- `DeckMotion` --implements--> `Lissage Direct / Normal / Fort`  [INFERRED]
  src/main/resources/web/motion.js → README.md
- `DJLink.icns et DJLink.ico` --references--> `Lanceur macOS · DJ Link.app`  [INFERRED]
  assets/icon-prompt.txt → README.md
- `DJLink.icns et DJLink.ico` --references--> `Lanceur Windows · DJLink.exe`  [INFERRED]
  assets/icon-prompt.txt → README.md
- `Panneau Régie lumière` --references--> `MIDI Clock · 24 ticks par beat`  [EXTRACTED]
  src/main/resources/web/index.html → README.md
- `Panneau Régie lumière` --references--> `Note MIDI par beat`  [EXTRACTED]
  src/main/resources/web/index.html → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Tempo du deck master vers les sorties lumière** — readme_deck_state, readme_midi_clock, readme_midi_note_per_beat, readme_osc [EXTRACTED 1.00]

## Communities (11 total, 6 thin omitted)

### Community 0 - "Moteur PRO DJ LINK"
Cohesion: 0.09
Nodes (9): CachedWave, DjLink, LoopWindow, NetworkStats, OutputSource, Settings, WaveRetry, Beat (+1 more)

### Community 1 - "Construction et intégrations lumière"
Cohesion: 0.06
Nodes (24): build(), fetch(), check.sh script, Daslight 5, État des decks et tempo master, DJ Link, Eclipse Public License 2.0, grandMA2 · Speed Master 1 (+16 more)

### Community 2 - "Lanceurs, caches et contrôles"
Cohesion: 0.06
Nodes (27): Windows app · GitHub Actions, AppKit, DJLink.png · icône source, DJLink.icns et DJLink.ico, Runtime Java embarqué · JDK 17+ à la compilation, Lanceur macOS · DJ Link.app, Microsoft Edge · fenêtre dédiée, WebKit natif (+19 more)

### Community 7 - "API, diagnostic et lissage"
Cohesion: 0.14
Nodes (9): Catalogue dbserver du lecteur, API HTTP · port 8080, Logs JSONL locaux · rétention de 30 jours, Diagnostics PRO DJ LINK par deck, Capture locale · POST /api/capture, sourceGeneration, Interface web · régie live quatre decks, Panneau Diagnostic PRO DJ LINK (+1 more)

### Community 8 - "Interface et rendu waves"
Cohesion: 0.19
Nodes (18): animateWaves(), bpm(), colors, drawWave(), esc(), formatTime(), hint(), loadWave() (+10 more)

## Knowledge Gaps
- **18 isolated node(s):** `AppKit`, `WebKit`, `PRO DJ LINK`, `Daslight 5`, `grandMA2 · Speed Master 1` (+13 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 100 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `DJ Link` connect `Construction et intégrations lumière` to `Lanceurs, caches et contrôles`, `API, diagnostic et lissage`?**
  _High betweenness centrality (0.340) - this node is a cross-community bridge._
- **What connects `AppKit`, `WebKit`, `PRO DJ LINK` to the rest of the system?**
  _18 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Moteur PRO DJ LINK` be split into smaller, more focused modules?**
  _Cohesion score 0.08704557091653865 - nodes in this community are weakly interconnected._
- **Why does `Windows app · GitHub Actions` connect `Lanceurs, caches et contrôles` to `Interface et rendu waves`, `Moteur PRO DJ LINK`?**
  _High betweenness centrality (0.245) - this node is a cross-community bridge._
- **Should `Construction et intégrations lumière` be split into smaller, more focused modules?**
  _Cohesion score 0.058673469387755105 - nodes in this community are weakly interconnected._
- **Why does `DjLink` connect `Moteur PRO DJ LINK` to `Ordonnancement MIDI Clock`, `Dépendances Java et lancement`, `Régressions du moteur`, `API, diagnostic et lissage`?**
  _High betweenness centrality (0.232) - this node is a cross-community bridge._
- **Should `Lanceurs, caches et contrôles` be split into smaller, more focused modules?**
  _Cohesion score 0.059379217273954114 - nodes in this community are weakly interconnected._

## Lecture du projet

- Entrées desktop : `macos/DJLink.swift` (WebKit) et `src/main/java/local/djlink/WindowsLauncher.java` (Edge) démarrent ou réutilisent le moteur local.
- Acquisition et état : `src/main/java/local/djlink/DjLink.java` concentre la découverte XDJ-AZ, les statuts et beats PRO DJ LINK, les métadonnées et les caches de waves.
- Monitoring : le moteur expose son API HTTP sur le port 8080 ; `src/main/resources/web/app.js`, `motion.js` et `waves.js` affichent les quatre decks.
- Lumière : les états alimentent MIDI Clock, notes MIDI et OSC. `ClockSchedule.java` règle la cadence des ticks MIDI.
- Diagnostic : compteurs réseau, captures locales et journaux JSONL ; vérifications Java/JavaScript dans `src/test/`.
- Construction : `build.py`, `build-app.py`, `build-windows.ps1` et `.github/workflows/windows.yml` préparent les dépendances, runtimes et applications.

Flux principal : lanceur → moteur DjLink → Beat Link / XDJ-AZ → états et caches → API HTTP → interface ; les mêmes états alimentent MIDI/OSC et le diagnostic.

## Couverture et limites

- Carte statique : les liens issus des documents sont distingués des inférences. Les groupes sont calculés automatiquement et ne constituent pas des frontières de modules. Les contrôles de cette session portent sur la carte ; l'application et le matériel n'ont pas été exécutés.
- `target/`, `.tools/`, `data/` et les caches sont exclus. Les cinq correctifs Beat Link générés par `build.py` sont décrits par `THIRD_PARTY.md`, sans analyse AST du code généré.
- L'extracteur PowerShell n'a produit aucun symbole pour `build-windows.ps1` ; le fichier reste présent comme nœud. CSS, licences sans extension, icônes ICNS/ICO et lanceur `.command` ne sont pas analysés directement.
- Diagnostic de l'extraction brute : 60 relations avec cible absente de la liste brute des nœuds, 4 auto-relations et 36 relations fusionnées par la représentation simple du graphe. Le constructeur peut résoudre les identifiants et créer des références externes ; ces chiffres ne signifient pas que le JSON final contient des liens cassés. Détail dans `GRAPH_HEALTH.txt` et `graph-health.json`.
- Les 36 relations fusionnées perdent des détails de relation ou de localisation. Consulter les sources avant une décision de modification.
- Les tokens des agents ne sont pas exposés par l'outil de délégation. Les zéros techniques dans les caches ne sont pas une mesure de consommation.

- Contrôle du JSON final : 315 identifiants uniques, 612 relations dont toutes les extrémités existent, 11 groupes nommés ; 4 auto-relations conservées. Certaines peuvent être des artefacts de résolution de noms.
- HTML généré et données embarquées vérifiés statiquement. Le rendu navigateur n’a pas été vérifié : l’environnement interdit l’accès automatisé aux URL `file:`. Le fichier peut être ouvert manuellement dans un navigateur ; le visualiseur charge vis-network depuis unpkg.com.
