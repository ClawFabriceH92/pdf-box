# PDF Box — Boîte à outils PDF (Android)

**CD v1.0** — cadrage, pas de code écrit jusqu'à validation du périmètre.

## 1. Objectif

Une boîte à outils PDF **100 % sur le téléphone** : ouvrir, annoter, recouper, fusionner, extraire du texte, chercher dans une bibliothèque — sans cloud, sans compte, sans connexion.
Cas d'usage déclencheurl :
- Note de frais / facture reçue en PDF sur le téléphone → annoter, chercher une ligne, extraire la page.
- Plusieurs PDF (extraits de pages, scans) → fusionner en un seul document propre.
- PDF scanné (images) → le rendre **recherchable** (OCR local).
- Retrouver un document dans la bibliothèque par mot-clé, nom, date.

## 2. Principes contraignants

- **Mobile local uniquement** : aucun upload, aucun compte, aucune dépendance réseau (l'app fonctionne en mode avion).
- **Un seul APK**, Kotlin + Jetpack Compose.
- **OCR local** : Google ML Kit (téléphoné sur l'appareil), aucune API cloud.
- **Accès fichiers via SAF** (Storage Access Framework) : pas de permission totale de stockage (API 30+ ne l'impose pas) ; l'utilisateur choisit ses dossiers, l'app les mémorise via `takePersistableUriPermission`.
- **min SDK 26** (Android 8.0), **cible API 34**, orientation libre, thème sombre.
- **Toucheurs PDF** : Android n'a pas de moteur PDF natif →
  - rendu : **PDFium** (lib native Android, via wrapper `pdfium-android` ou équivalent)
  - modif/structure : **Apache PDFBox** (Java, embarqué, licence Apache-2.0) — extraction, fusion, pages, texte
- **Aucun secret** à stocker (pas de login) → pas de keystore requis en v1.

## 3. Périmètre fonctionnel

### Cœur lecteur
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| L1 | Ouvrir un PDF (SAF, share-intent, App Files) | P0 | Pinch-to-zoom, pan, rendu par page |
| L2 | Liste des pages (miniatures) + saut de page | P0 | Navigation rapide |
| L3 | Recherche de texte dans le PDF | P0 | Surligne les occurrences, saut |
| L4 | Lecture plein écran / paysage | P0 | Confort lecture |

### Annotation & modification
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| A1 | Surlignage du texte | P0 | Couleur unique (jaune) en v1, exportable |
| A2 | Note collée sur un passage | P1 | Texte libre, visible, éditable, supprimable |
| A3 | Export annoté (nouveau PDF) | P1 | Copie du PDF avec annotations appliquées |
| A4 | Supprimer des pages | P1 | Pages sélectionnées → nouveau PDF |
| A5 | Extraire des pages → nouveau PDF | P0 | Sélection 1..N |
| A6 | Fusionner plusieurs PDF (ordre réglable) | P0 | Drag & drop du réordonnancement |
| A7 | Rotation d'une page | P2 | Pas de rotation globale en v1 |
| A8 | Signer son nom (dessin main) | P2 | Pose sur une page, export |
| A9 | Déplacer des pages (réordonner) | P0 | Liste des pages en mode tri : glisser-déposer pour réordonner, export nouveau PDF |
| A10 | Ajouter un filigrane | P0 | Texte (ex. « CONFIDENTIEL ») ou image/logo (SAF), position + rotation + opacité, sur toutes ou des pages choisies, export nouveau PDF |

### Texte & OCR
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| T1 | Extraire le texte d'un PDF (copier/tous/par page) | P0 | Via PDFBox |
| T2 | OCR local (ML Kit, FR + EN) sur PDF image | P0 | Rend le PDF searchable + texte copiable |
| T3 | « PDF searchable » : PDF image → PDF texte invisible par-dessus | P1 | Export PDF annoté avec couche texte |
| T4 | Partage du texte extrait (share-intent) | P0 | Vers e-mail / notes / autre app |
| T5 | Historique du texte extrait (derniers 50) | P1 | Liste nom + date + extrait |
| T6 | OCR de la photo (appareil) → PDF 1 page | P2 | Scanner un reçu, sortir un PDF |
| T7 | Extraction tableau → CSV | P0 | Détection du/des tableaux via PDFBox (texte positionné) → export `.csv` (colonnes/lignes alignées) → partage vers Excel/LibreOffice/Drive ; multi-tableaux = 1 fichier ou 1 onglet par table |

### Bibliothèque
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| B1 | Liste des PDF (nom, taille, date, nb pages) | P0 | Tri date/nom/taille, icône par taille |
| B2 | Recherche plein texte (bibliothèque) | P1 | SQLite FTS5 sur texte extrait + annotations |
| B3 | Étiquettes simples (1 tag par doc) | P1 | Ex : « facture », « frais » — filtrage |
| B4 | Ajout rapide (SAF picker + share-intent) | P0 | Depuis une notification / partage d'app tierce |
| B5 | Partage du fichier (intents) | P0 | Vers e-mail, Drive, autre app |
| B6 | Suppression (dossier local, non-muet) | P0 | Confirmation |
| B7 | Statistiques (nb docs, taille totale) | P2 | Écran simple |

### Navigation & UX
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| U1 | 4 onglets : Bibliothèque / Lecteur / Annotations / Outils | P0 | Navigation Material 3 bottom bar |
| U2 | Thème sombre par défaut, auto suit système | P0 | |
| U3 | Écran « Outils » = menu fusion / extraire / déplacer / filigrane / OCR / supprimer pages | P0 | Accès rapide actions |
| U4 | Notifications : OCR terminé, export prêt | P1 | Long OCR = tâche background |
| U5 | Widgets / raccourci app (tact) | P2 | |
| U6 | Recherche globale (recherche + filtre bibliothèque) | P1 | Barre de recherche unifiée |

### Factures & XML (Chorus / PDP / EDI)
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| X1 | Ouvrir une facture `.zip` (PDF + XML) | P0 | Import directement du zip (partage/SAF) → le PDF et le(s) XML sont appariés dans la bibliothèque |
| X2 | Liste des fichiers XML attachés à une facture | P0 | Écran « Facture » : PDF + fichiers XML (Chorus, PDP, EDIFACT, UBL…) avec taille/date |
| X3 | Visionneuse XML (coloration syntaxique) | P0 | Arborescence repliable, recherche dans le XML, copie de nœuds, lecture lisible des champs clés (émetteur, SIRET, date, HT/TVA/TTC, libellés) |
| X4 | Export / partage du XML | P0 | Partage vers e-mail, Drive, autre app ; renommage auto `facture-<date>-<emetteur>.xml` |
| X5 | Détection chiffré/vidange XML | P1 | Signaler un XML illisible (encodage, chiffrement) avec message d'erreur clair |
| X6 | Paire PDF↔XML dans la bibliothèque | P1 | Doc PDF affiché avec badge « XML attaché » ; clic → ouvre la paire |

### Professionnel (métier comptable)
| ID | Fonction | Priorité | Détail |
|---|---|---|---|
| P1F | Masquage confidentialité | P0 | Encadrer des zones (IBAN, SIRET, mentions RGPD) → noirci à l'export ; sélection directe sur le rendu PDFium, export PDF net |
| P2F | Formulaires PDF (AcroForm) | P1 | Détecter les champs, saisir (texte/choix/RF), signer, exporter |
| P3F | OCR structuré facture | P1 | Via ML Kit : émetteur, SIRET, date, HT, TVA, TTC, libellés → carte lisible + export partagé |
| P4F | Compression PDF | P1 | Réduction (image + texte), choix qualité (rapide/standard/sécurité) ; utile avant e-mail |
| P5F | Mot de passe PDF | P1 | Protéger un PDF (chiffrement AES-128/256) ; déverrouiller un PDF protégé (saisie du mdp) |
| P6F | PDF → image | P1 | Page choisie → PNG/JPG (partage, collage, visio) |
| P7F | Impression | P1 | Impression natif Android (Wi-Fi/DLNA) avec aperçu |
| P8F | Numérotation des pages | P2 | Pied de page « p. X/Y » ou numérotation continue |
| P9F | Traitements en lot | P2 | Appliquer compression/filigrane/masquage sur N PDF sélectionnés d'un coup (WorkManager) |
| P10F | Détection doublons | P2 | Dans la bibliothèque (même nom+taille+hash SHA-256) |
| P11F | Métadonnées | P2 | Modifier titre/auteur/sujet/date du PDF |

## 4. Architecture technique

- **Langage** : Kotlin, Android Studio (SDK 34), min 26.
- **UI** : Jetpack Compose + Material 3, `ViewModel` + `Flow` / `StateFlow`.
- **PDF** :
  - Rendu : **PDFium** (lib native — le même moteur que Chrome/Chromium) — via wrapper Kotlin.
  - Manipulation structure : **Apache PDFBox** (dépendance Gradle `org.apache.pdfbox:pdfbox:3.x`).
  - Le rendu et la structure coexistent : c'est le lecteur qui affiche (PDFium), c'est le « writer » qui modifie (PDFBox).
- **OCR** : **Google ML Kit** `com.google.mlkit:ocr-text-recognition` (FR/EN embarquée, on-device, ~18 Mo).
- **Stockage** :
  - Fichiers PDF : dossiers choisis par l'utilisateur (SAF, `takePersistableUriPermission`).
  - Métadonnées (bibliothèque, tags, annotations, FTS) : **Room** (SQLite), `app_data/pdfs_db.db`.
  - Recherche : **SQLite FTS5** (via Room, `@Fts4` ou extension `fts5`).
- **Concurrent / background** : `WorkManager` pour les OCR longs (tâche 30 s+) → notifications.
- **Permissions** :
  - `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` : **non** (SAF uniquement, API 30+).
  - `CAMERA` : optionnelle (P2, scanner photo).
  - `INTERNET` : **non requise** (tout est local — mais ML Kit peut pré-télécharger ses packages → on l'autorise avec mode offline par défaut).
- **Arborescence** (Android)
```
app/
  build.gradle.kts          (PDFBox, PDFium wrapper, ML Kit, Room, Compose)
  src/main/
    AndroidManifest.xml
    java/com/fabrice/pdfbox/
      PdfBoxApp.kt
      di/                    (injection, module Compose)
      feature/library/       (B1-B7)
      feature/reader/        (L1-L4)
      feature/annotate/      (A1-A8)
      feature/ocr/           (T1-T6)
      core/
        pdf/                 (wrappers PDFium + PDFBox)
        ocr/                 (ML Kit wrapper)
        data/                (Room DAO, FTS5, entities)
        util/                (SAF, share-intents, permissions)
      ui/theme/
```

## 5. Sécurité & Confidentialité

- **Zéro réseau** par défaut (aucune URL, aucun endpoint).
- **Zéro secret** (pas de login, pas de PIN en v1).
- Fichiers **restent sur le téléphone** ; rien n'est copié hors de l'app.
- OCR **100 % local** (ML Kit on-device — pas de `GmsApiClient`).
- **Pas de tracking**, pas d'analytics (on l'ose bien).
- Suppression : confirmation explicite, pas de corbeille en v1 (suppression définitive — voir §7 v1.1).

## 6. Écrans
1. **Bibliothèque** — liste docs, barre recherche, boutons ajout/partage, badge « XML attache » (X6).
2. **Lecteur** — PDF plein écran, navigation pages, barre outils (zoom, surligner, masque extraire, filigrane, OCR).
3. **Annotations** — liste des passages annotés de l'open doc, ajout/suppression de note.
4. **Facture** (X1-X4) — PDF + liste XML attachés, visionneuse XML (coloration, recherche), export/partage du XML, champs clés détectés.
5. **Outils** — menu action : fusionner, extraire, déplacer, filigrane, masquer, OCR, **tableau → CSV**, compression, mot de passe.
6. **Métadonnées** — détail d'un doc : taille, pages, date, tags, extraire le texte entier.
7. **Import** — écran « ajouter » : SAF picker / share-intent (PDF, zip facture, XML).
8. **OCR** — écran de progression (background), résultat, share.

## 7. Non-périmétré (v1)

- Conversion **PDF → Word** (nécessaire, mais dépendance LibreOffice non réaliste sur Android).
- **Partage réseau** des PDF (LAN, cloud, e-mail push automatique) → possible v2 via Ktor.
- **Corbeille** (soft-delete) — v1 : suppression définitive avec confirmation.
- **Synchronisation** multi-appareils (pas de cloud).
- **OCR multilingue** au-delà de FR/EN.
- **Détourage auto** (recadrage de facture) — P2.
- **Export DOCX / XLSX** des tableurs extraits.
- **Signature électronique qualifiée** (QWAC) — hors scope, P2 signature dessin uniquement.

## 8. Livrables (v1.0)

- **APK signé debug** (`.apk`, installable sur un Android 8.0+).
- **README** : installation, permissions, exemples d'usage, limitations.
- **Démo vidéo** ou captures d'écran des 7 écrans.
- **CD** (ce document) + **changelog** v1.0.
- **Sources** (Kotlin) — arborescence propre, prêt à builder.

## 9. Critères d'acceptance (v1.0)

Tests concrets (téléphone Android 8.0+ ou émulateur API 26) :

- [ ] Ouvrir un PDF de 50 pages → rendu fluide, zoom, pan.
- [ ] Surligner 3 passages → « Exporter annoté » → nouveau PDF correct.
- [ ] Extraire pages 2-5 → nouveau PDF de 4 pages, ordre correct.
- [ ] Fusionner 2 PDFs (3 pages + 2 pages) → ordre réglable, 5 pages.
- [ ] Déplacer : doc de 4 pages → page 4 en 1re → nouveau PDF, ordre [4,1,2,3], contenu intact.
- [ ] Filigrane texte « CONFIDENTIEL » sur 2 pages choisies → visible, position/rotation/opacité respectées, export correct.
- [ ] Filigrane image (logo PNG importé en SAF) → même vérification.
- [ ] Facture `.zip` (PDF + XML Chorus/PDP) → import → bibliothèque affiche la paire ; écran « Facture » liste le XML, visionneuse colorée, champs clés (HT/TTC/SIRET) visibles.
- [ ] XML illisible/encodage exotique → message d'erreur clair, pas de crash.
- [ ] Masquage : encadrer IBAN → export → le PDF est noirci sur l'IBAN, reste lisible ailleurs.
- [ ] PDF image (scan) → OCR local en < 30 s → texte copiable.
- [ ] PDF avec tableau de 5 colonnes × 8 lignes → « Tableau → CSV » → `.csv` correct (colonnes/lignes alignées), ouvre dans LibreOffice/Excel sans éparpillement.
- [ ] Bibliothèque : 5 docs → recherche « facture » → retrouve le doc avec le texte.
- [ ] Partage d'un PDF depuis une autre app (Partager → PDF Box) → import OK.
- [ ] Mode avion test : toutes les fonctions P0 passent.
- [ ] Suppression d'un doc → confirmation → disparaît du FS.
- [ ] Aucune alerte de permission réseau.
- [ ] Build release sur Android 14 (API 34) → sans crash.

## 10. Versions

- **v1.0** : tous les P0 (dont A5 extraire, A6 fusionner, A9 déplacer, A10 filigrane, P1F masquage, X1-X4 factures XML, T2 OCR, T7 tableau → CSV) — MVP utile.
- **v1.1** : A2 (notes), B2/B3 (recherche + tags), T5 (historique), U4 (notifications), U6 (recherche unifiée), T3 (searchable PDF), A3 (export annoté).
- **v1.2** : A7 (rotation), A8 (signer), T6 (OCR photo), B7 (stats), corbeille (soft-delete).

## 11. Dépendances & risques

| Élément | Risque | Mitigation |
|---|---|---|
| **PDFium wrapper** | Lib native (arm64-v8a, x86_64) — packaging AAB/APK | Utiliser un wrapper connu (`pdfium-android`) ; tester sur émulateur + physique |
| **PDFBox 3.x** | Dépendance Java — taille APK | `abiFilters` + `shrinkResources` ; ~5 Mo OK |
| **ML Kit OCR** | ~18 Mo, download au 1er usage par défaut | Package « on-device » (pas cloud) ; mode offline si `PLAY_STORE` indispo |
| **SAF** | Pas de lecture récursive auto | Demander les dossiers au 1er run ; documenter dans le README |
| **FTS5** | Room natif ne supporte pas FTS5 (que FTS4) | Utiliser `@Fts4` (suffisant) OÙ extension SQLite FTS5 via `androidx.sqlite` |
| **Écran OCR** | Long OCR → ANR / UI figée | `WorkManager` + coroutines + notifications |
| **Taille APK** | 3 engines (PDFium, PDFBox, ML Kit) | v1 ~60-80 Mo acceptable ; `abiFilters arm64-v8a` en release |

---

**Décision requise avant build** :
1. Validation du périmètre P0 (le cœur utile).
2. Avoir les 4 onglets (Bibliothèque / Lecteur / Annotations / Outils) comme maquette UI de base.
3. Nom de l'application (le titre du doc : « PDF Box » est un working title).
