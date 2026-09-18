package dev.ubuntu4a.core.proot

import android.util.Log

/**
 * Thin JNI wrapper around a tiny openpty/forkpty helper
 * (libubuntu-pty.so, built by the :app module from src/main/cpp/pty.c).
 * Without it the app still works via plain pipes, but full-screen TUIs
 * (vim, htop, less) degrade gracefully.
 */
object PtyNative {
    private const val TAG = "PtyNative"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("ubuntu-pty"); true }
            .getOrElse {
                Log.i(TAG, "pty helper not bundled, falling back to pipes: ${it.message}")
                false
            }
    }

    external fun fork(cols: Int, rows: Int, argv: Array<String>): IntArray

    external fun resize(fd: Int, cols: Int, rows: Int)

    external fun reap(pid: Int): Int
}
