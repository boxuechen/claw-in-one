package ai.openclaw.app.androiddevice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AndroidNsdAdbTlsEndpointDiscovery(
  context: Context,
) : AndroidDeviceEndpointDiscovery {
  private val nsdManager = context.getSystemService(NsdManager::class.java)
  private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

  override suspend fun discoverLocalAdbTlsEndpoint(): String? =
    withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
      discoverLocalEndpoint()
    }

  @Suppress("DEPRECATION")
  private suspend fun discoverLocalEndpoint(): String? =
    suspendCancellableCoroutine { continuation ->
      val completed = AtomicBoolean(false)
      val resolving = AtomicBoolean(false)
      val listenerRef = AtomicReference<NsdManager.DiscoveryListener?>()
      val multicastLock =
        wifiManager
          ?.createMulticastLock(MULTICAST_LOCK_TAG)
          ?.apply { setReferenceCounted(false) }

      fun releaseResources() {
        listenerRef.get()?.let { listener ->
          runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        if (multicastLock?.isHeld == true) multicastLock.release()
      }

      fun finish(endpoint: String?) {
        if (!completed.compareAndSet(false, true)) return
        releaseResources()
        continuation.resumeWith(Result.success(endpoint))
      }

      val discoveryListener =
        object : NsdManager.DiscoveryListener {
          override fun onDiscoveryStarted(serviceType: String) = Unit

          override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.equals(ADB_TLS_CONNECT_SERVICE_TYPE, ignoreCase = true)) return
            if (!resolving.compareAndSet(false, true)) return
            nsdManager.resolveService(
              serviceInfo,
              object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                  serviceInfo: NsdServiceInfo,
                  errorCode: Int,
                ) {
                  resolving.set(false)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                  resolving.set(false)
                  localEndpoint(serviceInfo)?.let(::finish)
                }
              },
            )
          }

          override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

          override fun onDiscoveryStopped(serviceType: String) {
            finish(null)
          }

          override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
          ) {
            finish(null)
          }

          override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
          ) {
            finish(null)
          }
        }
      listenerRef.set(discoveryListener)
      continuation.invokeOnCancellation {
        if (completed.compareAndSet(false, true)) releaseResources()
      }
      runCatching {
        multicastLock?.acquire()
        nsdManager.discoverServices(
          ADB_TLS_CONNECT_SERVICE_TYPE,
          NsdManager.PROTOCOL_DNS_SD,
          discoveryListener,
        )
      }.onFailure { finish(null) }
    }

  private fun localEndpoint(serviceInfo: NsdServiceInfo): String? {
    if (serviceInfo.port !in 1..65535) return null
    val localAddresses = localNetworkAddresses()
    val resolvedAddresses =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        serviceInfo.hostAddresses
      } else {
        @Suppress("DEPRECATION")
        listOfNotNull(serviceInfo.host)
      }
    val address =
      resolvedAddresses
        .asSequence()
        .filterNot(InetAddress::isLoopbackAddress)
        .filter { candidate -> localAddresses.any(candidate.address::contentEquals) }
        .sortedBy { candidate -> candidate !is Inet4Address }
        .firstOrNull()
        ?: return null
    val host = address.hostAddress ?: return null
    return if (address is Inet6Address) "[$host]:${serviceInfo.port}" else "$host:${serviceInfo.port}"
  }

  private fun localNetworkAddresses(): List<ByteArray> =
    runCatching {
      Collections
        .list(NetworkInterface.getNetworkInterfaces())
        .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses) }
        .filterNot(InetAddress::isLoopbackAddress)
        .map(InetAddress::getAddress)
    }.getOrDefault(emptyList())

  private companion object {
    const val ADB_TLS_CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    const val DISCOVERY_TIMEOUT_MS = 5_000L
    const val MULTICAST_LOCK_TAG = "ClawInOne:AdbTlsDiscovery"
  }
}
