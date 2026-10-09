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
and the patches below (Android #1–#5, iOS #6–#8). The Dart API is unchanged.

After pushing a change here, run `flutter pub upgrade signalr_flutter` in the app
so `pubspec.lock` pins the new commit.

## Patches (search for `LEGOAS PATCH`)

| # | File | Problem | Fix |
|---|---|---|---|
| 1 | `android/src/main/java/microsoft/aspnet/signalr/client/Connection.java` | `Connection.reconnect()` reads `mHeartbeatMonitor` twice without a lock, while `disconnect()` on another thread sets it to `null`. That gives a fatal crash: `NullPointerException` in `Connection.reconnect` (Crashlytics, obfuscated as `microsoft.aspnet.signalr.client.j.o()` = `HeartbeatMonitor.stop()`). | `reconnect()` takes a local snapshot of the monitor and returns if it is `null` or the connection is no longer `Connected`. It runs under `mStartLock` to serialize with `startTransport`. |
| 2 | `SignalRFlutterPlugin.kt::connect` | Every `connect()` creates a new `HubConnection` and leaves the old one running (heartbeat, reconnect, duplicate hub events). | `releaseConnection()` detaches the old callbacks and stops the old connection. Hub messages from a replaced connection are dropped. |
| 3 | `SignalRFlutterPlugin.kt::invokeMethod` | `res.onError { throw throwable }` throws on the SignalR thread (a crash) and never completes the Future on the Dart side. | Completes with `result.error(...)` on the main thread. |
| 4 | `SignalRFlutterPlugin.kt::stop` | Never calls `result.success`, so the Dart `stop()` never completes. | Calls `result.success(null)` after `connection.stop()`. |
| 5 | `android/src/main/java/microsoft/aspnet/signalr/client/SignalRFuture.java` | `cancel()` iterates the `mOnCancelled` `ArrayList` on one thread (`Connection.onError` → `disconnect` → `UpdateableCancellableFuture.cancel`, on a `NetworkRunnable` thread) while another thread adds a callback through `onCancelled()`. That gives a fatal crash: `java.util.ConcurrentModificationException` in `SignalRFuture.cancel` (Crashlytics, LelangKu 2.0.1 – 2.2.0). | `mOnCancelled` is a `CopyOnWriteArrayList`: `add()` is thread-safe and `cancel()` iterates a snapshot. Behaviour is otherwise unchanged (a callback added after `cancel()` is still not run). |
| 6 | `ios/Classes/SwiftSignalrFlutterPlugin.swift::connect`, `ios/Classes/SwiftR.swift::dispose` | iOS counterpart of #2. Every `connect()` creates a new `SignalR` (its own `WKWebView`) and leaves the old one running: it stays in `SwiftR.connections` and in the key window, keeps its connection and hub handlers (duplicate hub events), and its status callbacks still reach Dart, so a stale `disconnected` overwrites the status of the current connection. LelangKu calls `connect()` again from the "Koneksi Anda saat ini tidak stabil" dialog, so every tap on "Ya" added one more (bidders stuck on "Koneksi Anda Terputus" until the app was killed). | `releaseConnection()` clears the old connection's callbacks and calls the new `SignalR.dispose()`: it clears hubs and the JS queue, removes the object from `SwiftR.connections`, runs `swiftR.connection.stop()`, then removes the script message handler (which retained the object) and the web view. `userContentController(_:didReceive:)` and `webView(_:didFinish:)` return early after `dispose()` (`wkWebView` is nil by then). |
| 7 | `ios/Classes/SwiftSignalrFlutterPlugin.swift::stop` | Never calls `completion` on success, so the Dart `stop()` never completes (same as #4 on Android). | Calls `completion(nil)` after `connection.stop()`. |
| 8 | `ios/Classes/SwiftR.swift::start` | `start()` (Dart `reconnect()`) before the web view has sent `ready` calls `connect()` again, which creates a second web view for the same object; the first one keeps loading and both report to it. | `start()` only calls `connect()` when there is no web view yet. While the page is loading it does nothing: the `ready` message starts the connection. |

### How patch #1 is packaged
`Connection.java` was taken out of `signalr-client-sdk.jar`, along with
`Connection.class` and `Connection$1..12.class`, and moved to
`android/src/main/java/...` so it compiles from source. The rest of the jar is unchanged.

- Original jar SHA-256: `62bfa0765548e5529d96d3e36ae11f1fd4971542231907a89719f4f178dcde05`
- Jar after removing `Connection*`: `aea49ea847442e2ee9df10672a0bdd5430e9e783446805e2af9fe45369ad942c`

### How patch #5 is packaged
Same as patch #1: `SignalRFuture.java` was taken out of `signalr-client-sdk.jar`
(the jar ships its sources), along with `SignalRFuture.class` (it has no inner
classes), and moved to `android/src/main/java/...`. Apart from the patch, the
source is identical to the one in the jar.

- Jar after also removing `SignalRFuture*`: `63e84f8d02f213dad2cf080249761eb9136887bfbde42713c14e43dc25258b46`

### iOS
Patches #6–#8 are plain source changes in `ios/Classes` (no binary). They compile with the LelangKu iOS build; **not yet tested on a physical iPhone** when they were written.
