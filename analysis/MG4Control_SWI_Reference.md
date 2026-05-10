# MG4Control — Référence technique par firmware SWI / Technical Reference by SWI Firmware
> Source : analyse smali (JADX) + vérification croisée avec `MG4Hardware.kt`
> Firmwares couverts : SWI133 · SWI68 · SWI165 · SWI69 · SWI131 · SWI132
> Dernière mise à jour : 2026-05-10
> Statut : SWI133 ✅ | SWI68 ✅ | SWI69 ✅ | SWI131 ✅ | SWI132 ✅ | SWI165 ✅

---

<!-- ════════════════════════════════════════════════════════════
     FRANÇAIS
     ════════════════════════════════════════════════════════════ -->

# 🇫🇷 Version française

## 1. Architecture en couches (Katman)

| Couche | Nom | Description |
|---|---|---|
| **Katman1** | `android.car.Car` (AAOS) | CarPropertyManager + CarHvacManager — accès standard AAOS |
| **Katman2** | Binder direct `vehiclesetting` | `ServiceManager.getService("vehiclesetting")` → `IVehicleSettingService` |
| **Katman4** | VPM / VSM / CarVehicleSettingClient | Service OEM principal selon firmware (voir tableau ci-dessous) |
| **Katman5** | VehicleConditionManager / ICarGeneralService | Détection état allumage (ignition push) |

### Katman4 par firmware

| Firmware | Classe | Accès | Constante |
|---|---|---|---|
| SWI133 | `VehiclePropertyManager` (VPM) | Launcher SWI133 context → singleton | `LAUNCHER_PKG` = `com.saicmotor.hmi.launcher` |
| SWI68 / SWI165 | `VehicleSettingManager` (VSM) | Launcher SWI68 context → singleton | `LAUNCHER68_PKG` = `com.saicmotor.hmi.launcher` |
| SWI69 / SWI131 / SWI132 | `CarVehicleSettingClient` (VSM69) | `CarAdapterClient.queryClient(0x8)` | `VSM_SERVICE_CODE` = `0x8` |

### Katman5 par firmware

| Firmware | Service | Classe |
|---|---|---|
| SWI133 / SWI68 / SWI165 | `VehicleConditionManager` (via `IHubService`) | `com.saicmotor.sdk.vehiclesettings.manager.VehicleConditionManager` |
| SWI69 / SWI131 / SWI132 | `ICarGeneralService` (via `CarAdapterClient.queryClient(0x1)`) | `com.saicmotor.carapi.client.CarGeneralClient` |

---

## 2. Détection firmware — `FirmwareInfo.kt`

### Détection automatique
Lecture du `Build.VERSION.INCREMENTAL` au démarrage. Ordre d'évaluation :

```
startsWith("SWI133") → Gen.SWI133
startsWith("SWI132") → Gen.SWI132   ← avant SWI131 !
startsWith("SWI131") → Gen.SWI131
startsWith("SWI165") → Gen.SWI165
startsWith("SWI69")  → Gen.SWI69
startsWith("SWI68")  → Gen.SWI68
sinon                → Gen.UNKNOWN
```

### Helpers de regroupement

| Méthode | Firmwares inclus | Usage |
|---|---|---|
| `isVsmBased()` | SWI68, SWI69, SWI131, SWI132, SWI165 | API VSM disponible (hors SWI133) |
| `isNewGenVsm()` | SWI69, SWI131 | CarVehicleSettingClient via CarAdapterClient (sans SWI132 !) |
| `hasHeatFeatures()` | SWI133, SWI68, SWI165 | Siège/volant chauffants disponibles |

> ⚠️ SWI132 n'est **pas** dans `isNewGenVsm()` — utilise le même CarVehicleSettingClient mais est géré avec une branche dédiée `gen == SWI132` pour éviter les régressions.

---

## 3. Descriptors AIDL

| Constante | Valeur | Utilisé par |
|---|---|---|
| `DESCRIPTOR_VEHICLE` | `com.saicmotor.sdk.vehiclesettings.IVehicleSettingService` | SWI133 (ELK binder direct) |
| `DESCRIPTOR_VSM132` | `com.saicmotor.vehiclesetting.IVehicleSettingService` | SWI132 (alertes/TSR binder direct) |

---

## 4. SWI133

### 4.1 Architecture
- **Katman4** : `VehiclePropertyManager` (VPM) — via launcher `com.saicmotor.hmi.launcher`
- **Katman5** : `VehicleConditionManager`
- **Binder direct** : `sVehicleBinder` (vehiclesetting) — uniquement pour ELK

### 4.2 Constantes Property IDs (VPM)

| Fonctionnalité | Constante | Hex | Décimal | Valeurs |
|---|---|---|---|---|
| Mode de conduite | `PROP_DRIVE_MODE` | `0x2140A17C` | 557883772 | 0=Neige, 1=Eco, 3=Normal, 4=Sport |
| Régénération | `PROP_REGEN_LEVEL` | `0x2140A191` | 557883793 | 0=Faible, 1=Moyen, 2=Élevé, 3=Adaptatif |
| One Pedal | `PROP_ONE_PEDAL` | `0x2140A193` | 557883795 | 0=OFF, 1=ON |
| AEB activé | `PROP_AEB_SWITCH` | `0x2140A108` | 557883528 | 1=OFF, 2=ON (CPM) |
| AEB mode | `PROP_AEB_SYS_MODE` | `0x302000A` | 50397194 | 1=Alerte, 2=Alerte+Freinage (VPM) |
| AEB mode (bis) | `PROP_AEB_MODE` | `0x302000B` | 50397195 | 1=Alerte, 2=Alerte+Freinage (VPM) |
| AEB sensibilité | `PROP_AEB_SENSITIVITY` | `0x302000E` | 50397198 | 1=Faible, 2=Standard, 3=Élevé |
| TSR / SLIF | `PROP_TSR_MODE` | `0x5030049` | 83951689 | 0=OFF, 1=ON |
| Survitesse | `PROP_OVERSPEED_ALARM` | `0x503004E` | 83951694 | 0=OFF, 1=ON |
| Ton limite | `PROP_SPEED_LIMIT_TONE` | `0x503004F` | 83951695 | 0=OFF, 1=ON |
| ADAS mixte | `PROP_MIX_INTELLIGENT_DRIVE` | `0x32` | 50 | 0=Off, 1=Lim, 2=Auto, 3=ACC, 4=ICA |
| Éco énergie | `PROP_ENERGY_SAVING` | `0x5030007` | 83951623 | 0=OFF, 1=ON |

