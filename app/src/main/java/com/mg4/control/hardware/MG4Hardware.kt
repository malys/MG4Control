package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo

/**
 * Hardware abstraction layer for MG4 vehicle control.
 * Reconstructed from DriveHub Dort 0.9 smali.
 *
 * Three communication layers (mirrors original exactly):
 *  Katman1 — android.car.Car → CarPropertyManager / CarHvacManager (async ServiceConnection)
 *  Katman2 — ServiceManager.getService("vehiclesetting") → raw IBinder (often SELinux-blocked)
 *  Katman3 — bindService(VehicleService) → IHubService → sub-services (not needed for our use case)
 */
object MG4Hardware {

    private const val TAG = "MG4_HW"

    // Area IDs
    private const val AREA_GLOBAL = 0x1000000
    private const val AREA_HVAC   = 0x75

    // Vehicle property IDs
    private const val PROP_DRIVE_MODE  = 0x2140a17c
    private const val PROP_REGEN_LEVEL = 0x2140a191
    private const val PROP_ONE_PEDAL   = 0x2140a193
    const val PROP_SEAT_HEAT_L         = 0x15402513
    const val PROP_SEAT_HEAT_R         = 0x15402514
    const val PROP_STEERING_HEAT       = 0x1540253a

    // SAIC binder transaction codes
    private const val TX_SET_DRIVE_MODE  = 0x82
    private const val TX_SET_REGEN_LEVEL = 0xa1
    private const val TX_SET_ONE_PEDAL   = 0xa4

    private const val DESCRIPTOR_VEHICLE = "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService"
    private const val PREFS_NAME          = "drivehub_dort"
    private const val KEY_LAST_DRIVE_MODE = "last_drive_mode"

    // ADAS property IDs — SWI133 (getMixProperty / getIntProperty)
    private const val PROP_OVERSPEED_ALARM       = 0x503004e
    private const val PROP_SPEED_LIMIT_TONE      = 0x503004f
    private const val PROP_MIX_INTELLIGENT_DRIVE = 0x32

    // ELK — Assistant de sortie de voie (SWI133)
    // Accès via IVehicleSettingService binder (sVehicleBinder) — smali IVehicleSettingService$Stub$Proxy
    // getLaneKeepingAsstMode()   → TX 0x53 (synchrone, reply: readException + readInt)
    // setLaneKeepingAsstMode(I)  → TX 0x54 (ONEWAY, data: writeInt(value))
    // getLaneKeepingAsstSen()    → TX 0x55 (synchrone)
    // setLaneKeepingAsstSen(I)   → TX 0x56 (ONEWAY)
    // Mode  : 1=OFF, 2=Alerte(LDW), 3=Aider(LDP), 5=Maintien d'urgence(ELK)
    // Sen   : 1=Faible, 2=Standard, 3=Élevé
    private const val TX_ELK_GET_MODE = 0x53
    private const val TX_ELK_SET_MODE = 0x54
    private const val TX_ELK_GET_SEN  = 0x55
    private const val TX_ELK_SET_SEN  = 0x56

    /** Valeurs du mode ELK (LaneKeepingAsstMode). */
    object ElkMode {
        const val OFF       = 1   // Désactivé
        const val ALERT     = 2   // Alerte (LDW)
        const val ASSIST    = 3   // Aider (LDP)
        const val EMERGENCY = 5   // Maintien d'urgence (ELK)
    }

    /** Valeurs de sensibilité ELK. */
    object ElkSensitivity {
        const val LOW      = 1   // Faible
        const val STANDARD = 2   // Standard
        const val HIGH     = 3   // Élevé
    }

    // AEB — Système anti-collision avant (SWI133)
    // PROP_AEB_SWITCH    : CarPropertyManager, AREA_GLOBAL, 1=OFF / 2=ON
    private const val PROP_AEB_SWITCH    = 0x2140a108  // AAD_FRONT_COLLISION_ASST_SYS (CPM)
    // PROP_AEB_SYS_MODE  : VPM, ID_AAD_FRONT_COLLISION_ASST_SYS, 1=Alerte / 2=Alerte+Freinage
    // PROP_AEB_MODE      : VPM, ID_AAD_AUTO_EME_BREAK,            1=Alerte / 2=Alerte+Freinage
    // Le smali vehiclesettings écrit toujours les deux simultanément via setIntPropertyRecovery
    private const val PROP_AEB_SYS_MODE    = 0x302000a  // ID_AAD_FRONT_COLLISION_ASST_SYS (VPM)
    private const val PROP_AEB_MODE        = 0x302000b  // ID_AAD_AUTO_EME_BREAK (VPM)
    // PROP_AEB_SENSITIVITY : VPM, ForwardCollisionAsstSentItem, 1=Faible / 2=Standard / 3=Élevé
    private const val PROP_AEB_SENSITIVITY = 0x302000e  // ID_AAD_FRONT_COLLISION_ASST_SEN (VPM)

    // TSR — Reconnaissance des panneaux de vitesse (SLIF Warning)
    // SWI133 : VPM toggle 0/1 ; SWI68/SWI165 : VSM setSpeedAsstSlifWarning ; SWI69/SWI131 : VSM setSLIFWarningState (inversé)
    private const val PROP_TSR_MODE = 0x5030049  // ID_AAD_SLIF_WARNING

    // Économie d'énergie (Endurance Mode / Longer Endurance)
    // SWI133 : VPM PROP_ENERGY_SAVING ; SWI69/SWI131 : VSM setEnduranceMode ; SWI68/SWI165 : VSM setLongerEndurance
    private const val PROP_ENERGY_SAVING = 0x5030007  // ID_LONGER_ENDURANCE_MODE

    // SWI68 : VehicleSettingManager class name (loaded via launcher context)
    private const val VSM_CLASS      = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    private const val LAUNCHER68_PKG = "com.saicmotor.hmi.launcher"

    // SWI69/SWI131 : accès via CarAdapterClient → queryClient(0x8) → CarVehicleSettingClient
    // Architecture réelle : CarAdapterClient se connecte à com.saicmotor.caradapter.CarAdapterService,
    // puis queryClient(code) retourne l'IBinder pour chaque service.
    // Code 0x8 = CarVehicleSettingClient (vérifié dans VehicleSettingService.onResult() smali)
    private const val LAUNCHER69_PKG      = "com.saicmotor.launcher"
    private const val CAR_ADAPTER_CLASS   = "com.saicmotor.carapi.CarAdapterClient"
    private const val VSM69_CLIENT_CLASS  = "com.saicmotor.carapi.client.CarVehicleSettingClient"
    private const val VSM_SERVICE_CODE    = 0x8   // queryClient(0x8) → ICarVehicleSettingService

    /** Valeurs de mode ADAS pour firmware SWI68. */
    object Swi68Mode {
        const val OFF = 0x4   // Désactiver
        const val ACC = 0x1   // ACC
        const val TJA = 0x2   // TJA (Traffic Jam Assist)
    }

    /** Valeurs du mode AEB (communes SWI133 + SWI68). */
    object AebMode {
        const val ALARM       = 1   // Alerte seule (FCW)
        const val ALARM_BRAKE = 2   // Alerte + Freinage automatique d'urgence
    }

    /** Valeurs de sensibilité AEB — SWI133 uniquement (PROP_AEB_SENSITIVITY = 0x302000e). */
    object AebSensitivity {
        const val LOW      = 1   // Faible
        const val STANDARD = 2   // Standard
        const val HIGH     = 3   // Élevé
    }

