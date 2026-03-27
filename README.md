# MG4Control

> Application Android Automotive pour le contrôle avancé des paramètres de conduite du MG4 électrique.
> Android Automotive app for advanced driving settings control on the MG4 electric vehicle.

---

<details open>
<summary><strong>🇫🇷 Français</strong></summary>

## Table des matières
1. [Présentation](#présentation)
2. [Fonctionnalités](#fonctionnalités)
3. [Compatibilité](#compatibilité)
4. [Architecture](#architecture)
5. [Structure du projet](#structure-du-projet)
6. [Couches matérielles (Katman)](#couches-matérielles-katman)
7. [Système de profils](#système-de-profils)
8. [Interface utilisateur](#interface-utilisateur)
9. [Compilation et installation](#compilation-et-installation)
10. [Permissions requises](#permissions-requises)

---

## Présentation

**MG4Control** est une application système conçue pour Android Automotive OS, destinée à fonctionner sur les écrans de bord des véhicules MG4 équipés du SoC **SAIC MT2712**. Elle offre un accès direct et unifié aux réglages de conduite qui ne sont pas accessibles — ou difficilement accessibles — via l'interface constructeur.

L'application communique avec le véhicule via le SDK propriétaire SAIC, en accédant aux services Android Automotive (`CarPropertyManager`, `CarHvacManager`) ainsi qu'aux services de bas niveau exposés par le firmware du véhicule.

> **Important :** Cette application nécessite des privilèges système (`sharedUserId="android.uid.system"`) et doit être signée avec la clé de la ROM. Elle ne peut pas fonctionner sur un appareil standard débloqué.

---

## Fonctionnalités

### Paramètres de conduite
- **Mode de conduite** : ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Régénération** : Off / Faible / Moyen / Fort / Adaptatif / 1 Pédale

### Climatisation
- **Volant chauffant** : On / Off
- **Sièges chauffants gauche et droit** : Off / Niveau 1 / 2 / 3

### ADAS (Assistance à la conduite)
- **SWI133** : Off / Limiteur / Auto / ACC / ICA + alertes excès de vitesse / changement de limite
- **SWI68** : Désactiver / ACC / TJA + avertissement sonore

### Gestion de profils
- Sauvegarde jusqu'à **5 profils** personnalisés
- Application instantanée d'un profil en un clic
- Application automatique du profil par défaut **au démarrage du véhicule**

### Réglages
- Choix de la langue (Français / English)
- Activation/désactivation de l'application automatique du profil
- Dialog "À propos" avec version de l'app, version firmware et QR code GitHub

---

## Compatibilité

| Élément | Valeur |
|---------|--------|
| Véhicule cible | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Résolution d'écran | 1280 × 480 (orientation paysage forcée) |
| Firmware SWI133 | Compatible ✅ |
| Firmware SWI68 | Compatible ✅ |

---

## Architecture

### Vue d'ensemble

```
┌──────────────────────────────────────────────────────┐
│                    INTERFACE                          │
│  MainActivity ─── NavController ─── Fragment Host   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profils    │  │  Réglages   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                 COUCHE MÉTIER                         │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│            ABSTRACTION MATÉRIELLE (MG4Hardware)       │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│              SERVICES SYSTÈME & BOOT                  │
│      MG4ControlService  ─────  BootReceiver          │
└──────────────────────────────────────────────────────┘
```

### Démarrage de l'application

```
Démarrage véhicule
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
MG4ControlService.onCreate()
  └─ MG4Hardware.init()
  └─ Découverte des services Katman1 / Katman4
  └─ Application du profil par défaut (si activé)
       │
       ▼
MainActivity (IHM)
  └─ Détection du firmware (SWI133 / SWI68)
  └─ Configuration de la top bar
  └─ Navigation vers DashboardFragment
```

---

## Structure du projet

```
MG4Control/
├── app/src/main/
│   ├── java/com/mg4/control/
│   │   ├── MG4App.kt                  # Application — mode nuit, locale
│   │   ├── MainActivity.kt            # Activité principale, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Modèle de données d'un profil
│   │   │   ├── DriveMode.kt           # Enum modes de conduite (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   └── RegenLevel.kt          # Enum niveaux de régénération
│   │   │
│   │   ├── profile/
│   │   │   ├── ProfileManager.kt      # CRUD profils (SharedPreferences + Gson)
│   │   │   └── ProfileApplier.kt      # Application des réglages au véhicule (async)
│   │   │
│   │   ├── hardware/
│   │   │   └── MG4Hardware.kt         # Abstraction matérielle (4 couches)
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Écran principal unifié
│   │   │   ├── ProfileFragment.kt     # Gestion des profils
│   │   │   ├── SettingsFragment.kt    # Réglages & À propos
│   │   │   ├── ProfileAdapter.kt      # Adaptateur RecyclerView profils
│   │   │   ├── ConsoleFragment.kt     # Journal de debug en temps réel
│   │   │   ├── DriveRegenFragment.kt  # Héritage (non utilisé en v2)
│   │   │   ├── ClimateFragment.kt     # Héritage (non utilisé en v2)
│   │   │   └── AdasFragment.kt        # Héritage (non utilisé en v2)
│   │   │
│   │   ├── service/
│   │   │   └── MG4ControlService.kt   # Service de premier plan (boot + auto-apply)
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # Récepteur de démarrage système
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Détection génération firmware (SWI133 / SWI68)
│   │   │   ├── FirmwareHelper.kt      # Lecture version firmware complète (async)
│   │   │   └── LocaleHelper.kt        # Gestion de la langue (FR / EN)
│   │   │
│   │   └── debug/
│   │       └── AppLogger.kt           # Buffer de logs en mémoire (400 entrées)
│   │
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml      # Top bar + NavHostFragment
│   │   │   ├── fragment_dashboard.xml # Écran principal (conduite + climat + alertes)
│   │   │   ├── fragment_profile.xml   # Liste des profils
│   │   │   ├── fragment_settings.xml  # Réglages
│   │   │   ├── item_profile.xml       # Item liste de profil
│   │   │   ├── dialog_profile_edit.xml# Dialog création / édition de profil
│   │   │   └── dialog_app_info.xml    # Dialog "À propos"
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profils / Réglages
│   │   ├── values/strings.xml         # Chaînes FR
│   │   ├── values-en/strings.xml      # Chaînes EN
│   │   └── values/colors.xml          # Palette dash_* (dark theme)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Maquette interactive HTML 1280×480
```

---

## Couches matérielles (Katman)

`MG4Hardware` est organisé en **4 couches d'accès** (« Katman » = couche en turc), du plus haut niveau au plus bas, avec repli automatique en cas d'échec.

### Katman1 — Android Automotive Car API
Couche principale. Utilise les APIs officielles Android Automotive :
- `CarPropertyManager` → modes de conduite, régénération, pédale unique
- `CarHvacManager` → siège chauffant, volant chauffant

La connexion est initialisée par réflexion sur `Car.createCar()` avec plusieurs surcharges tentées successivement. Les actions en attente sont mises en file d'attente et exécutées dès que le service est prêt.

### Katman2 — Raw Binder (fallback)
Repli sur `ServiceManager.getService("vehiclesetting")` avec appels `binderTransact()` directs. Souvent bloqué par SELinux en production.

### Katman4 — Services ADAS (firmware-specific)
Couche dédiée aux fonctions ADAS, chargée dynamiquement selon la génération de firmware :

| Firmware | Service | Mécanisme |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Chargé depuis l'APK launcher via `ClassLoader` + réflexion sur `mIVehiclePropertyService`. Utilise `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Singleton statique chargé via réflexion. Utilise `setAccTjaMode()` / `setLaneKeepingWarningSound()` |

### Détection du firmware

```kotlin
// util/FirmwareInfo.kt
val gen = FirmwareInfo.getGeneration()  // Lit ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN
```

Le résultat est mis en cache et utilisé dans tout l'app pour brancher le code spécifique au firmware.

---

## Système de profils

### Modèle `DrivingProfile`

```kotlin
data class DrivingProfile(
    val id: String,             // UUID unique
    val name: String,           // Nom affiché
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // SWI133 uniquement :
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Lim 2=Auto 3=ACC 4=ICA
    // SWI68 uniquement :
    val soundWarning: Boolean,
    val swi68AdasMode: Int      // Swi68Mode.OFF / ACC / TJA
)
```

### Persistance

Les profils sont sérialisés en JSON via **Gson** et stockés dans `SharedPreferences`. Maximum **5 profils** par appareil.

### Application d'un profil

`ProfileApplier.apply()` exécute les appels matériels dans l'ordre suivant sur `Dispatchers.IO` :
1. Mode de conduite (rapide — binder)
2. Niveau de régénération (rapide — binder)
3. Volant chauffant (~2 s — polling de confirmation d'état)
4. Siège gauche (~7 s — polling par toggle)
5. Siège droit (~7 s — polling par toggle)
6. Attente Katman4 → ADAS (selon firmware)

---

## Interface utilisateur

### Navigation
L'application utilise un **NavController** avec **3 destinations** :

```
DashboardFragment (départ)
    ├──► ProfileFragment  (bouton PROFILS — toggle)
    └──► SettingsFragment (bouton RÉGLAGES — toggle)
```

Un second appui sur PROFILS ou RÉGLAGES ferme la vue et revient au dashboard.

### Dashboard (écran principal)
Disposition en **2 rangées** (ratio 2:1) optimisée pour 1280×480 :
- **Rangée haute (2/3)** : Mode de conduite | Régénération | ADAS
- **Rangée basse (1/3)** : Climatisation (volant + sièges) | Alertes

### Dark theme — palette de couleurs

| Token | Hex | Usage |
|-------|-----|-------|
| `dash_bg` | `#0C0C0E` | Fond général |
| `dash_card` | `#141416` | Cartes |
| `dash_section` | `#1C1C1F` | Sections internes |
| `dash_border` | `#2A2A2E` | Bordures |
| `dash_accent` | `#38BDF8` | Sélection active (bleu) |
| `dash_eco` | `#22C55E` | Mode ECO (vert) |
| `dash_warn` | `#F59E0B` | Mode SPORT (orange) |
| `dash_danger` | `#F43F5E` | Suppression / danger |

---

## Compilation et installation

### Prérequis
- Android Studio Hedgehog (2023.1) ou supérieur
- JDK 17+
- Android SDK API 34

### Build debug

```bash
# Avec le JDK d'Android Studio
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

L'APK se trouve dans :
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installation sur le véhicule

L'application nécessite d'être signée avec la clé système de la ROM. Sur un système de développement :

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> Sur une ROM de production, l'APK doit être incluse dans le build système ou installée via un mécanisme OEM spécifique.

---

## Permissions requises

| Permission | Justification |
|-----------|---------------|
| `FOREGROUND_SERVICE` | Service de premier plan pour l'auto-apply |
| `WAKE_LOCK` | Empêche le sleep pendant l'application des réglages |
| `RECEIVE_BOOT_COMPLETED` | Démarrage automatique au boot |
| `CAR_POWERTRAIN` | Contrôle du mode de conduite et de la régénération |
| `CONTROL_CAR_CLIMATE` | Contrôle des sièges et du volant chauffants |
| `CAR_VENDOR_EXTENSION` | Extensions propriétaires SAIC |
| `CAR_ENERGY` | Informations batterie / motorisation |

</details>

---

<details open>
<summary><strong>🇬🇧 English</strong></summary>

## Table of Contents
1. [Overview](#overview)
2. [Features](#features)
3. [Compatibility](#compatibility)
4. [Architecture](#architecture)
5. [Project Structure](#project-structure)
6. [Hardware Layers (Katman)](#hardware-layers-katman)
7. [Profile System](#profile-system)
8. [User Interface](#user-interface)
9. [Build & Installation](#build--installation)
10. [Required Permissions](#required-permissions)

---

## Overview

**MG4Control** is a system-level application designed for Android Automotive OS, intended to run on the head unit of MG4 electric vehicles equipped with the **SAIC MT2712** SoC. It provides direct, unified access to driving settings that are unavailable — or poorly accessible — through the stock manufacturer interface.

The app communicates with the vehicle through the proprietary SAIC SDK, accessing Android Automotive services (`CarPropertyManager`, `CarHvacManager`) as well as low-level services exposed by the vehicle's firmware.

> **Important:** This application requires system privileges (`sharedUserId="android.uid.system"`) and must be signed with the ROM's platform key. It cannot run on a standard unlocked device.

---

## Features

### Driving Settings
- **Drive mode**: ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Regenerative braking**: Off / Low / Medium / High / Adaptive / One Pedal

### Climate Control
- **Heated steering wheel**: On / Off
- **Heated seats (left & right)**: Off / Level 1 / 2 / 3

### ADAS (Advanced Driver Assistance)
- **SWI133**: Off / Speed Limiter / Auto / ACC / ICA + overspeed alert / speed limit change alert
- **SWI68**: Disable / ACC / TJA + audible warning

### Profile Management
- Save up to **5 custom profiles**
- Instant one-tap profile application
- Automatic default profile application **on vehicle startup**

### Settings
- Language selection (French / English)
- Enable/disable automatic profile application
- "About" dialog showing app version, firmware version, and GitHub QR code

---

## Compatibility

| Item | Value |
|------|-------|
| Target vehicle | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Screen resolution | 1280 × 480 (forced landscape) |
| SWI133 firmware | Supported ✅ |
| SWI68 firmware | Supported ✅ |

---

## Architecture

### Overview

```
┌──────────────────────────────────────────────────────┐
│                      UI LAYER                         │
│  MainActivity ─── NavController ─── Fragment Host    │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profiles   │  │  Settings   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                  BUSINESS LOGIC                       │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│           HARDWARE ABSTRACTION (MG4Hardware)          │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│               SYSTEM SERVICES & BOOT                  │
│       MG4ControlService  ─────  BootReceiver         │
└──────────────────────────────────────────────────────┘
```

### Startup Sequence

```
Vehicle boot
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
MG4ControlService.onCreate()
  └─ MG4Hardware.init()
  └─ Katman1 / Katman4 service discovery
  └─ Apply default profile (if enabled)
       │
       ▼
MainActivity (UI)
  └─ Firmware detection (SWI133 / SWI68)
  └─ Top bar setup
  └─ Navigate to DashboardFragment
```

---

## Project Structure

```
MG4Control/
├── app/src/main/
│   ├── java/com/mg4/control/
│   │   ├── MG4App.kt                  # Application — night mode, locale
│   │   ├── MainActivity.kt            # Main activity, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Profile data model
│   │   │   ├── DriveMode.kt           # Drive mode enum (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   └── RegenLevel.kt          # Regen level enum
│   │   │
│   │   ├── profile/
│   │   │   ├── ProfileManager.kt      # Profile CRUD (SharedPreferences + Gson)
│   │   │   └── ProfileApplier.kt      # Applies settings to vehicle (async)
│   │   │
│   │   ├── hardware/
│   │   │   └── MG4Hardware.kt         # Hardware abstraction (4 layers)
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Unified main screen
│   │   │   ├── ProfileFragment.kt     # Profile management
│   │   │   ├── SettingsFragment.kt    # Settings & About
│   │   │   ├── ProfileAdapter.kt      # Profile list RecyclerView adapter
│   │   │   ├── ConsoleFragment.kt     # Real-time debug log viewer
│   │   │   ├── DriveRegenFragment.kt  # Legacy (unused in v2)
│   │   │   ├── ClimateFragment.kt     # Legacy (unused in v2)
│   │   │   └── AdasFragment.kt        # Legacy (unused in v2)
│   │   │
│   │   ├── service/
│   │   │   └── MG4ControlService.kt   # Foreground service (boot + auto-apply)
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # System boot receiver
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Firmware generation detection (SWI133 / SWI68)
│   │   │   ├── FirmwareHelper.kt      # Full firmware version string reader (async)
│   │   │   └── LocaleHelper.kt        # Language management (FR / EN)
│   │   │
│   │   └── debug/
│   │       └── AppLogger.kt           # In-memory log ring buffer (400 entries)
│   │
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml      # Top bar + NavHostFragment
│   │   │   ├── fragment_dashboard.xml # Main screen (drive + climate + alerts)
│   │   │   ├── fragment_profile.xml   # Profile list
│   │   │   ├── fragment_settings.xml  # Settings screen
│   │   │   ├── item_profile.xml       # Profile list item
│   │   │   ├── dialog_profile_edit.xml# Profile create / edit dialog
│   │   │   └── dialog_app_info.xml    # About dialog
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profiles / Settings
│   │   ├── values/strings.xml         # French strings
│   │   ├── values-en/strings.xml      # English strings
│   │   └── values/colors.xml          # dash_* color palette (dark theme)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Interactive HTML mockup (1280×480)
```

---

## Hardware Layers (Katman)

`MG4Hardware` is organized into **4 access layers** ("Katman" = layer in Turkish), from highest to lowest level, with automatic fallback on failure.

### Katman1 — Android Automotive Car API
Primary layer. Uses official Android Automotive APIs:
- `CarPropertyManager` → drive modes, regeneration, one-pedal
- `CarHvacManager` → seat heating, steering wheel heating

The connection is initialized via reflection on `Car.createCar()` with multiple overloads tried in sequence. Pending actions are queued and executed once the service is ready, with exponential backoff retry (2 s → 60 s).

### Katman2 — Raw Binder (fallback)
Falls back to `ServiceManager.getService("vehiclesetting")` with direct `binderTransact()` calls. Usually blocked by SELinux in production builds.

### Katman4 — ADAS Services (firmware-specific)
Dedicated layer for ADAS functions, dynamically loaded according to the detected firmware generation:

| Firmware | Service | Mechanism |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Loaded from the launcher APK via `ClassLoader` + reflection on `mIVehiclePropertyService`. Uses `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Static singleton loaded via reflection. Uses `setAccTjaMode()` / `setLaneKeepingWarningSound()` |

### Firmware Detection

```kotlin
// util/FirmwareInfo.kt
val gen = FirmwareInfo.getGeneration()  // Reads ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN
```

The result is cached and used throughout the app to branch firmware-specific code paths.

---

## Profile System

### `DrivingProfile` Model

```kotlin
data class DrivingProfile(
    val id: String,             // Unique UUID
    val name: String,           // Display name
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // SWI133 only:
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Limiter 2=Auto 3=ACC 4=ICA
    // SWI68 only:
    val soundWarning: Boolean,
    val swi68AdasMode: Int      // Swi68Mode.OFF / ACC / TJA
)
```

### Persistence

Profiles are serialized to JSON via **Gson** and stored in `SharedPreferences`. Maximum **5 profiles** per device.

### Applying a Profile

`ProfileApplier.apply()` executes hardware calls in the following order on `Dispatchers.IO`:
1. Drive mode (fast — binder call)
2. Regen level (fast — binder call)
3. Heated steering wheel (~2 s — state confirmation polling)
4. Left seat heating (~7 s — toggle polling)
5. Right seat heating (~7 s — toggle polling)
6. Wait for Katman4 → ADAS (firmware-dependent)

---

## User Interface

### Navigation
The app uses a **NavController** with **3 destinations**:

```
DashboardFragment (start)
    ├──► ProfileFragment  (PROFILS button — toggle)
    └──► SettingsFragment (RÉGLAGES button — toggle)
```

A second press on PROFILS or RÉGLAGES closes the view and returns to the dashboard.

### Dashboard (main screen)
**2-row layout** (2:1 weight ratio) optimized for 1280×480:
- **Top row (2/3 height)**: Drive mode | Regeneration | ADAS
- **Bottom row (1/3 height)**: Climate (steering + seats) | Alerts

### Dark Theme — Color Palette

| Token | Hex | Usage |
|-------|-----|-------|
| `dash_bg` | `#0C0C0E` | App background |
| `dash_card` | `#141416` | Cards |
| `dash_section` | `#1C1C1F` | Inner sections |
| `dash_border` | `#2A2A2E` | Borders |
| `dash_accent` | `#38BDF8` | Active selection (blue) |
| `dash_eco` | `#22C55E` | ECO mode (green) |
| `dash_warn` | `#F59E0B` | SPORT mode (amber) |
| `dash_danger` | `#F43F5E` | Delete / danger actions |

---

## Build & Installation

### Prerequisites
- Android Studio Hedgehog (2023.1) or later
- JDK 17+
- Android SDK API 34

### Debug Build

```bash
# Using Android Studio's bundled JDK
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

Output APK location:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installing on the Vehicle

The application must be signed with the ROM's system key. On a development system:

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> On a production ROM, the APK must be included in the system build or installed through an OEM-specific mechanism.

---

## Required Permissions

| Permission | Reason |
|-----------|--------|
| `FOREGROUND_SERVICE` | Persistent foreground service for auto-apply |
| `WAKE_LOCK` | Prevents sleep during settings application |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on vehicle boot |
| `CAR_POWERTRAIN` | Drive mode and regeneration control |
| `CONTROL_CAR_CLIMATE` | Seat and steering wheel heating control |
| `CAR_VENDOR_EXTENSION` | SAIC proprietary extensions |
| `CAR_ENERGY` | Battery / powertrain information |

---

## Credits

Made with ❤ by **SliDeeN** and **Claude IA**

[![GitHub](https://img.shields.io/badge/GitHub-SliDeeN%2FMG4Control-181717?logo=github)](https://github.com/SliDeeN/MG4Control)

</details>