### 4.3 Binder direct — ELK (TX codes SWI133)

| Opération | TX code | Flag | Méthode AIDL |
|---|---|---|---|
| GET mode ELK | `0x53` | two-way | `getLaneKeepingAsstMode()` |
| SET mode ELK | `0x54` | ONEWAY | `setLaneKeepingAsstMode(I)` |
| GET sensibilité ELK | `0x55` | two-way | `getLaneKeepingAsstSen()` |
| SET sensibilité ELK | `0x56` | ONEWAY | `setLaneKeepingAsstSen(I)` |

**Valeurs ELK** : 1=OFF · 2=Alerte (LDW) · 3=Assist (LDP) · 5=Urgence (ELK)
**Valeurs sensibilité** : 1=Faible · 2=Standard · 3=Élevé

### 4.4 Comportement TSR connu
> Activer le TSR **réactive** automatiquement `OVERSPEED_ALARM` et `SPEED_LIMIT_TONE`.
> → `setTsrMode()` sauvegarde les valeurs avant l'appel et les restaure après.

### 4.5 ADAS — boutons SWI133
5 boutons : **Off / Limiteur / Auto / ACC / ICA** (valeurs 0→4 dans `PROP_MIX_INTELLIGENT_DRIVE`)

---

## 5. SWI68 / SWI165

### 5.1 Architecture
- **Katman4** : `VehicleSettingManager` (VSM) — via launcher `com.saicmotor.hmi.launcher`
- **Katman5** : `VehicleConditionManager`
- **Chauffage** : disponible (siège + volant)

> SWI165 utilise **exactement le même SDK que SWI68** → traitement identique dans le code.

### 5.2 Méthodes VSM — appels via `callVsm(methodName, ...)`

| Fonctionnalité | Méthode GET | Méthode SET | Valeurs |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaMode()` | `setAccTjaMode(I)` | OFF=4, ACC=1, TJA=2 |
| AEB activé | `getFcwAlarmMode()` | `setFcwAlarmMode(I)` | 0=OFF, 1=ON |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alerte, 2=Alerte+Freinage |
| AEB sensibilité | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Faible, 2=Standard, 3=Élevé |
| ELK mode | `getLaneKeepingAsstMode()` | `setLaneKeepingAsstMode(I)` | 1=OFF, 2=Alerte, 3=Assist, 5=Urgence |
| ELK sensibilité | `getLaneKeepingAsstSen()` | `setLaneKeepingAsstSen(I)` | 1=Faible, 2=Standard, 3=Élevé |
| Alerte sonore | `getSoundWarning()` | `setSoundWarning(I)` | 0=OFF, 1=ON |
| TSR / SLIF | `getSpeedAsstSlifWarning()` | `setSpeedAsstSlifWarning(I)` | 0=OFF, 1=ON |
| Éco énergie | `getLongerEndurance()` | `setLongerEndurance(I)` | 0=OFF, 1=ON |

### 5.3 ADAS — boutons SWI68/SWI165
3 boutons : **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`

### 5.4 HVAC — Property IDs (CarHvacManager / CarPropertyManager)

| Fonctionnalité | Constante | Hex | Valeurs |
|---|---|---|---|
| Volant chauffant | `PROP_STEERING_HEAT` | `0x1540253A` | 0=OFF, 1-3=Niveau |
| Siège gauche | `PROP_SEAT_HEAT_L` | `0x15402513` | 0=OFF, 1-3=Niveau |
| Siège droit | `PROP_SEAT_HEAT_R` | `0x15402514` | 0=OFF, 1-3=Niveau |

---

## 6. SWI69 / SWI131

### 6.1 Architecture
- **Katman4** : `CarVehicleSettingClient` via `CarAdapterClient.queryClient(0x8)`
- **Katman5** : `ICarGeneralService` via `CarAdapterClient.queryClient(0x1)`
- **Chauffage** : non disponible (pas de `hasHeatFeatures()`)

> SWI131 est identique à SWI69 — même APK launcher, même SDK, même méthodes.

### 6.2 Méthodes CarVehicleSettingClient — appels via `callVsm(methodName, ...)`

