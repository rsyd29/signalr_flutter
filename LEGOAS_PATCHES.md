# signalr_flutter (Legoas fork)

Fork of https://github.com/AS-Devs/signalr_flutter, maintained at
https://github.com/rsyd29/signalr_flutter (`master`). Upstream is no longer
maintained. The Android side bundles Microsoft's old SignalR Java client
(`android/libraries/signalr-client-sdk*.jar`, 2014).

Used by LelangKu (`app_legoas_lelangku`) as a git dependency:

```yaml
signalr_flutter:
  git:
    url: https://github.com/rsyd29/signalr_flutter.git
    ref: master
```

`lib/`, `ios/`, `pigeons/`, and the Kotlin plugin before these patches are
identical to upstream `8bd721a` (the commit LelangKu used before). This fork
only differs in Android build config (Gradle 8.10.2, Kotlin 2.0.20, compileSdk 34)
and the patches below. The Dart API is unchanged.

After pushing a change here, run `flutter pub upgrade signalr_flutter` in the app
so `pubspec.lock` pins the new commit.

## Patches (search for `LEGOAS PATCH`)

| # | File | Problem | Fix |
|---|---|---|---|
| 1 | `android/src/main/java/microsoft/aspnet/signalr/client/Connection.java` | `Connection.reconnect()` reads `mHeartbeatMonitor` twice without a lock, while `disconnect()` on another thread sets it to `null`. That gives a fatal crash: `NullPointerException` in `Connection.reconnect` (Crashlytics, obfuscated as `microsoft.aspnet.signalr.client.j.o()` = `HeartbeatMonitor.stop()`). | `reconnect()` takes a local snapshot of the monitor and returns if it is `null` or the connection is no longer `Connected`. It runs under `mStartLock` to serialize with `startTransport`. |
| 2 | `SignalRFlutterPlugin.kt::connect` | Every `connect()` creates a new `HubConnection` and leaves the old one running (heartbeat, reconnect, duplicate hub events). | `releaseConnection()` detaches the old callbacks and stops the old connection. Hub messages from a replaced connection are dropped. |
| 3 | `SignalRFlutterPlugin.kt::invokeMethod` | `res.onError { throw throwable }` throws on the SignalR thread (a crash) and never completes the Future on the Dart side. | Completes with `result.error(...)` on the main thread. |
| 4 | `SignalRFlutterPlugin.kt::stop` | Never calls `result.success`, so the Dart `stop()` never completes. | Calls `result.success(null)` after `connection.stop()`. |

### How patch #1 is packaged
`Connection.java` was taken out of `signalr-client-sdk.jar`, along with
`Connection.class` and `Connection$1..12.class`, and moved to
`android/src/main/java/...` so it compiles from source. The rest of the jar is unchanged.

- Original jar SHA-256: `62bfa0765548e5529d96d3e36ae11f1fd4971542231907a89719f4f178dcde05`
- Jar after removing `Connection*`: `aea49ea847442e2ee9df10672a0bdd5430e9e783446805e2af9fe45369ad942c`

iOS (`ios/`) is unchanged.
