# Ampli Son

Application Android qui capte le son par le micro du téléphone et l'envoie amplifié
vers le casque ou les écouteurs choisis (par exemple des Huawei FreeClip 2 en Bluetooth).

## Fonctions

- Choix de la sortie parmi les casques connectés (Bluetooth classique, Bluetooth LE Audio, filaire, USB).
- Choix du micro (micro du téléphone, micro filaire ou USB) et du mode de captation
  (standard, réduction de bruit/écho, son brut).
- Gain réglable de −10 à +30 dB, séparément pour l'oreille gauche et l'oreille droite,
  avec coupure par oreille et option « lier G/D ».
- Filtre anti-grondement (coupe les fréquences sous 120 Hz).
- Vu-mètres G/D avec la ligne de seuil.
- Seuil d'alerte réglable (−30 à 0 dBFS) : bandeau rouge et vibration en cas de dépassement,
  limiteur automatique en option (activé par défaut).
- Réglage du volume média du téléphone depuis l'application.
- Le service continue de fonctionner écran éteint (notification avec un bouton « Arrêter »).
- Sécurité : la lecture ne démarre pas sans casque sélectionné, et s'arrête si le casque se
  déconnecte ou si le son bascule sur le haut-parleur du téléphone.

## Compiler l'APK

### Option A : Android Studio
1. Ouvrir le dossier `AmpliSon` dans Android Studio (version Ladybug ou plus récente).
2. Attendre la synchronisation Gradle.
3. Menu **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. L'APK se trouve dans `app/build/outputs/apk/debug/`.

### Option B : GitHub, sans rien installer
1. Créer un dépôt GitHub (privé si vous voulez) et y déposer le contenu de ce dossier.
2. L'onglet **Actions** lance automatiquement la compilation (« Compiler l'APK »).
3. À la fin, télécharger l'artefact **AmpliSon-apk** : il contient l'APK.

## Installer sur le téléphone
1. Copier l'APK sur le téléphone, puis l'ouvrir.
2. Autoriser l'installation depuis cette source quand Android le demande.
3. Au premier démarrage de l'amplification, autoriser l'accès au micro (et aux notifications).

## Conseils d'utilisation
- Commencer avec un gain faible et un volume média bas, puis monter progressivement.
- Le Bluetooth ajoute un retard d'environ 0,1 à 0,3 s. C'est une limite du Bluetooth : une
  connexion filaire en a beaucoup moins.
- Éloigner le téléphone des écouteurs pour éviter le sifflement (larsen).
- Le seuil est un niveau numérique (dBFS) : le volume réel dans l'oreille dépend aussi du volume
  média du téléphone et des écouteurs.

Cette application n'est pas un dispositif médical et ne remplace pas un appareil auditif prescrit.