    @Volatile private var sAppContext: Context? = null
    @Volatile private var sCar: Any? = null
    @Volatile private var sCarPropertyManager: Any? = null
    @Volatile private var sCarHvacManager: Any? = null
    @Volatile private var sVehicleBinder: IBinder? = null
    @Volatile private var sVpm: Any? = null          // VehiclePropertyManager instance (SWI133, Katman4)
    @Volatile private var sVpmService: Any? = null   // mIVehiclePropertyService field value (SWI133)
    @Volatile private var sVsm: Any? = null          // VehicleSettingManager instance (SWI68, Katman4)
    @Volatile private var sVsmService: Any? = null   // mVehicleSettingService field value (SWI68)
    @Volatile private var sVsm133: Any? = null       // VehicleSettingManager instance (SWI133, pour ELK)
    @Volatile private var sInitialized = false
    @Volatile private var sCarBindAttempted = false
    @Volatile var logEnabled = true

    @Volatile private var sDriveModeListener: DriveModeListener? = null
    @Volatile private var sHvacListener: HvacListener? = null

    /** Listeners notifiés dès que Katman1 (CPM + HVAC) est opérationnel. */
    private val katman1ReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Listeners notifiés dès que Katman4 (mIVehiclePropertyService) est opérationnel. */
    private val katman4ReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Exécute [action] dès que CarPropertyManager et CarHvacManager sont disponibles.
     * Si déjà prêt, exécution immédiate. Sinon, mis en file et déclenché à la connexion.
     */
    fun whenKatman1Ready(action: () -> Unit) {
        if (sCarPropertyManager != null && sCarHvacManager != null) {
            action()
        } else {
            katman1ReadyListeners.add(action)
        }
    }

    /**
     * Exécute [action] dès que le service ADAS (Katman4) est disponible.
     * SWI133 → mIVehiclePropertyService ; SWI68/SWI69/SWI131 → mVehicleSettingService
     */
    fun whenKatman4Ready(action: () -> Unit) {
        val ready = if (FirmwareInfo.isVsmBased()) sVsmService != null else sVpmService != null
        if (ready) action() else katman4ReadyListeners.add(action)
    }

