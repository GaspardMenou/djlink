# Bibliothèques et correctifs

DJ Link utilise [Beat Link 8.0.0](https://github.com/Deep-Symmetry/beat-link), copyright © 2016–2025 Deep Symmetry, LLC, sous Eclipse Public License 2.0. Son code source et ses notices sont conservés dans les sources téléchargées et l’archive distribuée. Ce projet est indépendant d’AlphaTheta.

`build.py` génère trois modifications ciblées des sources de Beat Link 8.0.0 avant compilation :

- `VirtualCdj` accepte aussi les états Lighting `0x10` du XDJ-AZ.
- `CdjStatus.TrackSourceSlot` conserve la valeur `7` de la seconde clé USB du XDJ-AZ en mode quatre decks.
- `TimeFinder` vérifie l’absence de position avant de lire `precise`.

Le handshake Lighting reprend les structures publiques de `VirtualRekordbox` de Beat Link. Les champs d’adresse, de MAC et d’identifiant sont calculés à l’exécution. Les classes modifiées et ces structures restent régies par EPL-2.0. La génération s’arrête si le code source ne correspond pas exactement aux correctifs attendus.

Le projet est distribué sous EPL-2.0 (voir LICENSE). Les sources modifiées et l’archive de sources Beat Link sont aussi conservées dans META-INF/sources du JAR. Les notices présentes dans chaque dépendance sont recopiées sous META-INF/licenses, sans écrasement entre bibliothèques.

Autres bibliothèques téléchargées depuis Maven Central (leurs licences propres continuent de s’appliquer) :

| Bibliothèque | Version | Licence |
| --- | --- | --- |
| electro | 0.1.4 | EPL-2.0 |
| crate-digger | 0.2.1 | EPL-2.0 |
| commons-math3 | 3.6.1 | Apache-2.0 |
| remotetea-oncrpc | 1.1.4 | LGPL-2.1 |
| kaitai-struct-runtime | 0.10 | MIT |
| sqlite-jdbc (willena) | 3.49.0.0 | Apache-2.0 / BSD pour les composants SQLite |
| apiguardian-api | 1.1.2 | Apache-2.0 |
| slf4j-api, slf4j-simple | 1.7.36 | MIT |
| Gson | 2.13.2 | Apache-2.0 |

Références du protocole : [états détaillés](https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html), [requêtes de métadonnées](https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html).
