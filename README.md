# Audiobook Player

Lecteur de livres audio hors-ligne, sans serveur, pour fichiers MP3 locaux.

## Application Android native

L’application Kotlin autonome se trouve dans `android-native/`. Elle propose
l’import persistant d’un dossier audio, le scan des livres, la lecture en arrière-plan
avec Media3, la sauvegarde SQLite de la progression, ainsi que la recherche Apple Podcasts,
les abonnements et la synchronisation des épisodes. Les épisodes peuvent aussi être
téléchargés, mis en pause, repris ou annulés; les réglages de stockage, réseau mobile et
fréquence d’actualisation sont disponibles. Le mode Wi-Fi uniquement suspend aussi les
téléchargements en cours ou restaurés si l’appareil passe sur le réseau mobile; les
épisodes ajoutés après confirmation mobile gardent cette autorisation ponctuelle. La file
et l’avancement des téléchargements sont persistés dans SQLite; les téléchargements
interrompus sont remis en file à la prochaine ouverture de l’application, et les fichiers
partiels reprennent si le serveur accepte les requêtes HTTP Range. La lecture d’un épisode
non téléchargé demande aussi confirmation sur réseau mobile si le mode Wi-Fi uniquement
est actif; une confirmation ne lance que l’épisode choisi en streaming. Un service Android
de premier plan affiche une notification et maintient les transferts lorsque l’écran est
quitté ou l’activité fermée; si Android arrête le processus, la file est restaurée à sa
prochaine ouverture. La bibliothèque native
propose la recherche par titre, des filtres lus/non lus/en cours, le tri par titre ou écoute
récente, la reprise du dernier livre, le détail des livres, les chapitres terminés et les
commandes de lecture. La bibliothèque scannée et ses métadonnées sont mises en cache
localement et réutilisées au démarrage et lors des changements d’onglet; le scan du dossier
ne se relance qu’à la demande avec « Actualiser la bibliothèque ». Les listes et fiches
appliquent les métadonnées déjà en cache sans relire les fichiers ni interroger une API.
Le titre choisi dans Open Library complète un titre d’album local manquant; les tags locaux
restent prioritaires. La vitesse de lecture est
réglable de 0,75× à 2× et mémorisée.
Dans l’onglet lecteur, le tiroir « Chapitres » s’ouvre au toucher ou par un glissement
vers le haut. Il affiche la file de lecture, la piste en cours et les chapitres terminés;
sélectionner une piste lance sa lecture et referme le tiroir.
Le détail d’un livre utilise le même tiroir draggable pour ses chapitres, même si ce
livre n’est pas en cours de lecture. La poignée en bas ouvre la liste et ses états
« En cours » et « Terminé »; sélectionner un chapitre lance le livre correspondant.
Les blocs horizontaux « Dans la même série », « Du même auteur » et « Dans les mêmes
genres » proposent uniquement des livres de la bibliothèque, sans inclure le livre ouvert.
Les blocs sans correspondance sont masqués. Une recherche manuelle Open Library enrichit
aussi les sujets (utilisés comme genres) et la série de l’édition lorsque disponibles;
ces informations restent en cache, sans requête lors de l’affichage des suggestions.
Depuis la bibliothèque, « Parcourir la bibliothèque » ouvre une navigation par niveaux :
tous les livres, auteurs, genres ou séries, puis les livres de la catégorie choisie.
Les catégories utilisent les métadonnées en cache et Retour Android remonte l’historique.
Le classement et les suggestions partagent la même normalisation des auteurs, genres
et séries, avec des catégories typées communes à la navigation et aux suggestions.
Les filtres et le tri récent lisent les états de lecture par lots depuis SQLite à chaque
filtrage, sans multiplier les requêtes par livre ni conserver de progression obsolète.
Les couvertures du lecteur ne sont rechargées que lorsque leur source change;
la consultation d’un podcast ne charge que cet abonnement et ses épisodes depuis SQLite.
Le marquage « Terminé » est enregistré à la fin de chaque chapitre, y compris au passage
automatique au suivant et à la fin de la dernière piste.
La lecture continue en arrière-plan, mais s’arrête lorsque l’application est retirée
des applications récentes; la position est sauvegardée pour la prochaine reprise.
Le bouton « Arrêter et fermer le livre » sauvegarde la position, arrête le lecteur,
libère les ressources de lecture et vide la file sans effacer le livre de la bibliothèque.
Le bouton ou geste Retour Android revient à la page précédente; si le tiroir de lecture
est ouvert, il le ferme d’abord. Sans historique, Android reprend son comportement habituel.
Les options natives permettent aussi de choisir un thème clair, sombre ou système et une
palette de couleurs. Les tags présents localement dans les fichiers audio (titre, album,
artiste et pochette) sont lus et mis en cache. Pour un livre sans auteur ou pochette,
l’utilisateur peut lancer une recherche ponctuelle dans Open Library et choisir le résultat
à appliquer; aucune recherche de métadonnées n’est exécutée automatiquement. La recherche
transmet le titre et, s’il est connu, l’auteur à Open Library; les informations choisies
sont mises en cache localement. Une recherche sur le réseau mobile demande confirmation
si le réglage limite l’usage au Wi-Fi. Open Library est la seule source prévue pour les
métadonnées de livres; ses [règles d’utilisation](https://openlibrary.org/developers/api)
et [conseils sur les couvertures](https://openlibrary.org/dev/docs/api/covers) s’appliquent.
L’application fournit un lien de contact dans son `User-Agent` et un lien de crédit vers
Open Library dans le détail des livres enrichis.

Les podcasts utilisent des cartes et des fiches similaires aux livres : couverture,
auteur, description et épisodes avec date, durée et résumé lorsque le flux les fournit.
Les informations viennent uniquement d’Apple Podcasts et du flux RSS et sont conservées
dans SQLite. Les couvertures sont chargées en arrière-plan et mises en cache; les nouvelles
images ne sont pas téléchargées sur réseau mobile si le réglage limite l’usage au Wi-Fi.
« Actualiser » récupère aussi les informations enrichies des abonnements existants.
La vérification des mises à jour consulte les releases GitHub et peut télécharger puis
ouvrir l’installateur Android pour l’APK de la version la plus récente; l’autorisation
d’installation depuis cette application est requise par Android. Le workflow GitHub
Actions publie l’APK natif Kotlin et requiert les secrets `KEYSTORE_BASE64` et
`KEYSTORE_PASSWORD` pour signer toutes les versions avec la même clé.

## Construire la version Kotlin

Depuis la racine du dépôt :
```
$env:JAVA_HOME = '<chemin vers Android Studio>\jbr'
.\android-native\gradlew.bat -p .\android-native :app:assembleDebug
```

Un JDK 17 ou plus récent est nécessaire.

Le dépôt contient uniquement l’application Android native. Les anciennes sources
NativeScript/Angular, leurs ressources Android/iOS et la configuration npm ont été retirées.
Node.js et NativeScript ne sont pas nécessaires pour construire l’application.
L’identifiant `org.nativescript.audiobookplayer` est conservé pour maintenir la continuité
des installations et des données existantes.
