package com.mg4.control.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * [BT-PROFILES] Utilitaires Bluetooth pour la fonctionnalité de profils automatiques.
 *
 * Les associations Bluetooth ↔ Profil sont désormais stockées directement dans
 * [DrivingProfile.btDeviceMac] — ce fichier ne gère que :
 *   • le suivi en mémoire des appareils ACL connectés
 *   • la liste des appareils appairés (pour l'UI de l'éditeur de profil)
 *   • la requête HFP async (fix "première configuration")
 *
 * Pour supprimer la fonctionnalité : supprimer ce fichier + tous les blocs
 * marqués // [BT-PROFILES] dans les autres fichiers modifiés.
 */
object BluetoothProfileManager {

    // ── Appareils actuellement connectés (en mémoire) ─────────────────────────
    // Mis à jour depuis MG4ControlService via ACTION_ACL_CONNECTED/DISCONNECTED.
    // Remis à zéro si le service redémarre — le chemin HFP async compense ce cas.

    private val connectedMacs = mutableSetOf<String>()

    fun onDeviceConnected(mac: String)    { connectedMacs.add(mac) }
    fun onDeviceDisconnected(mac: String) { connectedMacs.remove(mac) }
    fun getConnectedMacs(): Set<String>   = connectedMacs.toSet()

    // ── Appareils appairés ────────────────────────────────────────────────────

    data class BtDeviceInfo(val name: String, val mac: String)

    /** Retourne la liste des appareils Bluetooth appairés, triée par nom. */
    fun getBondedDevices(context: Context): List<BtDeviceInfo> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (adapter.bondedDevices ?: emptySet<BluetoothDevice>())
                .filter { it.name != null }
                .map { BtDeviceInfo(it.name, it.address) }
                .sortedBy { it.name }
        } catch (_: SecurityException) { emptyList() }
    }

    // ── Appareils HFP actuellement connectés (requête async) ─────────────────
    //
    // Utilisé dans applyProfileOnIgnition() quand connectedMacs est vide,
    // ce qui se produit si le téléphone était déjà connecté avant le démarrage
    // du service (première configuration, redémarrage du service AAOS).
    //
    // On utilise BluetoothProfile.HEADSET (profil HFP AG, rôle voiture) :
    // sur AAOS la voiture est l'Audio Gateway, les téléphones connectés sont
    // donc accessibles via ce profil. HEADSET_CLIENT n'est pas exposé sur AAOS.

    fun checkConnectedHfpDevices(context: Context, onResult: (List<BluetoothDevice>) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null) { onResult(emptyList()); return }
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val connected = try { proxy.connectedDevices ?: emptyList() }
                                    catch (_: Exception) { emptyList() }
                    adapter.closeProfileProxy(profile, proxy)
                    onResult(connected)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } catch (_: Exception) { onResult(emptyList()) }
    }
}
