package ai.labs32.khaata.core.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The runtime permissions bank-SMS import needs.
 *
 * `RECEIVE_SMS` is what actually causes `SMS_RECEIVED` broadcasts to be delivered; `READ_SMS` is
 * requested alongside it because both sit in the same permission group and asking for one without
 * the other produces a second prompt later for no visible reason.
 *
 * Declaring these in the manifest is not enough on its own: they are dangerous permissions, so
 * without a granted runtime grant Android silently delivers nothing at all and the feature looks
 * broken rather than refused.
 */
object SmsPermission {

    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    /** True when the user has granted everything SMS import needs to actually receive messages. */
    fun isGranted(context: Context): Boolean = REQUIRED.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
