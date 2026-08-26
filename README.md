# PDF Box

Boîte à outils PDF **100 % locale** pour Android : lire, annoter, recouper,
fusionner, masquer, extraire du texte, lire des factures électroniques et leurs
XML — sans compte, sans cloud, sans connexion.

> L'application fonctionne en mode avion. Ce n'est pas une intention : les
> permissions réseau que les bibliothèques embarquées déclarent sont **retirées
> de l'APK**, et le système refuse donc toute socket au code de l'application.

## Installation

L'APK signé est publié par la CI à chaque build de la branche par défaut :

**→ [Dernier APK](https://github.com/ClawFabriceH92/pdf-box/releases/latest)**

Android 8.0 (API 26) minimum. L'installation hors Play Store demande d'autoriser
« Installer des applications inconnues » pour le navigateur ou le gestionnaire de
fichiers qui ouvre l'APK.

## Ce que l'application fait

| Écran | Contenu |
|---|---|
| 📚 Bibliothèque | Liste triable, recherche plein texte sur le contenu indexé, étiquettes, doublons, statistiques, import par sélecteur ou par partage. |
| 📖 Lecteur | Défilement continu, zoom à deux doigts, miniatures, recherche dans le document, surlignage, notes, masquage, signature, plein écran. |
| ✏️ Annotations | Tout ce qui a été posé sur le document courant, page par page ; export d'une copie annotée. |
| 🧰 Outils | Fusionner, extraire, supprimer, déplacer, pivoter, numéroter, filigraner, masquer, compresser, protéger, convertir, OCR, tableau → CSV, formulaires, lecture de facture, traitement en lot. |
| 🧾 Facture | PDF et XML attachés côte à côte, champs clés (émetteur, SIRET, TVA, HT/TVA/TTC), visionneuse XML colorée. |

**Toute opération produit un nouveau document.** Aucune n'écrase l'original :
c'est ce qui permet d'essayer un filigrane ou une compression sans crainte, sur
un appareil où il n'y a pas d'annulation.

## Deux régimes de stockage, et pourquoi la distinction compte

- **Document référencé** — choisi via le sélecteur système. Le fichier **reste
  où vous l'avez rangé** ; l'application en mémorise l'accès
  (`takePersistableUriPermission`). Le supprimer de la bibliothèque n'efface que
  la fiche.
- **Document géré** — reçu par partage, ou produit par un outil. Il est **copié**
  dans l'espace privé de l'application, sinon il deviendrait illisible dès la fin
  du partage. Le supprimer efface le fichier.

La bibliothèque affiche l'étiquette « référencé » sur les premiers, et la boîte
de confirmation de suppression dit à chaque fois ce qui va réellement disparaître.

## Permissions

| Permission | Usage |
|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | Signaler la fin d'un OCR ou d'un export long. Refusée, tout fonctionne à l'identique. |

C'est tout. Pas de permission de stockage : l'accès aux fichiers passe
exclusivement par le *Storage Access Framework*, c'est-à-dire par une sélection
explicite de votre part. Pas de permission `CAMERA` : la photo passe par un
intent système, donc par l'appareil photo du système. Pas d'`INTERNET`.

## Ce que l'application ne fait pas, et pourquoi

- **Signature électronique qualifiée (eIDAS).** L'application pose une signature
  *manuscrite* dans la page. Elle n'a aucune valeur probante particulière : une
  signature qualifiée exige un certificat et un dispositif de création, hors de
  portée d'une application locale sans compte.
- **Masquage « visuel » présenté comme sûr.** Le rectangle noir posé par-dessus
  laisse le texte dans le fichier, récupérable en le sélectionnant. L'outil
  propose donc deux modes, en disant ce que chacun garantit ; le mode sûr aplatit
  la page et le texte masqué disparaît réellement.
- **Détection de tableaux sans alignement.** Les colonnes sont retrouvées par la
  géométrie du texte : les blancs verticaux que traversent toutes les lignes. Un
  tableau dont les cellules ne s'alignent pas ne sera pas vu, et un scan sans
  couche texte non plus — il faut l'OCR d'abord.
- **Conversion PDF → Word, synchronisation, corbeille.** Hors périmètre v1.
- **Autres langues d'OCR que le français et l'anglais.** Le modèle latin embarqué
  couvre les deux ; les autres écritures demanderaient d'autres modèles.

## Choix techniques qui s'écartent du cahier des charges

Le cadrage prévoyait trois briques que l'implémentation a remplacées. Chaque
écart est un retrait de dépendance, pas un ajout.

**1. Rendu : `android.graphics.pdf.PdfRenderer` au lieu d'un wrapper PDFium.**
Le moteur de rendu PDF d'Android *est* PDFium, présent dans le système depuis
l'API 21. L'embarquer une seconde fois aurait ajouté plusieurs mégaoctets de
bibliothèque native par architecture — et le cahier des charges classait
justement le packaging de cette bibliothèque comme premier risque du projet. Ce
que le moteur système ne sait pas faire (extraire du texte, modifier la
structure) n'était de toute façon pas son rôle : c'est PDFBox qui s'en charge.

**2. Base : SQLite direct au lieu de Room.** Le schéma tient en cinq tables, et
la seule requête non triviale — la recherche plein texte avec `snippet()` — doit
s'écrire à la main de toute façon, Room ne la générant pas. Room aurait ajouté un
processeur d'annotations et une contrainte de version Kotlin sans rien simplifier.
FTS4 plutôt que FTS5 : FTS5 n'est pas garanti dans le SQLite embarqué d'Android 8,
cible minimale du projet, et le tokenizer `unicode61` de FTS4 indexe le français
correctement (accents repliés, casse ignorée).

**3. Traitements longs : scope applicatif au lieu de `WorkManager`.** L'apport
réel de WorkManager est de survivre à la mort du processus. Ces tâches durent des
dizaines de secondes, et relancer un OCR coûte moins cher que la mécanique de
reprise. Ce qui compte pour l'utilisateur — qu'une tâche continue quand il change
d'onglet ou quitte l'application, et qu'elle l'avertisse en finissant — est assuré.

L'interface est en français uniquement ; le cahier des charges n'en demandait pas
d'autre.

## Bibliothèques

| Brique | Rôle |
|---|---|
| [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) 2.0 (Apache-2.0) | Structure du PDF : pages, texte, formulaires, chiffrement, écriture. |
| ML Kit *text recognition* (bundled) | OCR sur l'appareil. Le modèle est **dans l'APK** : aucun téléchargement, aucun réseau. |
| `android.graphics.pdf.PdfRenderer` | Rendu des pages (PDFium système). |
| Jetpack Compose + Material 3 | Interface. |

## Construire

```bash
./gradlew testDebugUnitTest   # tests unitaires (logique pure : plages de pages,
                              # validations SIRET/TVA/IBAN, détection de tableaux)
./gradlew lintRelease         # les erreurs lint bloquent la CI
./gradlew assembleRelease     # APK signé dans app/build/outputs/apk/release/
```

JDK 17, SDK Android 35. Rien d'autre à installer.

### Signature

L'APK de release est signé avec `app/keystore/pdfbox-public.jks`, dont le mot de
passe est écrit en clair dans `app/build.gradle.kts`. **Ce n'est pas un secret** :
cette clé n'authentifie rien, n'importe qui peut signer un APK avec. Elle n'existe
que pour donner une signature *stable* d'un build à l'autre, sans quoi chaque APK
refuserait de s'installer par-dessus le précédent.

Si les secrets GitHub `PDFBOX_KEYSTORE_B64`, `PDFBOX_KEYSTORE_PASSWORD`,
`PDFBOX_KEY_ALIAS` et `PDFBOX_KEY_PASSWORD` sont définis, la CI utilise cette
clé-là à la place et la clé publique n'est plus employée.

## Licence

MIT — voir [LICENSE](LICENSE).
Le cahier des charges d'origine est conservé dans
[CAHIER_DES_CHARGES.md](CAHIER_DES_CHARGES.md).
