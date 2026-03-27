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

    // SWI68 : VehicleSettingManager class name (loaded via launcher context)
    private const val VSM_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"

    /** Valeurs de mode ADAS pour firmware SWI68. */
    object Swi68Mode {
        const val OFF = 0x4   // Désactiver
        const val ACC = 0x1   // ACC
        const val TJA = 0x2   // TJA (Traffic Jam Assist)
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
     * SWI133 → mIVehiclePropertyService ; SWI68 → mVehicleSettingService
     */
    fun whenKatman4Ready(action: () -> Unit) {
        val ready = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68)
            sVsmService != null else sVpmService != null
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
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68)
            initKatman4Swi68(context)
        else
            initKatman4(context)
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
                "com.saicmotor.hmi.launcher",
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

        // 3) Retries pour récupérer mIVehiclePropertyService une fois le service connecté
        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L).forEach { delay ->
            h.postDelayed({ if (sVpmService == null) tryGetVpmService(sVpm ?: return@postDelayed) }, delay)
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
    // Katman4 SWI68 — VehicleSettingManager via saicmotor.hmi.launcher context
    // -------------------------------------------------------------------------

    private fun initKatman4Swi68(context: Context) {
        if (sVsm != null) return
        val launcherCtx: Context
        val vsmClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                "com.saicmotor.hmi.launcher",
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
        return setMixIntProperty(PROP_MIX_INTELLIGENT_DRIVE, value)
    }

    // ── SWI68 ADAS API — VehicleSettingManager.setAccTjaMode / setLaneKeepingWarningSound ──

    /** Retourne le mode ACC/TJA actuel (0x4=Off, 0x1=ACC, 0x2=TJA), ou -1 si pas prêt. */
    fun getAccTjaMode(): Int {
        if (logEnabled) AppLogger.i(TAG, "getAccTjaMode →")
        return (callVsm("getAccTjaMode") as? Int) ?: -1
    }

    fun setAccTjaMode(mode: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setAccTjaMode → 0x${mode.toString(16)}")
        callVsm("setAccTjaMode", mode) ?: return false
        return true
    }

    fun isSoundWarningOn(): Boolean {
        return ((callVsm("getLaneKeepingWarningSound") as? Int) ?: 0) > 0
    }

    fun setSoundWarning(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSoundWarning → $on")
        callVsm("setLaneKeepingWarningSound", if (on) 1 else 0) ?: return false
        return true
    }

    fun isKatman4Ready(): Boolean = when (FirmwareInfo.getGeneration()) {
        FirmwareInfo.Gen.SWI68 -> sVsm != null && sVsmService != null
        else                    -> sVpm != null && sVpmService != null
    }
    fun isKatman4VpmCreated(): Boolean      = sVpm != null || sVsm != null
    fun isCarPropertyManagerReady(): Boolean = sCarPropertyManager != null
    fun isCarHvacManagerReady(): Boolean     = sCarHvacManager != null

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    fun setDriveModeListener(listener: DriveModeListener?) { sDriveModeListener = listener }
    fun setHvacListener(listener: HvacListener?) { sHvacListener = listener }
}
