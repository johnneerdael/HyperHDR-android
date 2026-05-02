# Manual test: FlatBuffer wire format round-trip

**Prereqs:** A reachable HyperHDR v22+ instance (default flatbuffer port 19400) with at least one configured LED instance and an LED layout. A few visible LEDs is enough; the test sends a solid red frame.

**Steps:**

1. Start HyperHDR; confirm in its web UI that the flatbuffer server is listening (Configuration → Network Services → Flatbuffer server).
2. From the project root:

   ```bash
   ./gradlew :common:compileTestKotlin
   ```

3. Run this scratch script (paste into a new file `scratch/manual_send.kts`, then `kotlinc -script scratch/manual_send.kts` — or run it as a JUnit test pinned to your local box; do **not** commit the script with a hard-coded IP):

   ```kotlin
   import eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferClient
   import kotlinx.coroutines.runBlocking

   val host = System.getenv("HYPERHDR_HOST") ?: "192.168.1.100"
   val client = HyperHdrFlatBufferClient(host = host, port = 19400, priority = 100)
   runBlocking {
       client.connect()
       // Send a 4×2 red NV12 frame: Y = 76 (red), UV = (84, 255).
       val y = ByteArray(8) { 76.toByte() }
       val uv = ByteArray(4).apply {
           this[0] = 84.toByte(); this[1] = 255.toByte()
           this[2] = 84.toByte(); this[3] = 255.toByte()
       }
       client.sendNv12(y, uv, 4, 2, 4, 4)
       Thread.sleep(2_000)
       client.clear(100)
       client.close()
   }
   ```

4. While the script runs, look at the LEDs (or HyperHDR's "Live Preview"). They should turn solid red for ~2 seconds, then return to whatever priority is below 100.

**Pass:** LEDs go red, HyperHDR's logs (`/var/log/hyperhdr/...`) show `Received first NV12 frame. First plane size: 8 ... Image size: 12 (4 x 2)`.

**Fail modes:**
- LEDs don't change → check `priority` (lower number = higher priority; 100 is reasonable but a higher-priority source might be active).
- HyperHDR log says `The NV12 image data size (X) does not match the width and height (Y)` → strides or dimensions are wrong; the implementation packed UV at the wrong size.
- Connection refused → wrong port (default 19400) or HyperHDR's flatbuffer server isn't enabled.
- Connection drops every few seconds with no traffic → likely a firewall idle-timeout. Send any frame periodically (e.g., loop the `sendNv12` call) or set `socket.keepAlive = true` in the client.
