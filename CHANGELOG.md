# Journal des versions

## 1.0.0

Première version utilisable. Tout le périmètre P0 du cahier des charges, plus
l'essentiel des P1 et une partie des P2.

**Lecture**
- Ouverture d'un PDF par le sélecteur système, par partage depuis une autre
  application, ou en ouvrant une pièce jointe (L1).
- Défilement continu, zoom à deux doigts, bande de miniatures, saut de page (L2).
- Recherche dans le document avec surlignage des occurrences et navigation
  d'une occurrence à l'autre (L3) ; mode plein écran (L4).
- Documents protégés : le mot de passe est demandé à l'ouverture, jamais stocké.

**Annotation**
- Surlignage qui épouse les mots quand le document a une couche texte, et
  rectangle libre sinon (A1) ; cinq couleurs.
- Notes ancrées sur un point de la page, modifiables et supprimables (A2).
- Export d'une copie annotée : surlignages dessinés dans la page, notes
  converties en pense-bêtes PDF standard, signature incrustée (A3).
- Supprimer (A4), extraire (A5), fusionner (A6), pivoter (A7), réordonner (A9).
- Signature manuscrite tracée au doigt, rejouée en haute résolution (A8).
- Filigrane texte ou logo : position, rotation, opacité, taille, mosaïque,
  pages choisies (A10).

**Texte, OCR et données**
- Extraction du texte, par page ou en entier, avec partage (T1, T4).
- OCR sur l'appareil (ML Kit embarqué, aucun réseau) en français et anglais (T2).
- « PDF recherchable » : couche de texte invisible posée sur un scan, sans
  altérer son apparence (T3).
- Historique des 50 dernières extractions (T5).
- Photo ou image → PDF, orientation EXIF respectée (T6).
- Détection de tableaux par projection des blancs et export CSV, séparateur
  réglable, BOM pour Excel (T7).

**Bibliothèque**
- Liste triable par date, nom, taille ou nombre de pages (B1).
- Recherche plein texte sur le contenu indexé, en plus du nom (B2, U6).
- Étiquette par document et filtrage (B3).
- Import rapide par le sélecteur ou par partage entrant (B4), partage (B5),
  suppression avec confirmation (B6), statistiques (B7).
- Détection de doublons par taille puis empreinte SHA-256 (P10F).

**Factures électroniques**
- Import d'une archive `.zip` : le PDF et les XML sont appariés (X1).
- Écran « Facture » listant les pièces jointes avec taille et type (X2).
- Visionneuse XML : arborescence repliable, coloration syntaxique, recherche,
  copie d'un nœud (X3).
- Partage du XML avec renommage normalisé `facture-<date>-<émetteur>.xml` (X4).
- Diagnostic explicite des fichiers illisibles : archive, PDF, gzip, binaire,
  encodage exotique, XML mal formé (X5).
- Badge « XML attaché » dans la bibliothèque (X6).
- Champs clés lus dans les formats CII (Factur-X, Chorus Pro) et UBL, avec
  contrôle de cohérence HT + TVA = TTC.

**Métier**
- Masquage de zones, en deux modes explicitement distingués : aplatissement de
  la page (le texte disparaît réellement du fichier) ou rectangle noir posé
  par-dessus (rapide, mais récupérable) (P1F).
- Formulaires AcroForm : détection des champs, saisie, figeage (P2F).
- Lecture structurée d'une facture depuis son texte, avec validation par clé de
  Luhn du SIRET, clé du numéro de TVA et modulo 97 de l'IBAN (P3F).
- Compression en trois profils, décrits par ce qu'ils font perdre (P4F).
- Mot de passe : protection AES 128 ou 256 bits, permissions réglables,
  déverrouillage d'un document dont on a la clé (P5F).
- Page → PNG ou JPEG, résolution réglable (P6F).
- Impression par le service Android (P7F).
- Numérotation des pages (P8F), traitement en lot (P9F), édition des
  métadonnées (P11F).

**Interface**
- Quatre onglets : Bibliothèque, Lecteur, Annotations, Outils (U1).
- Thème sombre par défaut, couleurs dynamiques Material You (U2).
- Écran « Outils » regroupant toutes les actions (U3).
- Notification à la fin d'un traitement long (U4).
- Raccourcis d'appui long sur l'icône (U5).

**Écarts assumés par rapport au cahier des charges**

Trois choix techniques diffèrent de ce que prévoyait le cadrage. Ils sont
détaillés dans le README ; en résumé :

1. **Rendu par `android.graphics.pdf.PdfRenderer`** plutôt qu'un wrapper PDFium
   embarqué. Le moteur système *est* PDFium ; l'utiliser retire une dépendance
   native de plusieurs mégaoctets par architecture et supprime le risque de
   packaging que le cahier des charges classait premier.
2. **SQLite direct plutôt que Room.** Cinq tables, et la seule requête non
   triviale — la recherche FTS avec `snippet()` — doit de toute façon s'écrire à
   la main. Room aurait ajouté un processeur d'annotations sans rien simplifier.
   FTS4 et non FTS5 : FTS5 n'est pas garanti dans le SQLite d'Android 8.
3. **Pas de `WorkManager`.** Les traitements longs tournent dans un scope lié au
   processus : ils survivent aux changements d'écran et à la mise en arrière-plan,
   et notifient à la fin. Seule la mort du processus les interrompt — relancer un
   OCR coûte moins que la mécanique de reprise.

Une quatrième différence est fonctionnelle : l'interface est en français
uniquement. Le cahier des charges ne demandait pas d'autre langue.