| Fonctionnalité | Méthode GET | Méthode SET | Valeurs |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaState()` | `setAccTjaState(I)` | OFF=4, ACC=1, TJA=2 |
| AEB activé | `getFcwState()` | `setFcwState(I)` | 1=OFF, **2=ON** |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alerte, 2=Alerte+Freinage |
| AEB sensibilité | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Faible, 2=Standard, 3=Élevé |
| ELK mode | `getLasMode()` | `setLasMode(I)` | 1=OFF, 2=Alerte, 3=Assist, 5=Urgence |
| ELK sensibilité | `getLasSensitivity()` | `setLasSensitivity(I)` | 1=Faible, 2=Standard, 3=Élevé |
| Alerte sonore | `getSoundWarning()` | `setSoundWarning(I)` | 0=OFF, 1=ON |
| TSR / SLIF | `getSLIFWarningState()` | `setSLIFWarningState(I)` | **0=ON, 1=OFF** (inversé !) |
| Éco énergie | `getEnduranceMode()` | `setEnduranceMode(I)` | 0=OFF, 1=ON |

> ⚠️ **Convention TSR inversée** : `getSLIFWarningState()` retourne **0=ON et 1=OFF** — à l'inverse de tous les autres firmwares. `isTsrOn()` retourne `== 0`.

### 6.3 TSR — comportement connu
> Activer le TSR **restaure** `soundWarning` depuis les préférences internes.
> → Appliquer TSR en premier, réécrire `soundWarning` après dans `ProfileApplier`.

### 6.4 ADAS — boutons SWI69/SWI131
3 boutons : **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`
Méthode : `getAccTjaState` / `setAccTjaState` (différent de SWI68 qui utilise `getAccTjaMode`)

---

## 7. SWI132

### 7.1 Architecture
- **Katman4** : `CarVehicleSettingClient` via `CarAdapterClient.queryClient(0x8)` — **même stack que SWI69**
- **Katman5** : `ICarGeneralService` via `CarAdapterClient.queryClient(0x1)` — **même stack que SWI69**
- **Binder direct** : `sVehicleBinder` (vehiclesetting) — pour TSR + alertes survitesse/limite
- **Chauffage** : non disponible
- **Alertes** : deux alertes indépendantes (survitesse + ton limite) — **pas de `soundWarning` VSM**

### 7.2 Binder direct — TX codes SWI132

Descriptor : `com.saicmotor.vehiclesetting.IVehicleSettingService`
Flag : **two-way `0x0`** (différent du flag ONEWAY de SWI133 pour ELK)
Layout Parcel : `writeInterfaceToken(descriptor)` → `writeInt(value)` → `transact` → `readException()` → `readInt()`

| Opération | TX code | Méthode AIDL | Valeurs |
|---|---|---|---|
| GET TSR | `0x058` | `getSLIFWarningState()` | 0=OFF, **1=ON** |
| SET TSR | `0x057` | `setSLIFWarningState(I)` | 0=OFF, 1=ON |
| GET survitesse | `0x129` | `getOverSpeedSoundMode()` | 0=OFF, 1=ON |
| SET survitesse | `0x128` | `setOverSpeedSoundMode(I)` | 0=OFF, 1=ON |
| GET ton limite | `0x12B` | `getSpeedLimitSoundMode()` | 0=OFF, 1=ON |
| SET ton limite | `0x12A` | `setSpeedLimitSoundMode(I)` | 0=OFF, 1=ON |