    interface DriveModeListener { fun onDriveModeChanged(mode: DriveMode) }
    interface HvacListener {
        fun onSeatHeatChanged(left: Int, right: Int)
        fun onSteeringHeatChanged(on: Boolean)
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        if (sInitialized) return
        sInitialized = true
        sAppContext = context.applicationContext
        AppLogger.i(TAG, "=== MG4Hardware.init() === uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.DEVICE}")
        bindCarService(context)
        sVehicleBinder = getBinderService("vehiclesetting")
        when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68  -> initKatman4Swi68(context)
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI165 -> initKatman4Swi68(context)  // même SDK que SWI68
            FirmwareInfo.isNewGenVsm()                              -> initKatman4Swi69(context)   // SWI69 + SWI131
            else                                                    -> initKatman4(context)
        }
        if (sVehicleBinder != null)
            AppLogger.i(TAG, "  ✓ Katman2: vehiclesetting binder OK")
        else
            AppLogger.w(TAG, "  ✗ Katman2: vehiclesetting null (SELinux — expected)")
        AppLogger.i(TAG, "========================================")
    }

    // -------------------------------------------------------------------------
    // Katman1 — android.car.Car (async, mirrors original bindCarService exactly)
    // -------------------------------------------------------------------------

    private fun bindCarService(context: Context) {
        if (sCarBindAttempted) return
        sCarBindAttempted = true
        val carClass: Class<*>
        try {
            carClass = Class.forName("android.car.Car")
            AppLogger.i(TAG, "  Katman1: android.car.Car class found ✓")
        } catch (e: ClassNotFoundException) {
            AppLogger.w(TAG, "  Katman1: android.car.Car not found — not Automotive?")
            return
        } catch (e: Exception) {
            AppLogger.e(TAG, "  Katman1: forName error: ${e.message}")
            return
        }

        var car: Any? = null

        // Attempt 1: createCar(Context)
        try {
            car = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context) → success")
        } catch (_: Exception) {}

        // Attempt 2: createCar(Context, Handler)
        if (car == null) {
            try {
                car = carClass.getMethod("createCar", Context::class.java, Handler::class.java)
                    .invoke(null, context, null)
                if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context, Handler) → success")
            } catch (_: Exception) {}
        }

        // Attempt 3: createCar(Context, ServiceConnection) — async, callback fires when connected
        var scMethodFound: java.lang.reflect.Method? = null
        try {
            scMethodFound = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
            AppLogger.i(TAG, "  Katman1: createCar(Context, ServiceConnection) method found")
        } catch (_: Exception) {}

        if (car == null && scMethodFound != null) {
            try {
                val sc = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        AppLogger.i(TAG, "  Katman1: ServiceConnection.onServiceConnected")
                        tryGetManagersFromCar(carClass)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        AppLogger.w(TAG, "  Katman1: Car service disconnected")
                        sCarPropertyManager = null
                        sCarHvacManager = null
                    }
                }
                car = scMethodFound.invoke(null, context, sc)
                if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context, SC) → callback pending")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman1: createCar(Context, SC) error: ${e.message}")
            }
        }

        if (car == null) {
            AppLogger.e(TAG, "  Katman1: all createCar methods failed")
            return
        }

        sCar = car

        // Call car.connect() if available (required on older builds)
        try {
            carClass.getMethod("connect").invoke(car)
            AppLogger.i(TAG, "  Katman1: car.connect() called")
        } catch (_: NoSuchMethodException) {
            // connect() not present on all builds, ignore
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman1: car.connect() error: ${e.message}")
        }

        // Try sync managers immediately
        tryGetManagersFromCar(carClass)

        // Schedule retries — délais étendus pour couvrir le boot lent du Car service SAIC
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 2_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 5_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 10_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 20_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 40_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 60_000)
    }

    private fun tryGetManagersFromCar(carClass: Class<*>) {
        val car = sCar ?: return
        if (sCarPropertyManager != null && sCarHvacManager != null) return // already done
        try {
            val connected = try {
                (carClass.getMethod("isConnected").invoke(car) as? Boolean) ?: true
            } catch (_: Exception) { true }

            AppLogger.i(TAG, "  Katman1: isConnected() → $connected")
            if (!connected) {
                AppLogger.w(TAG, "  Katman1: car not yet connected")
                return
            }

            val getCarManager = carClass.getMethod("getCarManager", String::class.java)

            if (sCarPropertyManager == null) {
                try {
                    val svc = carClass.getField("PROPERTY_SERVICE").get(null) as String
                    sCarPropertyManager = getCarManager.invoke(car, svc)
                    AppLogger.i(TAG, "  Katman1: CarPropertyManager READY ✓")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "  Katman1: CarPropertyManager unavailable: ${e.message}")
                }
            }

            if (sCarHvacManager == null) {
                try {
                    val svc = carClass.getField("HVAC_SERVICE").get(null) as String
                    sCarHvacManager = getCarManager.invoke(car, svc)
                    AppLogger.i(TAG, "  Katman1: CarHvacManager READY ✓")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "  Katman1: CarHvacManager unavailable: ${e.message}")
                }
            }

            // Notifier les abonnés whenKatman1Ready dès que les deux managers sont prêts
            if (sCarPropertyManager != null && sCarHvacManager != null && katman1ReadyListeners.isNotEmpty()) {
                val toNotify = katman1ReadyListeners.toList()
                katman1ReadyListeners.clear()
                Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "  Katman1: tryGetManagersFromCar error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Katman2 — ServiceManager raw binder
    // -------------------------------------------------------------------------

    private fun getBinderService(serviceName: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val method = sm.getMethod("getService", String::class.java)
            (method.invoke(null, serviceName) as? IBinder).also {
                AppLogger.d(TAG, "getService($serviceName) → $it")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getService($serviceName) error: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Katman4 — VehiclePropertyManager via saicmotor.hmi.launcher context
    // -------------------------------------------------------------------------

    private fun initKatman4(context: Context) {
        if (sVpm != null) return
        val launcherCtx: Context
        val vpmClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            vpmClass = launcherCtx.classLoader
                .loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman4: package/class error: ${e.message} — will retry")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context.applicationContext) }, 5_000)
            return
        }

        var vpm: Any? = null

        // ---- Constructeurs (priorité) ----
        // 1) ctor(launcherCtx) — most likely for a class bundled in the launcher APK
        if (vpm == null) vpm = tryInvoke("ctor(launcherCtx)") {
            vpmClass.getConstructor(Context::class.java).newInstance(launcherCtx)
        }
        // 2) ctor(appCtx)
        if (vpm == null) vpm = tryInvoke("ctor(appCtx)") {
            vpmClass.getConstructor(Context::class.java).newInstance(context)
        }
        // 3) ctor() no-arg
        if (vpm == null) vpm = tryInvoke("ctor()") {
            @Suppress("DEPRECATION") vpmClass.newInstance()
        }

        // ---- Méthodes statiques de factory ----
        if (vpm == null) vpm = tryInvoke("getInstance(launcherCtx)") {
            vpmClass.getMethod("getInstance", Context::class.java).invoke(null, launcherCtx)
        }
        if (vpm == null) vpm = tryInvoke("getInstance(appCtx)") {
            vpmClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        }
        if (vpm == null) vpm = tryInvoke("getInstance()") {
            vpmClass.getMethod("getInstance").invoke(null)
        }

        if (vpm == null) {
            AppLogger.w(TAG, "  Katman4: toutes les tentatives ont échoué — will retry")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context.applicationContext) }, 10_000)
            return
        }

        sVpm = vpm

        // 1) bindService() — connecte au service véhicule (async)
        tryInvoke("vpm.bindService()") { vpm!!.javaClass.getMethod("bindService").invoke(vpm) }

        // 2) init(Context, IVehicleServiceListener) via dynamic proxy — reçoit onServiceConnected
        initWithServiceListener(vpm!!, context, launcherCtx)

        // 3) VehicleSettingManager pour SWI133 (ELK) — même singleton que SWI68
        tryInitVsm133(launcherCtx, context)

        // 4) Retries pour récupérer mIVehiclePropertyService et VSM133 une fois le service connecté
        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (sVpmService == null) tryGetVpmService(sVpm ?: return@postDelayed)
                if (sVsm133 == null) tryInitVsm133(launcherCtx, context)
            }, delay)
        }

        tryGetVpmService(vpm!!)
        AppLogger.i(TAG, "  Katman4: VPM prêt — mIVehiclePropertyService=${if (sVpmService != null) "OK ✓" else "null (en attente)"}")
    }

    private fun initWithServiceListener(vpm: Any, context: Context, launcherCtx: Context) {
        val vpmClass = vpm.javaClass

        // Log all methods for diagnostics
        val methodSummary = vpmClass.methods.joinToString(", ") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }
        AppLogger.d(TAG, "  Katman4: VPM methods = $methodSummary")

        // Strategy 1: Inspect the actual init() signature to get the real listener type
        val initMethod2 = vpmClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }

        if (initMethod2 != null) {
            val listenerType = initMethod2.parameterTypes[1]
            AppLogger.i(TAG, "  Katman4: init() trouvé, listener type = ${listenerType.name}")

            // Try dynamic proxy with the actual listener interface type
            if (listenerType.isInterface) {
                try {
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.classLoader, arrayOf(listenerType)
                    ) { _, method, _ ->
                        when (method.name) {
                            "onServiceConnected" -> {
                                AppLogger.i(TAG, "  Katman4: onServiceConnected ✓")
                                tryGetVpmService(vpm)
                            }
                            "onServiceDisconnected" -> {
                                AppLogger.w(TAG, "  Katman4: onServiceDisconnected")
                                sVpmService = null
                            }
                            else -> {}
                        }
                        null
                    }
                    initMethod2.invoke(vpm, context, proxy)
                    AppLogger.i(TAG, "  Katman4: init(Context, proxy) ✓")
                    return
                } catch (e: Exception) {
                    AppLogger.d(TAG, "  Katman4: init(Context, proxy) failed: ${e.message}")
                }
            }

            // Fallback: try init(Context, null) — works if listener is nullable
            try {
                initMethod2.invoke(vpm, context, null)
                AppLogger.i(TAG, "  Katman4: init(Context, null) ✓")
                return
            } catch (e: Exception) {
                AppLogger.d(TAG, "  Katman4: init(Context, null) failed: ${e.message}")
            }
        }

        // Strategy 2: init(Context) single-param
        try {
            vpmClass.getMethod("init", Context::class.java).invoke(vpm, context)
            AppLogger.i(TAG, "  Katman4: init(Context) ✓")
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: init(Context) failed: ${e.message}")
        }

        // Strategy 3: init() no-arg
        try {
            vpmClass.getMethod("init").invoke(vpm)
            AppLogger.i(TAG, "  Katman4: init() ✓")
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: init() failed: ${e.message}")
        }

        AppLogger.w(TAG, "  Katman4: aucun init() fonctionnel — mIVehiclePropertyService restera null")
    }

    /** Exécute [block], retourne le résultat ou null, log le résultat/erreur. */
    private fun tryInvoke(label: String, block: () -> Any?): Any? = try {
        val r = block()
        AppLogger.i(TAG, "  Katman4: $label → ${if (r != null) "OK ($r)" else "null"}")
        r
    } catch (e: Exception) {
        AppLogger.d(TAG, "  Katman4: $label → ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun tryGetVpmService(vpm: Any) {
        if (sVpmService != null) return
        for (cls in generateSequence<Class<*>>(vpm.javaClass) { it.superclass }) {
            try {
                val f = cls.getDeclaredField("mIVehiclePropertyService")
                f.isAccessible = true
                val svc = f.get(vpm)
                if (svc != null) {
                    sVpmService = svc
                    AppLogger.i(TAG, "  Katman4: mIVehiclePropertyService READY ✓")
                    // Notify any pending Katman4 listeners
                    val toNotify = katman4ReadyListeners.toList()
                    katman4ReadyListeners.clear()
                    Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
                }
                return
            } catch (_: NoSuchFieldException) { continue } catch (_: Exception) { return }
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // SWI133 — VehicleSettingManager (ELK : getLaneKeepingAsstMode / setLaneKeepingAsstMode)
    // Même singleton que SWI68 mais initialisé dans le chemin SWI133.
    // -------------------------------------------------------------------------

    private fun tryInitVsm133(launcherCtx: Context, appCtx: Context) {
        if (sVsm133 != null) return
        try {
            val vsmClass = launcherCtx.classLoader.loadClass(VSM_CLASS)

            // Tentative 1 : lire le singleton déjà initialisé par le launcher
            val f = vsmClass.getDeclaredField("sVehicleSettingManager")
            f.isAccessible = true
            val singleton = f.get(null)
            if (singleton != null) {
                sVsm133 = singleton
                AppLogger.i(TAG, "  SWI133: VehicleSettingManager singleton ✓")
                return
            }

            // Tentative 2 : appeler init() nous-mêmes (comme SWI68)
            val initMethod = vsmClass.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 2 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AppLogger.w(TAG, "  SWI133: VSM init() non trouvé, singleton sera null")
                return
            }
            val listenerType = initMethod.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        AppLogger.i(TAG, "  SWI133: VSM onServiceConnected ✓")
                        try {
                            val f2 = vsmClass.getDeclaredField("sVehicleSettingManager")
                            f2.isAccessible = true
                            sVsm133 = f2.get(null)
                            AppLogger.i(TAG, "  SWI133: sVsm133 = ${if (sVsm133 != null) "OK ✓" else "null"}")
                        } catch (_: Exception) {}
                    }
                    null
                }
            } else null
            initMethod.invoke(null, appCtx, listener)
            AppLogger.i(TAG, "  SWI133: VehicleSettingManager.init() called")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI133: tryInitVsm133 exc: ${e.message}")
        }
    }

    /**
     * Appelle une méthode sur sVsm133 par réflexion.
     * Retourne la valeur (Int pour getters, null pour setters void) ou null si erreur.
     */
    private fun callVsm133(methodName: String, vararg args: Any?): Any? {
        val vsm = sVsm133 ?: return null
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            vsm.javaClass.getMethod(methodName, *types).invoke(vsm, *args)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI133/VSM: $methodName() exc: ${e.message}")
            null
        }
    }

    // Katman4 SWI68 — VehicleSettingManager via saicmotor.hmi.launcher context
    // -------------------------------------------------------------------------

    private fun initKatman4Swi68(context: Context) {
        if (sVsm != null) return
        val launcherCtx: Context
        val vsmClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            vsmClass = launcherCtx.classLoader.loadClass(VSM_CLASS)
            AppLogger.i(TAG, "  SWI68: VehicleSettingManager class found ✓")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: class load error: ${e.message} — retry in 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi68(context.applicationContext) }, 5_000)
            return
        }

        // Appel static : VehicleSettingManager.init(Context, IVehicleServiceListener)
        val initMethod = vsmClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }

        if (initMethod != null) {
            val listenerType = initMethod.parameterTypes[1]
            val listenerArg: Any? = if (listenerType.isInterface) {
                try {
                    java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.classLoader, arrayOf(listenerType)
                    ) { _, method, _ ->
                        if (method.name == "onServiceConnected") {
                            AppLogger.i(TAG, "  SWI68: VehicleSettingManager onServiceConnected ✓")
                            sVsm?.let { tryGetVsmService(it, vsmClass) }
                        }
                        null
                    }
                } catch (e: Exception) { null.also { AppLogger.d(TAG, "  SWI68: proxy error: ${e.message}") } }
            } else null

            try {
                initMethod.invoke(null, context, listenerArg)
                AppLogger.i(TAG, "  SWI68: VehicleSettingManager.init() called")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  SWI68: init() error: ${e.message}")
            }
        } else {
            AppLogger.w(TAG, "  SWI68: init(Context, listener) non trouvé")
        }

        // Récupère le singleton depuis le champ statique sVehicleSettingManager
        try {
            val f = vsmClass.getDeclaredField("sVehicleSettingManager")
            f.isAccessible = true
            sVsm = f.get(null)
            AppLogger.i(TAG, "  SWI68: sVehicleSettingManager = ${if (sVsm != null) "OK ✓" else "null"}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: sVehicleSettingManager field error: ${e.message}")
        }

        sVsm?.let { tryGetVsmService(it, vsmClass) }

        // Retries pour récupérer mVehicleSettingService et le singleton si pas encore prêt
        val h = Handler(Looper.getMainLooper())
        listOf(1_000L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L).forEach { delay ->
            h.postDelayed({
                if (sVsm == null) {
                    try {
                        val f = vsmClass.getDeclaredField("sVehicleSettingManager")
                        f.isAccessible = true
                        sVsm = f.get(null)
                        if (sVsm != null) AppLogger.i(TAG, "  SWI68: singleton récupéré @${delay}ms")
                    } catch (_: Exception) {}
                }
                sVsm?.let { if (sVsmService == null) tryGetVsmService(it, vsmClass) }
            }, delay)
        }
    }

    private fun tryGetVsmService(vsm: Any, vsmClass: Class<*>? = null) {
        if (sVsmService != null) return
        val cls = vsmClass ?: vsm.javaClass
        for (c in generateSequence<Class<*>>(cls) { it.superclass }) {
            try {
                val f = c.getDeclaredField("mVehicleSettingService")
                f.isAccessible = true
                val svc = f.get(vsm)
                if (svc != null) {
                    sVsmService = svc
                    AppLogger.i(TAG, "  SWI68: mVehicleSettingService READY ✓")
                    val toNotify = katman4ReadyListeners.toList()
                    katman4ReadyListeners.clear()
                    Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
                }
                return
            } catch (_: NoSuchFieldException) { continue } catch (_: Exception) { return }
        }
    }

    // -------------------------------------------------------------------------
    // Katman4 SWI69/SWI131 — CarVehicleSettingClient via CarAdapterClient
    //
    // Architecture réelle (vérifiée dans smali) :
    //   CarAdapterClient.getInstance(ctx).start()
    //   → bindService(com.saicmotor.caradapter / CarAdapterService)
    //   → onResult(0=OK) : queryClient(0x8) → IBinder (ICarVehicleSettingService)
    //   → new CarVehicleSettingClient(ibinder)
    //
    // CarVehicleSettingClient expose exactement les mêmes méthodes que VehicleSettingManager
    // (getAccTjaState, setAccTjaState, getLasWarningSound, getFcwState, etc.)
    // -------------------------------------------------------------------------

    private fun initKatman4Swi69(context: Context) {
        if (sVsm != null) return

        val launcherCtx: Context
        val adapterClass: Class<*>
        val clientClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER69_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            clientClass  = launcherCtx.classLoader.loadClass(VSM69_CLIENT_CLASS)
            AppLogger.i(TAG, "  SWI69: CarAdapterClient + CarVehicleSettingClient classes found ✓")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI69: class load error: ${e.message} — retry in 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi69(context.applicationContext) }, 5_000)
            return
        }

        // Obtenir le singleton CarAdapterClient
        val adapter = tryInvoke("SWI69 CarAdapterClient.getInstance(appCtx)") {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
        } ?: tryInvoke("SWI69 CarAdapterClient.getInstance(launcherCtx)") {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, launcherCtx)
        }

        if (adapter == null) {
            AppLogger.w(TAG, "  SWI69: CarAdapterClient.getInstance() failed — retry in 10s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi69(context.applicationContext) }, 10_000)
            return
        }

        // Enregistrer le ServiceConnListener (onResult(0) = connecté)
        val listenerType = adapterClass.declaredClasses
            .firstOrNull { it.simpleName == "ServiceConnListener" }
        if (listenerType != null && listenerType.isInterface) {
            try {
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, method, args ->
                    if (method.name == "onResult") {
                        val code = (args?.getOrNull(0) as? Int) ?: -1
                        AppLogger.i(TAG, "  SWI69: CarAdapterClient.onResult($code)")
                        if (code == 0) tryInitClientFromAdapter(adapter, adapterClass, clientClass)
                    }
                    null
                }
                adapterClass.getMethod("setConnListener", listenerType).invoke(adapter, proxy)
                AppLogger.i(TAG, "  SWI69: ServiceConnListener registered ✓")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  SWI69: setConnListener error: ${e.message}")
            }
        }

        // Démarrer la connexion à CarAdapterService
        tryInvoke("SWI69 adapter.start()") {
            adapterClass.getMethod("start").invoke(adapter)
        }

        // Tentative immédiate si CarAdapterService était déjà connecté
        tryInitClientFromAdapter(adapter, adapterClass, clientClass)

        // Retries échelonnés
        val h = Handler(Looper.getMainLooper())
        listOf(1_000L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (sVsm == null) tryInitClientFromAdapter(adapter, adapterClass, clientClass)
            }, delay)
        }
    }

    /**
     * Tente d'obtenir un CarVehicleSettingClient via queryClient(0x8).
     * Appelée à la connexion (onResult=0) et lors des retries.
     */
    private fun tryInitClientFromAdapter(adapter: Any, adapterClass: Class<*>, clientClass: Class<*>) {
        if (sVsm != null) return
        try {
            val ibinder = adapterClass
                .getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, VSM_SERVICE_CODE) as? IBinder

            if (ibinder == null) {
                AppLogger.d(TAG, "  SWI69: queryClient(0x${VSM_SERVICE_CODE.toString(16)}) → null (pas encore connecté)")
                return
            }

            val client = clientClass
                .getConstructor(IBinder::class.java)
                .newInstance(ibinder)

            sVsm        = client
            sVsmService = ibinder
            AppLogger.i(TAG, "  SWI69: CarVehicleSettingClient READY ✓")

            val toNotify = katman4ReadyListeners.toList()
            katman4ReadyListeners.clear()
            Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI69: tryInitClientFromAdapter error: ${e.message}")
        }
    }

    private fun callVsm(methodName: String, vararg args: Any?): Any? {
        val vsm = sVsm ?: return null
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            vsm.javaClass.getMethod(methodName, *types).invoke(vsm, *args)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: $methodName() exc: ${e.message}")
            null
        }
    }

    private fun getIntPropertyVpm(propId: Int): Int {
        val vpm = sVpm ?: return -1
        return try {
            (vpm.javaClass.getMethod("getIntProperty", Int::class.java)
                .invoke(vpm, propId) as? Int) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun setIntPropertyVpm(propId: Int, value: Int): Boolean {
        val vpm = sVpm ?: return false
        return try {
            vpm.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java)
                .invoke(vpm, propId, value)
            true
        } catch (_: Exception) { false }
    }

    /** Variante avec recovery — utilisée par vehiclesettings pour les propriétés FCW/AEB. */
    private fun setIntPropertyVpmRecovery(propId: Int, value: Int): Boolean {
        val vpm = sVpm ?: return false
        return try {
            vpm.javaClass.getMethod("setIntPropertyRecovery", Int::class.java, Int::class.java)
                .invoke(vpm, propId, value)
            if (logEnabled) AppLogger.i(TAG, "  VPM setIntRecovery 0x${propId.toString(16)} value=$value ✓")
            true
        } catch (e: Exception) {
            // Fallback sur setIntProperty si setIntPropertyRecovery absent
            AppLogger.d(TAG, "  VPM setIntRecovery fallback for 0x${propId.toString(16)}: ${e.message}")
            setIntPropertyVpm(propId, value)
        }
    }

    private fun getMixIntProperty(propId: Int): Int {
        val vpm = sVpm ?: return -1
        return try {
            // Méthode réelle sur VPM : getMixProperty(Class, int)
            val result = vpm.javaClass
                .getMethod("getMixProperty", Class::class.java, Int::class.java)
                .invoke(vpm, Int::class.javaObjectType, propId)
            when (result) {
                is Int    -> result
                is Number -> result.toInt()
                null      -> { AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) = null"); -1 }
                else      -> { AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) = $result (${result.javaClass.simpleName})"); -1 }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) exc: ${e.message}")
            -1
        }
    }

    private fun setMixIntProperty(propId: Int, value: Int): Boolean {
        val vpm = sVpm ?: return false
        return try {
            // Méthode réelle sur VPM : setMixProperty(Class, int, Object)
            vpm.javaClass
                .getMethod("setMixProperty", Class::class.java, Int::class.java, Any::class.java)
                .invoke(vpm, Int::class.javaObjectType, propId, value)
            AppLogger.i(TAG, "  Katman4: setMixProperty(0x${propId.toString(16)}, $value) ✓")
            true
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: setMixProperty(0x${propId.toString(16)}, $value) exc: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Low-level property accessors
    // -------------------------------------------------------------------------

    private fun getIntPropertyCPM(propId: Int, areaId: Int): Int {
        val cpm = sCarPropertyManager ?: return -1
        return try {
            (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, propId, areaId) as? Int) ?: -1
        } catch (e: Exception) {
            AppLogger.d(TAG, "  CPM getInt 0x${Integer.toHexString(propId)} exc: ${e.message}")
            -1
        }
    }

    private fun setIntPropertyCPM(propId: Int, areaId: Int, value: Int): Boolean {
        val cpm = sCarPropertyManager ?: run {
            AppLogger.w(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} — CPM not ready")
            return false
        }
        return try {
            cpm.javaClass
                .getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                .invoke(cpm, propId, areaId, value)
            if (logEnabled) AppLogger.i(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} area=0x${Integer.toHexString(areaId)} value=$value ✓")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} error: ${e.message}")
            false
        }
    }

    fun getIntPropertyHvac(propId: Int, areaId: Int): Int {
        val hvac = sCarHvacManager ?: return -1
        return try {
            (hvac.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(hvac, propId, areaId) as? Int) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun setIntPropertyHvac(propId: Int, areaId: Int, value: Int): Boolean {
        val hvac = sCarHvacManager ?: return false
        return try {
            hvac.javaClass
                .getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                .invoke(hvac, propId, areaId, value)
            true
        } catch (_: Exception) { false }
    }

    /**
     * SAIC proprietary binder transact (Katman2 fallback).
     * Parcel layout from smali: [interfaceToken, AREA_GLOBAL, 1, value, float[], byte[]]
     */
    private fun binderTransact(binder: IBinder?, descriptor: String, txCode: Int, value: Int): Boolean {
        if (binder == null) {
            AppLogger.w(TAG, "  Binder TX=$txCode — binder null")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(AREA_GLOBAL)
            data.writeInt(1)
            data.writeInt(value)
            data.writeFloatArray(FloatArray(0))
            data.writeByteArray(ByteArray(0))
            binder.transact(txCode, data, reply, 0)
            val status = if (reply.dataAvail() > 0) reply.readInt() else 0
            if (logEnabled) AppLogger.i(TAG, "  Binder TX=$txCode value=$value → status=$status ${if (status == 0) "✓" else "✗ REJECTED"}")
            status == 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "  binderTransact error: ${e.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * HVAC toggle — cycles the property by sending value=1 until target is reached.
     * Timeout: 7 seconds (from original smali: 0x1b58 ms = 7000 ms).
     */
    fun setHvacLevelWithToggle(propId: Int, areaId: Int, targetLevel: Int): Boolean {
        val deadline = System.currentTimeMillis() + 7_000L
        var lastClickMs = 0L
        while (System.currentTimeMillis() < deadline) {
            val current = getIntPropertyHvac(propId, areaId)
            if (current == targetLevel) {
                if (logEnabled) AppLogger.i(TAG, "HVAC target reached: $targetLevel")
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastClickMs >= 500L) {
                if (logEnabled) AppLogger.i(TAG, "HVAC click → current=$current target=$targetLevel")
                setIntPropertyHvac(propId, areaId, 1)
                lastClickMs = now
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            } else {
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
            }
        }
        AppLogger.e(TAG, "HVAC timeout! prop=0x${Integer.toHexString(propId)}")
        return false
    }

    // -------------------------------------------------------------------------
    // Public vehicle control API
    // -------------------------------------------------------------------------

    fun setDriveMode(mode: DriveMode): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setDriveMode → ${mode.label} (${mode.value})")
        val ok = setIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL, mode.value)
        if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_DRIVE_MODE, mode.value)
        sAppContext?.getSharedPreferences(PREFS_NAME, 0)?.edit()
            ?.putInt(KEY_LAST_DRIVE_MODE, mode.value)?.apply()
        return ok
    }

    fun setRegenLevel(level: RegenLevel): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setRegenLevel → ${level.label} (${level.value})")
        return if (level == RegenLevel.ONE_PEDAL) {
            val ok = setOnePedal(true)
            if (!ok) {
                setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value)
                binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value)
            }
            ok
        } else {
            setOnePedal(false)
            val ok = setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value)
            if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value)
            ok
        }
    }

    fun setOnePedal(enabled: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setOnePedal → ${if (enabled) "On" else "Off"}")
        val intVal = if (enabled) 1 else 0
        val ok = setIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL, intVal)
        if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, intVal)
        return ok
    }

    fun getSeatHeatLeft(): Int  = getIntPropertyHvac(PROP_SEAT_HEAT_L, AREA_HVAC).coerceAtLeast(0)
    fun getSeatHeatRight(): Int = getIntPropertyHvac(PROP_SEAT_HEAT_R, AREA_HVAC).coerceAtLeast(0)
    fun isSteeringHeatOn(): Boolean = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC) > 0

    fun getDriveMode(): DriveMode? {
        val cpm = sCarPropertyManager ?: return null
        return try {
            val raw = (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_DRIVE_MODE, AREA_GLOBAL) as? Int) ?: return null
            DriveMode.fromValue(raw)
        } catch (_: Exception) { null }
    }

    fun getRegenLevel(): RegenLevel? {
        val cpm = sCarPropertyManager ?: return null
        return try {
            val raw = (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_REGEN_LEVEL, AREA_GLOBAL) as? Int) ?: return null
            RegenLevel.fromValue(raw)
        } catch (_: Exception) { null }
    }

    fun setSeatHeatLeft(level: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSeatHeatLeft → $level")
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_L, AREA_HVAC, level)
    }

    fun setSeatHeatRight(level: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSeatHeatRight → $level")
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_R, AREA_HVAC, level)
    }

    fun setSteeringHeat(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSteeringHeat → $on")
        val current = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC)
        if ((current > 0) == on) return true
        // Send a single click and wait for state confirmation (avoids on/off oscillation)
        setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, 1)
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            if ((getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC) > 0) == on) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // ADAS API (Katman4)
    // -------------------------------------------------------------------------

    fun isOverspeedAlarmOn(): Boolean = getIntPropertyVpm(PROP_OVERSPEED_ALARM) > 0
    fun setOverspeedAlarm(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setOverspeedAlarm → $on")
        return setIntPropertyVpm(PROP_OVERSPEED_ALARM, if (on) 1 else 0)
    }

    fun isSpeedLimitToneOn(): Boolean = getIntPropertyVpm(PROP_SPEED_LIMIT_TONE) > 0
    fun setSpeedLimitTone(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSpeedLimitTone → $on")
        return setIntPropertyVpm(PROP_SPEED_LIMIT_TONE, if (on) 1 else 0)
    }

    /** Returns 0–4 (Off/Limiteur/Auto/ACC/ICA), or -1 if Katman4 not ready. */
    fun getMixedIntelligentDrive(): Int = getMixIntProperty(PROP_MIX_INTELLIGENT_DRIVE)
    fun setMixedIntelligentDrive(value: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setMixedIntelligentDrive → $value")
        // Primary: setMixProperty (smali-accurate). Fallback: setIntProperty if method missing.
        if (setMixIntProperty(PROP_MIX_INTELLIGENT_DRIVE, value)) return true
        return setIntPropertyVpm(PROP_MIX_INTELLIGENT_DRIVE, value)
    }

    // ── SWI68 / SWI69 ADAS API — VehicleSettingManager (noms de méthodes différents) ──

    /**
     * Retourne le mode ACC/TJA actuel (0x4=Off, 0x1=ACC, 0x2=TJA), ou -1 si pas prêt.
     * SWI68 : getAccTjaMode()   SWI69/SWI131 : getAccTjaState()
     */
    fun getAccTjaMode(): Int {
        val method = if (FirmwareInfo.isNewGenVsm()) "getAccTjaState" else "getAccTjaMode"
        if (logEnabled) AppLogger.i(TAG, "$method →")
        return (callVsm(method) as? Int) ?: -1
    }

    /** SWI68 : setAccTjaMode(I)   SWI69/SWI131 : setAccTjaState(I) */
    fun setAccTjaMode(mode: Int): Boolean {
        val method = if (FirmwareInfo.isNewGenVsm()) "setAccTjaState" else "setAccTjaMode"
        if (logEnabled) AppLogger.i(TAG, "$method → 0x${mode.toString(16)}")
        callVsm(method, mode) ?: return false
        return true
    }

    /**
     * SWI68 : getLaneKeepingWarningSound()   SWI69/SWI131 : getLasWarningSound()
     * Valeurs : 2=ON / 1=OFF
     */
    fun isSoundWarningOn(): Boolean {
        val method = if (FirmwareInfo.isNewGenVsm()) "getLasWarningSound" else "getLaneKeepingWarningSound"
        return ((callVsm(method) as? Int) ?: 1) == 2
    }

    /** SWI68 : setLaneKeepingWarningSound(I)   SWI69/SWI131 : setLasWarningSound(I) */
    fun setSoundWarning(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSoundWarning → $on")
        val method = if (FirmwareInfo.isNewGenVsm()) "setLasWarningSound" else "setLaneKeepingWarningSound"
        callVsm(method, if (on) 2 else 1) ?: return false
        return true
    }

    // ── AEB — Système anti-collision avant ──────────────────────────────────

    /**
     * Retourne true si le système anti-collision avant est activé.
     * SWI133          : lit PROP_AEB_SWITCH (2=ON, 1=OFF) via CarPropertyManager.
     * SWI68 / SWI165  : getFcwAlarmMode() == 2  (FCW_ALARM_ON=2, FCW_ALARM_OFF=1)
     *                   Vérifié dans SafeSettingsRepository SWI165 — même API que SWI68.
     * SWI69 / SWI131  : getFcwState() — 1=DÉSACTIVÉ, 2=ACTIVÉ
     */
    fun isAebEnabled(): Boolean {
        return when {
            FirmwareInfo.isNewGenVsm() -> (callVsm("getFcwState") as? Int) == 2
            FirmwareInfo.isVsmBased()  -> (callVsm("getFcwAlarmMode") as? Int) == 2
            else                       -> getIntPropertyCPM(PROP_AEB_SWITCH, AREA_GLOBAL) == 0x2
        }
    }

    fun setAebEnabled(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setAebEnabled → $on")
        return when {
            FirmwareInfo.isNewGenVsm() -> {
                // SWI69/SWI131 — séquences identiques au launcher officiel :
                // OFF : setFcwState(1) + setFcwAutoBrakeMode(1) + setFcwSensitivity(0)
                // ON  : setFcwState(2) + setFcwAutoBrakeMode(curMode)
                // Le launcher conditionne son affichage à fcwState==1 AND autoBreakState==1
                // → sans setFcwAutoBrakeMode, son switch reste ON même quand l'AEB est désactivé
                if (on) {
                    val sOk = callVsm("setFcwState", 2) != null
                    val curMode = (callVsm("getFcwAutoBrakeMode") as? Int) ?: 1
                    val mOk = callVsm("setFcwAutoBrakeMode", curMode) != null
                    sOk || mOk
                } else {
                    callVsm("setFcwState", 1)
                    callVsm("setFcwAutoBrakeMode", 1)
                    callVsm("setFcwSensitivity", 0) != null
                }
            }
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68 ||
            FirmwareInfo.isSWI165() -> {
                // SWI68 / SWI165 : setFcwAlarmMode(2=ON / 1=OFF) + setFcwAutoBrakeMode(1) si OFF
                // Vérifié dans SafeSettingsRepository SWI165 — même API que SWI68,
                // setAutoEmergencyBraking() n'est jamais utilisé par l'app officielle.
                if (on) callVsm("setFcwAlarmMode", 2) != null
                else { (callVsm("setFcwAlarmMode", 1) != null) or (callVsm("setFcwAutoBrakeMode", 1) != null) }
            }
            else -> setIntPropertyCPM(PROP_AEB_SWITCH, AREA_GLOBAL, if (on) 0x2 else 0x1)
        }
    }

    /**
     * Retourne le mode AEB courant (1=Alerte, 2=Alerte+Freinage), ou -1 si pas prêt.
     * SWI133          : PROP_AEB_MODE (0x302000b) via VehiclePropertyManager.
     * SWI68/SWI69/SWI131 : getFcwAutoBrakeMode() (1=Alerte, 2=Alerte+Freinage).
     */
    fun getAebMode(): Int {
        return if (FirmwareInfo.isVsmBased()) {
            (callVsm("getFcwAutoBrakeMode") as? Int) ?: AebMode.ALARM
        } else {
            val raw = getIntPropertyVpm(PROP_AEB_MODE)
            if (raw < 1) -1 else raw
        }
    }

    fun setAebMode(mode: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setAebMode → $mode")
        return when {
            FirmwareInfo.isNewGenVsm() -> {
                // SWI69/SWI131 : il faut fixer le mode VIA setFcwAutoBrakeMode,
                // puis appeler setFcwState(2) pour que le service applique le nouveau mode.
                // setFcwState(2) seul active avec le MODE COURANT stocké — il ne change pas le mode.
                // L'ordre : 1) fixer le mode, 2) activer (commit le mode).
                val modeVal = if (mode == AebMode.ALARM_BRAKE) 2 else 1
                val mOk = callVsm("setFcwAutoBrakeMode", modeVal) != null
                val sOk = callVsm("setFcwState", 2) != null
                mOk || sOk
            }
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68 ||
            FirmwareInfo.isSWI165() -> {
                // SWI68 / SWI165 : setFcwAutoBrakeMode uniquement (1=Alerte, 2=Alerte+Freinage)
                callVsm("setFcwAutoBrakeMode", if (mode == AebMode.ALARM_BRAKE) 2 else 1) != null
            }
            else -> {
                // SWI133 smali exact
                if (mode == AebMode.ALARM_BRAKE) {
                    val r1 = setIntPropertyVpmRecovery(PROP_AEB_SYS_MODE, AebMode.ALARM_BRAKE)
                    val r2 = setIntPropertyVpmRecovery(PROP_AEB_MODE, AebMode.ALARM_BRAKE)
                    r1 || r2
                } else {
                    setIntPropertyVpmRecovery(PROP_AEB_MODE, AebMode.ALARM)
                }
            }
        }
    }

    /**
     * Retourne la sensibilité AEB courante (1=Faible, 2=Standard, 3=Élevé), ou -1 si pas prêt.
     * SWI133         : PROP_AEB_SENSITIVITY (0x302000e, VPM)
     * SWI68/SWI165   : VehicleSettingManager.getFcwSensitivity()
     * SWI69/SWI131   : CarVehicleSettingClient.getFcwSensitivity()
     */
    fun getAebSensitivity(): Int {
        return if (FirmwareInfo.isVsmBased()) {
            (callVsm("getFcwSensitivity") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  AEB GET sensitivity=$it via VSM ✓")
            } ?: -1
        } else {
            val raw = getIntPropertyVpm(PROP_AEB_SENSITIVITY)
            if (raw < 1) -1 else raw
        }
    }

    /**
     * Définit la sensibilité AEB (1=Faible, 2=Standard, 3=Élevé).
     * SWI133         : PROP_AEB_SENSITIVITY (0x302000e, VPM)
     * SWI68/SWI165   : VehicleSettingManager.setFcwSensitivity(I)
     * SWI69/SWI131   : CarVehicleSettingClient.setFcwSensitivity(I)
     */
    fun setAebSensitivity(level: Int): Boolean {
        return if (FirmwareInfo.isVsmBased()) {
            AppLogger.i(TAG, "  AEB SET sensitivity=$level via VSM")
            callVsm("setFcwSensitivity", level) != null
        } else {
            AppLogger.i(TAG, "  AEB SET sensitivity=$level via VPM")
            setIntPropertyVpmRecovery(PROP_AEB_SENSITIVITY, level)
        }
    }

    // -------------------------------------------------------------------------
    // ELK — Assistant de sortie de voie (SWI133 uniquement pour l'instant)
    // Utilise IVehicleSettingService via sVehicleBinder (TX 0x53–0x56)
    // -------------------------------------------------------------------------

    /**
     * Retourne le mode ELK courant (1=OFF, 2=Alerte, 3=Aider, 5=Maintien d'urgence).
     * Routage par firmware :
     *   SWI133         → VSM133 (getLaneKeepingAsstMode) → binder TX 0x53
     *   SWI68/SWI165   → VSM    (getLaneKeepingAsstMode)
     *   SWI69/SWI131   → VSM    (getLasMode)
     */
    fun getElkMode(): Int = when {
        !FirmwareInfo.isVsmBased() -> {
            val vsm = callVsm133("getLaneKeepingAsstMode")
            if (vsm is Int && vsm > 0) {
                AppLogger.d(TAG, "  ELK GET mode=$vsm via VSM133 ✓")
                vsm
            } else elkBinderGet(TX_ELK_GET_MODE)
        }
        FirmwareInfo.isNewGenVsm() -> {
            // SWI69/SWI131 — CarVehicleSettingClient
            (callVsm("getLasMode") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET mode=$it via VSM (Las) ✓")
            } ?: -1
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            (callVsm("getLaneKeepingAsstMode") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET mode=$it via VSM ✓")
            } ?: -1
        }
    }

    /**
     * Définit le mode ELK.
     * Routage identique à getElkMode().
     */
    fun setElkMode(mode: Int): Boolean = when {
        !FirmwareInfo.isVsmBased() -> {
            if (sVsm133 != null) {
                AppLogger.i(TAG, "  ELK SET mode=$mode via VSM133")
                callVsm133("setLaneKeepingAsstMode", mode)
                true
            } else elkBinderSet(TX_ELK_SET_MODE, mode)
        }
        FirmwareInfo.isNewGenVsm() -> {
            AppLogger.i(TAG, "  ELK SET mode=$mode via VSM (Las)")
            callVsm("setLasMode", mode)
            true
        }
        else -> {
            AppLogger.i(TAG, "  ELK SET mode=$mode via VSM")
            callVsm("setLaneKeepingAsstMode", mode)
            true
        }
    }

    /**
     * Retourne la sensibilité ELK courante (1=Faible, 2=Standard, 3=Élevé).
     * Routage par firmware — identique à getElkMode().
     */
    fun getElkSensitivity(): Int = when {
        !FirmwareInfo.isVsmBased() -> {
            val vsm = callVsm133("getLaneKeepingAsstSen")
            if (vsm is Int && vsm > 0) {
                AppLogger.d(TAG, "  ELK GET sen=$vsm via VSM133 ✓")
                vsm
            } else elkBinderGet(TX_ELK_GET_SEN)
        }
        FirmwareInfo.isNewGenVsm() -> {
            // SWI69/SWI131 — CarVehicleSettingClient
            (callVsm("getLasSensitivity") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET sen=$it via VSM (Las) ✓")
            } ?: -1
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            (callVsm("getLaneKeepingAsstSen") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET sen=$it via VSM ✓")
            } ?: -1
        }
    }

    /**
     * Définit la sensibilité ELK.
     * Routage identique à getElkMode().
     */
    fun setElkSensitivity(level: Int): Boolean = when {
        !FirmwareInfo.isVsmBased() -> {
            if (sVsm133 != null) {
                AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM133")
                callVsm133("setLaneKeepingAsstSen", level)
                true
            } else elkBinderSet(TX_ELK_SET_SEN, level)
        }
        FirmwareInfo.isNewGenVsm() -> {
            AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM (Las)")
            callVsm("setLasSensitivity", level)
            true
        }
        else -> {
            AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM")
            callVsm("setLaneKeepingAsstSen", level)
            true
        }
    }

    /** Retourne true si l'ELK est activé (mode ≠ OFF). */
    fun isElkEnabled(): Boolean {
        val mode = getElkMode()
        return mode > 0 && mode != ElkMode.OFF
    }

    /**
     * GET via IVehicleSettingService binder — layout smali :
     *   data : [writeInterfaceToken]
     *   transact(code, data, reply, 0)
     *   reply: readException() + readInt()
     */
    private fun elkBinderGet(txCode: Int): Int {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return -1
        }
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            val ok = binder.transact(txCode, data, reply, 0)
            if (!ok) {
                AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} — transact returned false")
                return -1
            }
            reply.readException()
            val result = reply.readInt()
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} = $result")
            result
        } catch (e: Exception) {
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} exc: ${e.message}")
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * SET via IVehicleSettingService binder — layout smali :
     *   data : [writeInterfaceToken, writeInt(value)]
     *   transact(code, data, null, FLAG_ONEWAY=1)
     */
    private fun elkBinderSet(txCode: Int, value: Int): Boolean {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  ELK SET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return false
        }
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            data.writeInt(value)
            binder.transact(txCode, data, null, IBinder.FLAG_ONEWAY)
            AppLogger.i(TAG, "  ELK SET TX=0x${txCode.toString(16)} value=$value ✓")
            true
        } catch (e: Exception) {
            AppLogger.d(TAG, "  ELK SET TX=0x${txCode.toString(16)} exc: ${e.message}")
            false
        } finally {
            data.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // TSR — Reconnaissance des panneaux de vitesse
    // -------------------------------------------------------------------------

    fun isTsrOn(): Boolean = when {
        FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
            getIntPropertyVpm(PROP_TSR_MODE) > 0
        FirmwareInfo.isNewGenVsm() ->   // SWI69 + SWI131 — convention inversée : 0=ON, 1=OFF
            (callVsm("getSLIFWarningState") as? Int) == 0
        FirmwareInfo.isVsmBased() ->    // SWI68 + SWI165
            (callVsm("getSpeedAsstSlifWarning") as? Int) == 1
        else -> false
    }

    fun setTsrMode(enabled: Boolean): Boolean {
        AppLogger.i(TAG, "setTsrMode → $enabled")
        return when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 -> {
                // SWI133 : le firmware remet OVERSPEED et SPEED_TONE à ON quand le SLIF est réactivé
                // → on sauvegarde avant et on restaure après.
                val prefs = sAppContext?.getSharedPreferences("mg4_settings", 0)
                if (!enabled) {
                    val overspeedOn = isOverspeedAlarmOn()
                    val speedToneOn = isSpeedLimitToneOn()
                    prefs?.edit()
                        ?.putBoolean("tsr_saved_overspeed", overspeedOn)
                        ?.putBoolean("tsr_saved_speed_tone", speedToneOn)
                        ?.apply()
                    AppLogger.i(TAG, "  TSR OFF — sauvegarde overspeed=$overspeedOn speedTone=$speedToneOn")
                }
                val ok = setIntPropertyVpmRecovery(PROP_TSR_MODE, if (enabled) 1 else 0)
                if (enabled && ok) {
                    Thread.sleep(400)
                    val savedOverspeed = prefs?.getBoolean("tsr_saved_overspeed", true) ?: true
                    val savedSpeedTone = prefs?.getBoolean("tsr_saved_speed_tone", true) ?: true
                    AppLogger.i(TAG, "  TSR ON — restauration overspeed=$savedOverspeed speedTone=$savedSpeedTone")
                    setOverspeedAlarm(savedOverspeed)
                    setSpeedLimitTone(savedSpeedTone)
                }
                ok
            }
            FirmwareInfo.isNewGenVsm() -> {   // SWI69 + SWI131 — convention inversée : 0=activer, 1=désactiver
                callVsm("setSLIFWarningState", if (enabled) 0 else 1) ?: return false
                true
            }
            FirmwareInfo.isVsmBased() -> {    // SWI68 + SWI165
                // L'avertissement sonore pourrait être remis à ON lors de la réactivation du TSR
                // → on sauvegarde avant et on restaure après.
                val prefs = sAppContext?.getSharedPreferences("mg4_settings", 0)
                if (!enabled) {
                    val soundOn = isSoundWarningOn()
                    prefs?.edit()?.putBoolean("tsr_saved_sound_warning", soundOn)?.apply()
                    AppLogger.i(TAG, "  TSR OFF — sauvegarde soundWarning=$soundOn")
                }
                callVsm("setSpeedAsstSlifWarning", if (enabled) 1 else 0) ?: return false
                if (enabled) {
                    Thread.sleep(400)
                    val savedSound = prefs?.getBoolean("tsr_saved_sound_warning", true) ?: true
                    AppLogger.i(TAG, "  TSR ON — restauration soundWarning=$savedSound")
                    setSoundWarning(savedSound)
                }
                true
            }
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Économie d'énergie (Endurance Mode)
    // -------------------------------------------------------------------------

    fun isEnergySavingOn(): Boolean = when {
        FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
            getIntPropertyVpm(PROP_ENERGY_SAVING) == 1
        FirmwareInfo.isNewGenVsm() ->       // SWI69 + SWI131
            (callVsm("getEnduranceMode") as? Int) == 1
        FirmwareInfo.isVsmBased() ->        // SWI68 + SWI165
            (callVsm("getLongerEndurance") as? Int) == 1
        else -> false
    }

    fun setEnergySavingMode(enabled: Boolean): Boolean {
        AppLogger.i(TAG, "setEnergySavingMode → $enabled")
        return when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
                setIntPropertyVpmRecovery(PROP_ENERGY_SAVING, if (enabled) 1 else 0)
            FirmwareInfo.isNewGenVsm() -> {  // SWI69 + SWI131
                callVsm("setEnduranceMode", if (enabled) 1 else 0) ?: return false
                true
            }
            FirmwareInfo.isVsmBased() -> {   // SWI68 + SWI165
                callVsm("setLongerEndurance", if (enabled) 1 else 0) ?: return false
                true
            }
            else -> false
        }
    }

    fun isKatman4Ready(): Boolean =
        if (FirmwareInfo.isVsmBased()) sVsm != null && sVsmService != null
        else                           sVpm != null && sVpmService != null
    fun isKatman4VpmCreated(): Boolean      = sVpm != null || sVsm != null
    fun isCarPropertyManagerReady(): Boolean = sCarPropertyManager != null
    fun isCarHvacManagerReady(): Boolean     = sCarHvacManager != null

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    fun setDriveModeListener(listener: DriveModeListener?) { sDriveModeListener = listener }
    fun setHvacListener(listener: HvacListener?) { sHvacListener = listener }
}
