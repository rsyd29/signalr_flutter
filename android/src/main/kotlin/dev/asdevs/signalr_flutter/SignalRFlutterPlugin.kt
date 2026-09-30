package dev.asdevs.signalr_flutter

import android.os.Handler
import android.os.Looper

import io.flutter.embedding.engine.plugins.FlutterPlugin
import microsoft.aspnet.signalr.client.ConnectionState
import microsoft.aspnet.signalr.client.Credentials
import microsoft.aspnet.signalr.client.LogLevel
import microsoft.aspnet.signalr.client.SignalRFuture
import microsoft.aspnet.signalr.client.hubs.HubConnection
import microsoft.aspnet.signalr.client.hubs.HubProxy
import microsoft.aspnet.signalr.client.transport.LongPollingTransport
import microsoft.aspnet.signalr.client.transport.ServerSentEventsTransport
import java.lang.Exception

/** SignalrFlutterPlugin */
class SignalrFlutterPlugin : FlutterPlugin, SignalrApi.SignalRHostApi {
    private lateinit var connection: HubConnection
    private lateinit var hub: HubProxy

    private lateinit var signalrApi: SignalrApi.SignalRPlatformApi

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        SignalrApi.SignalRHostApi.setup(flutterPluginBinding.binaryMessenger, this)
        signalrApi = SignalrApi.SignalRPlatformApi(flutterPluginBinding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        SignalrApi.SignalRHostApi.setup(binding.binaryMessenger, null)
    }

    override fun connect(
        connectionOptions: SignalrApi.ConnectionOptions,
        result: SignalrApi.Result<String>?
    ) {
        try {
            // LEGOAS PATCH: the app can call connect() more than once. Without
            // this, the old connection keeps running (heartbeat, reconnect,
            // duplicate hub events) with nothing referencing it.
            releaseConnection()

            connection =
                if (connectionOptions.queryString?.isNotEmpty() == true) {
                    HubConnection(
                        connectionOptions.baseUrl,
                        connectionOptions.queryString,
                        true
                    ) { _: String, _: LogLevel ->
                    }
                } else {
                    HubConnection(connectionOptions.baseUrl)
                }

            if (connectionOptions.headers?.isNotEmpty() == true) {
                val cred = Credentials { request ->
                    request.headers = connectionOptions.headers
                }
                connection.credentials = cred
            }

            hub = connection.createHubProxy(connectionOptions.hubName)
            val currentConnection = connection

            connectionOptions.hubMethods?.forEach { methodName ->
                hub.on(methodName, { res ->
                    Handler(Looper.getMainLooper()).post {
                        // Drop messages from a connection that has already been replaced.
                        if (connection === currentConnection) {
                            signalrApi.onNewMessage(methodName, res) { }
                        }
                    }
                }, String::class.java)
            }

            connection.connected {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.reconnected {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.reconnecting {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.RECONNECTING
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.closed {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.DISCONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.connectionSlow {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTION_SLOW
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.error { handler ->
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTION_ERROR
                    statusChangeResult.errorMessage = handler.localizedMessage
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            when (connectionOptions.transport) {
                SignalrApi.Transport.SERVER_SENT_EVENTS -> connection.start(
                    ServerSentEventsTransport(
                        connection.logger
                    )
                )
                SignalrApi.Transport.LONG_POLLING -> connection.start(
                    LongPollingTransport(
                        connection.logger
                    )
                )
                else -> {
                    connection.start()
                }
            }

            result?.success(connection.connectionId ?: "")
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun reconnect(result: SignalrApi.Result<String>?) {
        try {
            connection.start()
            result?.success(connection.connectionId ?: "")
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun stop(result: SignalrApi.Result<Void>?) {
        try {
            connection.stop()
            // LEGOAS PATCH: the original never completed on success.
            result?.success(null)
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    /** Detaches every callback from the old connection, then stops it. */
    private fun releaseConnection() {
        if (!this::connection.isInitialized) return
        val old = connection
        old.connected(null)
        old.reconnected(null)
        old.reconnecting(null)
        old.closed(null)
        old.connectionSlow(null)
        old.error(null)
        try {
            old.stop()
        } catch (ex: Exception) {
            // The old connection is being discarded; a stop failure doesn't matter.
        }
    }

    override fun isConnected(result: SignalrApi.Result<Boolean>?) {
        try {
            if (this::connection.isInitialized) {
                when (connection.state) {
                    ConnectionState.Connected -> result?.success(true)
                    else -> result?.success(false)
                }
            } else {
                result?.success(false)
            }
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun invokeMethod(
        methodName: String,
        arguments: MutableList<String>,
        result: SignalrApi.Result<String>?
    ) {
        try {
            val res: SignalRFuture<String> =
                hub.invoke(String::class.java, methodName, *arguments.toTypedArray())

            res.done { msg: String? ->
                Handler(Looper.getMainLooper()).post {
                    result?.success(msg ?: "")
                }
            }

            // LEGOAS PATCH: the original rethrew here, on the SignalR thread
            // (app crash), and never completed the Future on the Dart side.
            res.onError { throwable ->
                Handler(Looper.getMainLooper()).post {
                    result?.error(throwable)
                }
            }
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }
}