> ⚠️ **Convention TSR normale** (contrairement à SWI69/SWI131) : `getSLIFWarningState()` retourne **1=ON** (pas d'inversion).

### 7.3 Méthodes CarVehicleSettingClient (Katman4) — identiques à SWI69

| Fonctionnalité | Méthode GET | Méthode SET | Valeurs |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaState()` | `setAccTjaState(I)` | OFF=4, ACC=1, TJA=2 |
| AEB activé | `getFcwState()` | `setFcwState(I)` | 1=OFF, **2=ON** |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alerte, 2=Alerte+Freinage |
| AEB sensibilité | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Faible, 2=Standard, 3=Élevé |
| ELK mode | `getLasMode()` | `setLasMode(I)` | 1=OFF, 2=Alerte, 3=Assist, 5=Urgence |
| ELK sensibilité | `getLasSensitivity()` | `setLasSensitivity(I)` | 1=Faible, 2=Standard, 3=Élevé |
| Éco énergie | `getEnduranceMode()` | `setEnduranceMode(I)` | 0=OFF, 1=ON |

### 7.4 ADAS — boutons SWI132
3 boutons : **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`
(identique à SWI68/SWI69/SWI131/SWI165 — pas de Limiteur/ICA)

### 7.5 Différences clés SWI132 vs SWI69/SWI131

| Point | SWI69/SWI131 | SWI132 |
|---|---|---|
| TSR convention | `0=ON, 1=OFF` (inversé) | `0=OFF, 1=ON` (normal) |
| TSR accès | Via `callVsm("setSLIFWarningState")` | Via binder direct TX `0x057` |
| Alertes survitesse/limite | Alerte unique `soundWarning` | Deux alertes indépendantes via binder TX `0x128` / `0x12A` |
| AEB enabled check | `getFcwState() == 2` | `getFcwState() == 2` ✅ identique |

---

## 8. Tableau récapitulatif — méthodes par firmware

### Mode de conduite & Régénération
> Commun à tous les firmwares via `CarPropertyManager` (PROP_DRIVE_MODE / PROP_REGEN_LEVEL)

| Firmware | DriveMode | RegenLevel | One Pedal |
|---|---|---|---|
| Tous | CPM `0x2140A17C` | CPM `0x2140A191` | CPM `0x2140A193` |

**Valeurs DriveMode** : 0=Neige, 1=Eco, 3=Normal, 4=Sport
**Valeurs RegenLevel** : 0=Faible, 1=Moyen, 2=Élevé, 3=Adaptatif, +ONE_PEDAL

### AEB (résumé)

| Firmware | isAebEnabled | setAebEnabled | getAebMode | setAebMode |
|---|---|---|---|---|
| SWI133 | CPM `PROP_AEB_SWITCH == 2` | CPM + VPM reset | VPM `PROP_AEB_MODE` | VPM dual write |
| SWI68 / SWI165 | VSM `getFcwAlarmMode() != 0` | VSM `setFcwAlarmMode` | VSM `getFcwAutoBrakeMode` | VSM `setFcwAutoBrakeMode` |
| SWI69 / SWI131 / SWI132 | VSM69 `getFcwState() == 2` | VSM69 `setFcwState` | VSM69 `getFcwAutoBrakeMode` | VSM69 `setFcwAutoBrakeMode` |

### TSR (résumé)

| Firmware | isTsrOn | setTsrMode | Convention |
|---|---|---|---|
| SWI133 | VPM `PROP_TSR_MODE > 0` | VPM set + restauration alertes | Normal (1=ON) |
| SWI68 / SWI165 | VSM `getSpeedAsstSlifWarning() == 1` | VSM `setSpeedAsstSlifWarning` | Normal (1=ON) |
| SWI69 / SWI131 | VSM69 `getSLIFWarningState() == 0` | VSM69 `setSLIFWarningState` | **Inversé (0=ON)** |
| SWI132 | Binder `TX 0x058 == 1` | Binder `TX 0x057` | Normal (1=ON) |

### Alertes survitesse / ton limite (résumé)

| Firmware | Type | Accès |
|---|---|---|
| SWI133 | Deux alertes indépendantes | VPM `PROP_OVERSPEED_ALARM` / `PROP_SPEED_LIMIT_TONE` |
| SWI68 / SWI165 / SWI69 / SWI131 | Alerte unique `soundWarning` | VSM `getSoundWarning` / `setSoundWarning` |
| SWI132 | Deux alertes indépendantes | Binder TX `0x129/0x128` / `0x12B/0x12A` |

### ELK (résumé)

| Firmware | Méthodes GET/SET mode | Méthodes GET/SET sensibilité |
|---|---|---|
| SWI133 | Binder TX `0x53` / `0x54` | Binder TX `0x55` / `0x56` |
| SWI68 / SWI165 | VSM `getLaneKeepingAsstMode` / `set...` | VSM `getLaneKeepingAsstSen` / `set...` |
| SWI69 / SWI131 / SWI132 | VSM69 `getLasMode` / `setLasMode` | VSM69 `getLasSensitivity` / `setLasSensitivity` |

### Éco énergie (résumé)

| Firmware | GET | SET |
|---|---|---|
| SWI133 | VPM `PROP_ENERGY_SAVING` | VPM `setIntPropertyVpmRecovery` |
| SWI68 / SWI165 | VSM `getLongerEndurance()` | VSM `setLongerEndurance(I)` |
| SWI69 / SWI131 / SWI132 | VSM69 `getEnduranceMode()` | VSM69 `setEnduranceMode(I)` |

---

## 9. Constantes globales

### Objets valeurs (MG4Hardware)

```kotlin
object Swi68Mode      { OFF=4, ACC=1, TJA=2 }
object ElkMode        { OFF=1, ALERT=2, ASSIST=3, EMERGENCY=5 }
object ElkSensitivity { LOW=1, STANDARD=2, HIGH=3 }
object AebMode        { ALARM=1, ALARM_BRAKE=2 }
object AebSensitivity { LOW=1, STANDARD=2, HIGH=3 }
object CarIgnitionItem { OFF=0, ACCESSORY=1, RUN=2, CRANK=3 }
```

### HVAC Property IDs (tous firmwares compatibles)

| Fonctionnalité | Constante | Hex | Valeurs |
|---|---|---|---|
| Volant chauffant | `PROP_STEERING_HEAT` | `0x1540253A` | 0=OFF, 1-3=Niveau |
| Siège gauche | `PROP_SEAT_HEAT_L` | `0x15402513` | 0=OFF, 1-3=Niveau |
| Siège droit | `PROP_SEAT_HEAT_R` | `0x15402514` | 0=OFF, 1-3=Niveau |

> Disponibles uniquement sur SWI133, SWI68, SWI165 (`hasHeatFeatures() == true`)

---

## 10. Comportements spéciaux connus

### TSR → réactivation des alertes
- **SWI133** : activer TSR remet `OVERSPEED_ALARM` et `SPEED_LIMIT_TONE` à ON
  → Sauvegarde avant appel, restauration après (dans `setTsrMode()`)
- **SWI69/SWI131** : activer TSR restaure `soundWarning` depuis les prefs internes
  → Appliquer TSR en premier, réécrire `soundWarning` après dans `ProfileApplier`
- **SWI132** : TSR via binder direct — pas d'effet de bord observé sur les alertes

### AEB — convention SWI69/SWI131/SWI132
- `getFcwState()` retourne **2=ON** et **1=OFF** (pas 0/1 comme les autres)
- `setFcwState(2)` déclenche aussi `setFcwAutoBrakeMode(2)` côté firmware (d'après smali)

### DriveMode SNOW + Éco énergie
- Ces deux modes sont mutuellement exclusifs dans l'UI
- En mode SNOW, le bouton Éco énergie est grisé
- En mode Éco énergie, la régénération est désactivée (sauf One Pedal)

### Polling HVAC (siège/volant chauffant)
- Les setters HVAC sont **bloquants** — polling jusqu'à 7s en interne
- Exécutés sur `Dispatchers.IO` dans `ProfileApplier`

---

## 11. Points de vigilance pour le développement

| Risque | Détail |
|---|---|
| `isNewGenVsm()` ne contient **pas** SWI132 | Toujours ajouter `gen == SWI132` explicitement |
| `isVsmBased()` contient SWI132 | Vérifier que SWI132 ne tombe pas dans une branche SWI68 par inadvertance |
| Convention TSR inversée SWI69/SWI131 | `== 0` pour ON, `== 1` pour OFF |
| `getFcwState()` retourne 2=ON (pas 1) | Uniquement SWI69 / SWI131 / SWI132 |
| SWI132 sans `soundWarning` | Ne jamais appeler `setSoundWarning()` pour SWI132 |
| ELK mode `== 0` → skip dans ProfileApplier | Valeur 0 = défaut non configuré, évite modification involontaire |
| AEB `aebEnabled=false + aebMode=1 + aebSensitivity=0` → skip | Valeurs par défaut, évite désactivation involontaire au profil |

---
---

<!-- ════════════════════════════════════════════════════════════
     ENGLISH
     ════════════════════════════════════════════════════════════ -->

# 🇬🇧 English version

## 1. Layer Architecture (Katman)

| Layer | Name | Description |
|---|---|---|
| **Katman1** | `android.car.Car` (AAOS) | CarPropertyManager + CarHvacManager — standard AAOS access |
| **Katman2** | Direct binder `vehiclesetting` | `ServiceManager.getService("vehiclesetting")` → `IVehicleSettingService` |
| **Katman4** | VPM / VSM / CarVehicleSettingClient | Main OEM service depending on firmware (see table below) |
| **Katman5** | VehicleConditionManager / ICarGeneralService | Ignition state detection (ignition push) |

### Katman4 by firmware

| Firmware | Class | Access | Constant |
|---|---|---|---|
| SWI133 | `VehiclePropertyManager` (VPM) | Launcher SWI133 context → singleton | `LAUNCHER_PKG` = `com.saicmotor.hmi.launcher` |
| SWI68 / SWI165 | `VehicleSettingManager` (VSM) | Launcher SWI68 context → singleton | `LAUNCHER68_PKG` = `com.saicmotor.hmi.launcher` |
| SWI69 / SWI131 / SWI132 | `CarVehicleSettingClient` (VSM69) | `CarAdapterClient.queryClient(0x8)` | `VSM_SERVICE_CODE` = `0x8` |

### Katman5 by firmware

| Firmware | Service | Class |
|---|---|---|
| SWI133 / SWI68 / SWI165 | `VehicleConditionManager` (via `IHubService`) | `com.saicmotor.sdk.vehiclesettings.manager.VehicleConditionManager` |
| SWI69 / SWI131 / SWI132 | `ICarGeneralService` (via `CarAdapterClient.queryClient(0x1)`) | `com.saicmotor.carapi.client.CarGeneralClient` |

---

## 2. Firmware Detection — `FirmwareInfo.kt`

### Automatic detection
Reads `Build.VERSION.INCREMENTAL` at startup. Evaluation order:

```
startsWith("SWI133") → Gen.SWI133
startsWith("SWI132") → Gen.SWI132   ← before SWI131!
startsWith("SWI131") → Gen.SWI131
startsWith("SWI165") → Gen.SWI165
startsWith("SWI69")  → Gen.SWI69
startsWith("SWI68")  → Gen.SWI68
else                 → Gen.UNKNOWN
```

### Grouping helpers

| Method | Included firmwares | Usage |
|---|---|---|
| `isVsmBased()` | SWI68, SWI69, SWI131, SWI132, SWI165 | VSM API available (excludes SWI133) |
| `isNewGenVsm()` | SWI69, SWI131 | CarVehicleSettingClient via CarAdapterClient (without SWI132!) |
| `hasHeatFeatures()` | SWI133, SWI68, SWI165 | Heated seats/steering wheel available |

> ⚠️ SWI132 is **not** in `isNewGenVsm()` — uses the same CarVehicleSettingClient but is handled with an explicit `gen == SWI132` branch to avoid regressions.

---

## 3. AIDL Descriptors

| Constant | Value | Used by |
|---|---|---|
| `DESCRIPTOR_VEHICLE` | `com.saicmotor.sdk.vehiclesettings.IVehicleSettingService` | SWI133 (ELK direct binder) |
| `DESCRIPTOR_VSM132` | `com.saicmotor.vehiclesetting.IVehicleSettingService` | SWI132 (alerts/TSR direct binder) |

---

## 4. SWI133

### 4.1 Architecture
- **Katman4**: `VehiclePropertyManager` (VPM) — via launcher `com.saicmotor.hmi.launcher`
- **Katman5**: `VehicleConditionManager`
- **Direct binder**: `sVehicleBinder` (vehiclesetting) — ELK only

### 4.2 Property IDs Constants (VPM)

| Feature | Constant | Hex | Decimal | Values |
|---|---|---|---|---|
| Drive mode | `PROP_DRIVE_MODE` | `0x2140A17C` | 557883772 | 0=Snow, 1=Eco, 3=Normal, 4=Sport |
| Regen level | `PROP_REGEN_LEVEL` | `0x2140A191` | 557883793 | 0=Low, 1=Medium, 2=High, 3=Adaptive |
| One Pedal | `PROP_ONE_PEDAL` | `0x2140A193` | 557883795 | 0=OFF, 1=ON |
| AEB enabled | `PROP_AEB_SWITCH` | `0x2140A108` | 557883528 | 1=OFF, 2=ON (CPM) |
| AEB mode | `PROP_AEB_SYS_MODE` | `0x302000A` | 50397194 | 1=Alert, 2=Alert+Braking (VPM) |
| AEB mode (alt) | `PROP_AEB_MODE` | `0x302000B` | 50397195 | 1=Alert, 2=Alert+Braking (VPM) |
| AEB sensitivity | `PROP_AEB_SENSITIVITY` | `0x302000E` | 50397198 | 1=Low, 2=Standard, 3=High |
| TSR / SLIF | `PROP_TSR_MODE` | `0x5030049` | 83951689 | 0=OFF, 1=ON |
| Overspeed alarm | `PROP_OVERSPEED_ALARM` | `0x503004E` | 83951694 | 0=OFF, 1=ON |
| Speed limit tone | `PROP_SPEED_LIMIT_TONE` | `0x503004F` | 83951695 | 0=OFF, 1=ON |
| ADAS mixed | `PROP_MIX_INTELLIGENT_DRIVE` | `0x32` | 50 | 0=Off, 1=Limit, 2=Auto, 3=ACC, 4=ICA |
| Energy saving | `PROP_ENERGY_SAVING` | `0x5030007` | 83951623 | 0=OFF, 1=ON |

### 4.3 Direct binder — ELK (TX codes SWI133)

| Operation | TX code | Flag | AIDL method |
|---|---|---|---|
| GET ELK mode | `0x53` | two-way | `getLaneKeepingAsstMode()` |
| SET ELK mode | `0x54` | ONEWAY | `setLaneKeepingAsstMode(I)` |
| GET ELK sensitivity | `0x55` | two-way | `getLaneKeepingAsstSen()` |
| SET ELK sensitivity | `0x56` | ONEWAY | `setLaneKeepingAsstSen(I)` |

**ELK values**: 1=OFF · 2=Alert (LDW) · 3=Assist (LDP) · 5=Emergency (ELK)
**Sensitivity values**: 1=Low · 2=Standard · 3=High

### 4.4 Known TSR behavior
> Enabling TSR **automatically re-enables** `OVERSPEED_ALARM` and `SPEED_LIMIT_TONE`.
> → `setTsrMode()` saves values before the call and restores them after.

### 4.5 ADAS — SWI133 buttons
5 buttons: **Off / Limiter / Auto / ACC / ICA** (values 0→4 in `PROP_MIX_INTELLIGENT_DRIVE`)

---

## 5. SWI68 / SWI165

### 5.1 Architecture
- **Katman4**: `VehicleSettingManager` (VSM) — via launcher `com.saicmotor.hmi.launcher`
- **Katman5**: `VehicleConditionManager`
- **Heating**: available (seats + steering wheel)

> SWI165 uses **exactly the same SDK as SWI68** → identical handling in code.

### 5.2 VSM methods — calls via `callVsm(methodName, ...)`

| Feature | GET method | SET method | Values |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaMode()` | `setAccTjaMode(I)` | OFF=4, ACC=1, TJA=2 |
| AEB enabled | `getFcwAlarmMode()` | `setFcwAlarmMode(I)` | 0=OFF, 1=ON |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alert, 2=Alert+Braking |
| AEB sensitivity | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Low, 2=Standard, 3=High |
| ELK mode | `getLaneKeepingAsstMode()` | `setLaneKeepingAsstMode(I)` | 1=OFF, 2=Alert, 3=Assist, 5=Emergency |
| ELK sensitivity | `getLaneKeepingAsstSen()` | `setLaneKeepingAsstSen(I)` | 1=Low, 2=Standard, 3=High |
| Sound warning | `getSoundWarning()` | `setSoundWarning(I)` | 0=OFF, 1=ON |
| TSR / SLIF | `getSpeedAsstSlifWarning()` | `setSpeedAsstSlifWarning(I)` | 0=OFF, 1=ON |
| Energy saving | `getLongerEndurance()` | `setLongerEndurance(I)` | 0=OFF, 1=ON |

### 5.3 ADAS — SWI68/SWI165 buttons
3 buttons: **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`

### 5.4 HVAC — Property IDs (CarHvacManager / CarPropertyManager)

| Feature | Constant | Hex | Values |
|---|---|---|---|
| Steering heat | `PROP_STEERING_HEAT` | `0x1540253A` | 0=OFF, 1-3=Level |
| Left seat heat | `PROP_SEAT_HEAT_L` | `0x15402513` | 0=OFF, 1-3=Level |
| Right seat heat | `PROP_SEAT_HEAT_R` | `0x15402514` | 0=OFF, 1-3=Level |

---

## 6. SWI69 / SWI131

### 6.1 Architecture
- **Katman4**: `CarVehicleSettingClient` via `CarAdapterClient.queryClient(0x8)`
- **Katman5**: `ICarGeneralService` via `CarAdapterClient.queryClient(0x1)`
- **Heating**: not available (no `hasHeatFeatures()`)

> SWI131 is identical to SWI69 — same launcher APK, same SDK, same methods.

### 6.2 CarVehicleSettingClient methods — calls via `callVsm(methodName, ...)`

| Feature | GET method | SET method | Values |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaState()` | `setAccTjaState(I)` | OFF=4, ACC=1, TJA=2 |
| AEB enabled | `getFcwState()` | `setFcwState(I)` | 1=OFF, **2=ON** |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alert, 2=Alert+Braking |
| AEB sensitivity | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Low, 2=Standard, 3=High |
| ELK mode | `getLasMode()` | `setLasMode(I)` | 1=OFF, 2=Alert, 3=Assist, 5=Emergency |
| ELK sensitivity | `getLasSensitivity()` | `setLasSensitivity(I)` | 1=Low, 2=Standard, 3=High |
| Sound warning | `getSoundWarning()` | `setSoundWarning(I)` | 0=OFF, 1=ON |
| TSR / SLIF | `getSLIFWarningState()` | `setSLIFWarningState(I)` | **0=ON, 1=OFF** (inverted!) |
| Energy saving | `getEnduranceMode()` | `setEnduranceMode(I)` | 0=OFF, 1=ON |

> ⚠️ **Inverted TSR convention**: `getSLIFWarningState()` returns **0=ON and 1=OFF** — opposite of all other firmwares. `isTsrOn()` returns `== 0`.

### 6.3 TSR — known behavior
> Enabling TSR restores `soundWarning` from internal preferences.
> → Apply TSR first, then rewrite `soundWarning` with the profile value in `ProfileApplier`.

### 6.4 ADAS — SWI69/SWI131 buttons
3 buttons: **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`
Method: `getAccTjaState` / `setAccTjaState` (different from SWI68 which uses `getAccTjaMode`)

---

## 7. SWI132

### 7.1 Architecture
- **Katman4**: `CarVehicleSettingClient` via `CarAdapterClient.queryClient(0x8)` — **same stack as SWI69**
- **Katman5**: `ICarGeneralService` via `CarAdapterClient.queryClient(0x1)` — **same stack as SWI69**
- **Direct binder**: `sVehicleBinder` (vehiclesetting) — for TSR + overspeed/limit alerts
- **Heating**: not available
- **Alerts**: two independent alerts (overspeed + limit tone) — **no VSM `soundWarning`**

### 7.2 Direct binder — TX codes SWI132

Descriptor: `com.saicmotor.vehiclesetting.IVehicleSettingService`
Flag: **two-way `0x0`** (different from SWI133 ELK ONEWAY flag)
Parcel layout: `writeInterfaceToken(descriptor)` → `writeInt(value)` → `transact` → `readException()` → `readInt()`

| Operation | TX code | AIDL method | Values |
|---|---|---|---|
| GET TSR | `0x058` | `getSLIFWarningState()` | 0=OFF, **1=ON** |
| SET TSR | `0x057` | `setSLIFWarningState(I)` | 0=OFF, 1=ON |
| GET overspeed | `0x129` | `getOverSpeedSoundMode()` | 0=OFF, 1=ON |
| SET overspeed | `0x128` | `setOverSpeedSoundMode(I)` | 0=OFF, 1=ON |
| GET limit tone | `0x12B` | `getSpeedLimitSoundMode()` | 0=OFF, 1=ON |
| SET limit tone | `0x12A` | `setSpeedLimitSoundMode(I)` | 0=OFF, 1=ON |

> ⚠️ **Normal TSR convention** (unlike SWI69/SWI131): `getSLIFWarningState()` returns **1=ON** (no inversion).

### 7.3 CarVehicleSettingClient methods (Katman4) — identical to SWI69

| Feature | GET method | SET method | Values |
|---|---|---|---|
| ADAS ACC/TJA | `getAccTjaState()` | `setAccTjaState(I)` | OFF=4, ACC=1, TJA=2 |
| AEB enabled | `getFcwState()` | `setFcwState(I)` | 1=OFF, **2=ON** |
| AEB mode | `getFcwAutoBrakeMode()` | `setFcwAutoBrakeMode(I)` | 1=Alert, 2=Alert+Braking |
| AEB sensitivity | `getFcwSensitivity()` | `setFcwSensitivity(I)` | 1=Low, 2=Standard, 3=High |
| ELK mode | `getLasMode()` | `setLasMode(I)` | 1=OFF, 2=Alert, 3=Assist, 5=Emergency |
| ELK sensitivity | `getLasSensitivity()` | `setLasSensitivity(I)` | 1=Low, 2=Standard, 3=High |
| Energy saving | `getEnduranceMode()` | `setEnduranceMode(I)` | 0=OFF, 1=ON |

### 7.4 ADAS — SWI132 buttons
3 buttons: **OFF / ACC / TJA** — `Swi68Mode.OFF=4 · ACC=1 · TJA=2`
(identical to SWI68/SWI69/SWI131/SWI165 — no Limiter/ICA)

### 7.5 Key differences SWI132 vs SWI69/SWI131

| Point | SWI69/SWI131 | SWI132 |
|---|---|---|
| TSR convention | `0=ON, 1=OFF` (inverted) | `0=OFF, 1=ON` (normal) |
| TSR access | Via `callVsm("setSLIFWarningState")` | Via direct binder TX `0x057` |
| Overspeed/limit alerts | Single alert `soundWarning` | Two independent alerts via binder TX `0x128` / `0x12A` |
| AEB enabled check | `getFcwState() == 2` | `getFcwState() == 2` ✅ identical |

---

## 8. Summary tables — methods by firmware

### Drive mode & Regeneration
> Common to all firmwares via `CarPropertyManager` (PROP_DRIVE_MODE / PROP_REGEN_LEVEL)

| Firmware | DriveMode | RegenLevel | One Pedal |
|---|---|---|---|
| All | CPM `0x2140A17C` | CPM `0x2140A191` | CPM `0x2140A193` |

**DriveMode values**: 0=Snow, 1=Eco, 3=Normal, 4=Sport
**RegenLevel values**: 0=Low, 1=Medium, 2=High, 3=Adaptive, +ONE_PEDAL

### AEB (summary)

| Firmware | isAebEnabled | setAebEnabled | getAebMode | setAebMode |
|---|---|---|---|---|
| SWI133 | CPM `PROP_AEB_SWITCH == 2` | CPM + VPM reset | VPM `PROP_AEB_MODE` | VPM dual write |
| SWI68 / SWI165 | VSM `getFcwAlarmMode() != 0` | VSM `setFcwAlarmMode` | VSM `getFcwAutoBrakeMode` | VSM `setFcwAutoBrakeMode` |
| SWI69 / SWI131 / SWI132 | VSM69 `getFcwState() == 2` | VSM69 `setFcwState` | VSM69 `getFcwAutoBrakeMode` | VSM69 `setFcwAutoBrakeMode` |

### TSR (summary)

| Firmware | isTsrOn | setTsrMode | Convention |
|---|---|---|---|
| SWI133 | VPM `PROP_TSR_MODE > 0` | VPM set + restore alerts | Normal (1=ON) |
| SWI68 / SWI165 | VSM `getSpeedAsstSlifWarning() == 1` | VSM `setSpeedAsstSlifWarning` | Normal (1=ON) |
| SWI69 / SWI131 | VSM69 `getSLIFWarningState() == 0` | VSM69 `setSLIFWarningState` | **Inverted (0=ON)** |
| SWI132 | Binder `TX 0x058 == 1` | Binder `TX 0x057` | Normal (1=ON) |

### Overspeed / speed limit tone alerts (summary)

| Firmware | Type | Access |
|---|---|---|
| SWI133 | Two independent alerts | VPM `PROP_OVERSPEED_ALARM` / `PROP_SPEED_LIMIT_TONE` |
| SWI68 / SWI165 / SWI69 / SWI131 | Single `soundWarning` alert | VSM `getSoundWarning` / `setSoundWarning` |
| SWI132 | Two independent alerts | Binder TX `0x129/0x128` / `0x12B/0x12A` |

### ELK (summary)

| Firmware | GET/SET mode methods | GET/SET sensitivity methods |
|---|---|---|
| SWI133 | Binder TX `0x53` / `0x54` | Binder TX `0x55` / `0x56` |
| SWI68 / SWI165 | VSM `getLaneKeepingAsstMode` / `set...` | VSM `getLaneKeepingAsstSen` / `set...` |
| SWI69 / SWI131 / SWI132 | VSM69 `getLasMode` / `setLasMode` | VSM69 `getLasSensitivity` / `setLasSensitivity` |

### Energy saving (summary)

| Firmware | GET | SET |
|---|---|---|
| SWI133 | VPM `PROP_ENERGY_SAVING` | VPM `setIntPropertyVpmRecovery` |
| SWI68 / SWI165 | VSM `getLongerEndurance()` | VSM `setLongerEndurance(I)` |
| SWI69 / SWI131 / SWI132 | VSM69 `getEnduranceMode()` | VSM69 `setEnduranceMode(I)` |

---

## 9. Global constants

### Value objects (MG4Hardware)

```kotlin
object Swi68Mode      { OFF=4, ACC=1, TJA=2 }
object ElkMode        { OFF=1, ALERT=2, ASSIST=3, EMERGENCY=5 }
object ElkSensitivity { LOW=1, STANDARD=2, HIGH=3 }
object AebMode        { ALARM=1, ALARM_BRAKE=2 }
object AebSensitivity { LOW=1, STANDARD=2, HIGH=3 }
object CarIgnitionItem { OFF=0, ACCESSORY=1, RUN=2, CRANK=3 }
```

### HVAC Property IDs (all compatible firmwares)

| Feature | Constant | Hex | Values |
|---|---|---|---|
| Steering heat | `PROP_STEERING_HEAT` | `0x1540253A` | 0=OFF, 1-3=Level |
| Left seat heat | `PROP_SEAT_HEAT_L` | `0x15402513` | 0=OFF, 1-3=Level |
| Right seat heat | `PROP_SEAT_HEAT_R` | `0x15402514` | 0=OFF, 1-3=Level |

> Available only on SWI133, SWI68, SWI165 (`hasHeatFeatures() == true`)

---

## 10. Known special behaviors

### TSR → alert re-activation
- **SWI133**: enabling TSR turns `OVERSPEED_ALARM` and `SPEED_LIMIT_TONE` back ON
  → Save values before the call, restore after (in `setTsrMode()`)
- **SWI69/SWI131**: enabling TSR restores `soundWarning` from internal prefs
  → Apply TSR first, rewrite `soundWarning` after in `ProfileApplier`
- **SWI132**: TSR via direct binder — no side effect observed on alerts

### AEB — SWI69/SWI131/SWI132 convention
- `getFcwState()` returns **2=ON** and **1=OFF** (not 0/1 like other firmwares)
- `setFcwState(2)` also triggers `setFcwAutoBrakeMode(2)` at firmware level (per smali)

### DriveMode SNOW + Energy saving
- These two modes are mutually exclusive in the UI
- In SNOW mode, the Energy saving button is greyed out
- In Energy saving mode, regeneration is disabled (except One Pedal)

### HVAC polling (seat/steering heating)
- HVAC setters are **blocking** — internal polling up to 7s
- Executed on `Dispatchers.IO` in `ProfileApplier`

---

## 11. Development watch points

| Risk | Detail |
|---|---|
| `isNewGenVsm()` does **not** include SWI132 | Always add `gen == SWI132` explicitly |
| `isVsmBased()` includes SWI132 | Verify SWI132 doesn't accidentally fall into an SWI68 branch |
| Inverted TSR convention on SWI69/SWI131 | `== 0` for ON, `== 1` for OFF |
| `getFcwState()` returns 2=ON (not 1) | Only on SWI69 / SWI131 / SWI132 |
| SWI132 has no `soundWarning` | Never call `setSoundWarning()` for SWI132 |
| ELK mode `== 0` → skip in ProfileApplier | Value 0 = unset default, avoids unintended modification |
| AEB `aebEnabled=false + aebMode=1 + aebSensitivity=0` → skip | Default values, avoids unintended disable on profile apply |
