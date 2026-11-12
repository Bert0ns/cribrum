package io.github.bert0ns.cribrum.testing

import android.graphics.Bitmap
import android.os.Build
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.config.ConfigurationRegistry
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader
import java.nio.IntBuffer

/**
 * Robolectric's native runtime loader, plus one native graphics call on the thread that loads it (#377).
 *
 * Android's `JniConstants` (the `java.nio` buffer classes and others) is built by a `pthread_once` in the native
 * runtime, on the first call that needs it, and it looks the classes up with a `JNIEnv` that is not the calling
 * thread's. When that first call comes from a worker thread, as in a scan that decodes on `Dispatchers.Default`,
 * `FindClass` fails now and then and the JVM aborts with `Class not found: java/nio/FloatBuffer`. A test that makes an
 * earlier call on the test thread, as in a whole class or the full suite, never meets it. A run filtered to one test
 * does. The loader runs at the start of the first test, on the test thread, so the constants are built there and the
 * workers only find them ready. Registered in `META-INF/services`, where it wins over Robolectric's own.
 */
class WarmedNativeRuntimeLoader : DefaultNativeRuntimeLoader() {
    override fun ensureLoaded() {
        super.ensureLoaded()
        // The runtime also loads for SQLite alone, in legacy graphics and on SDK 34 and below, where it has no native
        // Bitmap and the call below would break the sandbox's ColorSpace.
        if (ConfigurationRegistry.get(GraphicsMode.Mode::class.java) != GraphicsMode.Mode.NATIVE) return
        if (RuntimeEnvironment.getApiLevel() < Build.VERSION_CODES.BAKLAVA) return
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(IntBuffer.allocate(1))
    }
}
