package com.mg4.control.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.mg4.control.R

/**
 * Reçoit le résultat de l'installation silencieuse depuis [ApkInstaller].
 * Enregistré dans le Manifest ; appelé par PackageInstaller après commit().
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.mg4.control.ACTION_INSTALL_STATUS"
        private const val TAG = "InstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status  = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        Log.d(TAG, "Install status=$status message=$message")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Installation réussie !")
                Toast.makeText(
                    context,
                    context.getString(R.string.update_installed),
                    Toast.LENGTH_LONG
                ).show()
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Ne devrait pas arriver avec INSTALL_PACKAGES système,
                // mais on ouvre quand même la confirmation si nécessaire.
                @Suppress("DEPRECATION")
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }

            else -> {
                Log.e(TAG, "Installation échouée — status=$status : $message")
                Toast.makeText(
                    context,
                    context.getString(R.string.update_install_error, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
