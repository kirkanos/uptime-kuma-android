package net.kirkanos.uptimekuma.push.data

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor
import javax.inject.Inject

/** Thin seam over the static UnifiedPush connector API so the repository logic can be unit tested. */
interface DistributorGateway {
    /** Installed distributors (package names). */
    fun distributors(): List<String>

    /** Distributor saved by an earlier [saveDistributor], if still installed. */
    fun savedDistributor(): String?

    /** The system default distributor, if one can be resolved without user interaction. */
    fun defaultDistributor(): String?

    fun saveDistributor(packageName: String)

    /** Registers the default instance; the endpoint arrives asynchronously in `KumaPushService.onNewEndpoint`. */
    fun register(messageForDistributor: String?)

    fun unregister()

    /** Human readable app name of a distributor package. */
    fun label(packageName: String): String
}

internal class UnifiedPushDistributorGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DistributorGateway {
        override fun distributors(): List<String> = UnifiedPush.getDistributors(context)

        override fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

        override fun defaultDistributor(): String? =
            when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
                is ResolvedDistributor.Found -> resolved.packageName
                else -> null
            }

        override fun saveDistributor(packageName: String) = UnifiedPush.saveDistributor(context, packageName)

        override fun register(messageForDistributor: String?) =
            UnifiedPush.register(context, INSTANCE_DEFAULT, messageForDistributor, null)

        override fun unregister() = UnifiedPush.unregister(context, INSTANCE_DEFAULT)

        override fun label(packageName: String): String =
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
    }
