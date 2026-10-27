package com.pntapps.pocketserver.linux

import android.content.Context
import java.io.File

/**
 * Every byte PocketServer owns lives under the app's private storage, so uninstalling the app
 * removes all of it. Nothing is ever written to shared storage.
 */
class LinuxPaths(context: Context) {
    // Canonical: filesDir is /data/user/0/... (a symlink) but proot resolves the rootfs to
    // /data/data/...; link2symlink breaks when PROOT_L2S_DIR and the rootfs disagree on the prefix.
    private val files: File = context.filesDir.canonicalFile

    /** Directory Android extracted our jniLibs into; the only app location we may exec() from. */
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
    val proot = File(nativeLibDir, "libproot.so")
    val prootLoader = File(nativeLibDir, "libproot-loader.so")

    val rootfs = File(files, "ubuntu")
    val prootTmp = File(files, "proot-tmp")
    val fakeProc = File(files, "fake-proc")
    /** Exposed inside Ubuntu as /shared. Media folders in it may live on the SD card (see [storage]). */
    val shared = File(files, "shared")

    /** Optional SD card storage for /shared's media folders. */
    val storage = SharedStorage.get(context)
    val logs = File(files, "logs")
    val downloads = File(context.cacheDir.canonicalFile, "downloads")

    val installedMarker = File(rootfs, ".pocketserver-installed")

    val isInstalled: Boolean get() = installedMarker.exists()
}
