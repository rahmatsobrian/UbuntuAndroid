package dev.ubuntu4a.core.data.catalog

import dev.ubuntu4a.core.data.model.DesktopEnv
import dev.ubuntu4a.core.data.model.ReleaseStatus
import dev.ubuntu4a.core.data.model.RootfsSource
import dev.ubuntu4a.core.data.model.UbuntuRelease

object UbuntuCatalog {
    val releases: List<UbuntuRelease> = listOf(
        UbuntuRelease("questing", "25.10", "Questing Quokka"),
        UbuntuRelease("plucky", "25.04", "Plucky Puffin"),
        UbuntuRelease("noble", "24.04", "Noble Numbat", lts = true),
        UbuntuRelease("jammy", "22.04", "Jammy Jellyfish", lts = true),
        UbuntuRelease("focal", "20.04", "Focal Fossa", lts = true),
        UbuntuRelease("bionic", "18.04", "Bionic Beaver", lts = true),
        UbuntuRelease("resolute", "26.04", "Resolute Raccoon", status = ReleaseStatus.EXPERIMENTAL),
        UbuntuRelease("devel", "next", "Development", status = ReleaseStatus.DEVEL),
    )

    /** https://github.com/wahasa/Ubuntu/releases/tag/Rootfs (build 18012026) */
    const val RELEASE_BASE = "https://github.com/wahasa/Ubuntu/releases/download/Rootfs"

    private val releaseSha256 = mapOf(
        "ubuntu-devel-arm64" to "abb44aeebdee6242476783349c762def2d5bf7cc73f5e280f257718a4d1990b0",
        "ubuntu-devel-armhf" to "578363ff3b2faa5f18a103ac9043a24479ad42a0b2e777ed94665700cf2e238c",
        "ubuntu-jammy-arm64" to "7f39be00da4840a92dac82c3c04e290920f1f69fd2b30e447c48c034bf6d0f27",
        "ubuntu-jammy-armhf" to "c044a763c2a15c55691b13ac8599ddb642019664f89dfcb6501b6447fcb0a74d",
        "ubuntu-noble-arm64" to "c9064f6265a1dfc9a96f2c54ee0c54b9a2c30c9cf5d3389d5dedf48832dc4290",
        "ubuntu-noble-armhf" to "b206d0aaed1bf29e6c630855cf97512edda783ceba63530d551007d4c0eb2d15",
        "ubuntu-plucky-arm64" to "45060e499a404e41dd3f6ddc07683d7e94257b7a76e3d493e4cf8efc3847bf95",
        "ubuntu-plucky-armhf" to "c902849fe5f661a7b36e1493e506ff6027e57d358c1deade9b9d5cc254077d0c",
        "ubuntu-questing-arm64" to "c6ad55964caee41ddee4711c218bfca197690f3506c7bcfe8db5b5e983f74c1f",
        "ubuntu-questing-armhf" to "a51ff522e4fd951781082e3a8e5ec14fd034462954219b2ffd31ed0ccb35e3ba",
        "ubuntu-resolute-arm64" to "20ecc8ca12473f1dcb21d105345e79df7ec47eca5a86edf736cdeaa68d487259",
        "ubuntu-resolute-armhf" to "68063c6aa20b08766ba8520a69560a89f3fab1a09353dcef642f311e66062017",
    )

    private fun assetKey(codeName: String, arch: String) = "ubuntu-$codeName-$arch"

    fun releaseAssetName(codeName: String, arch: String) = "${assetKey(codeName, arch)}-root.tar.xz"

    fun releaseAssetUrl(codeName: String, arch: String) = "$RELEASE_BASE/${releaseAssetName(codeName, arch)}"

    fun releaseSha256(codeName: String, arch: String): String? = releaseSha256[assetKey(codeName, arch)]

    fun hasReleaseAsset(codeName: String, arch: String): Boolean = releaseSha256(codeName, arch) != null

    /** Resolve (url, expectedSha256?) for the requested source, falling back to OCI when the release has no asset. */
    fun resolve(
        source: RootfsSource,
        mirror: String,
        codeName: String,
        arch: String,
    ): Pair<String, String?> {
        if (source == RootfsSource.RELEASE && hasReleaseAsset(codeName, arch)) {
            return releaseAssetUrl(codeName, arch) to releaseSha256(codeName, arch)
        }
        return rootfsUrl(mirror, codeName, arch) to null
    }

    fun rootfsUrl(mirror: String, codeName: String, arch: String): String =
        "$mirror/$codeName/current/ubuntu-$codeName-oci-$arch-root.tar.gz"

    fun deDescription(de: DesktopEnv): String = when (de) {
        DesktopEnv.NONE -> "Terminal only, no GUI desktop"
        DesktopEnv.XFCE -> "Lightweight and best tested on proot"
        DesktopEnv.LXQT -> "Very light Qt-based desktop"
        DesktopEnv.LXDE -> "Classic light desktop (GTK2)"
        DesktopEnv.KDE -> "Full-featured, heavier on ARM devices"
    }
}

data class LinuxApp(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val packages: List<String>,
    val launchCmd: String,
    val iconAsset: String? = null,
    val needsDesktop: Boolean = true,
)

object LinuxAppCatalog {
    val apps: List<LinuxApp> = listOf(
        LinuxApp("vim", "Vim", "Advanced text editor", "Editor", listOf("vim"), "vim", needsDesktop = false),
        LinuxApp("htop", "htop", "Interactive process viewer", "Utility", listOf("htop"), "htop", needsDesktop = false),
        LinuxApp("git", "Git", "Distributed version control", "Development", listOf("git"), "git", needsDesktop = false),
        LinuxApp("nano", "Nano", "Simple terminal editor", "Editor", listOf("nano"), "nano", needsDesktop = false),
        LinuxApp("chromium", "Chromium", "Open-source web browser", "Internet", listOf("chromium"), "chromium --no-sandbox --disable-gpu"),
        LinuxApp("firefox", "Firefox ESR", "Mozilla web browser", "Internet", listOf("firefox-esr"), "firefox-esr"),
        LinuxApp("thunderbird", "Thunderbird", "Email & news client", "Internet", listOf("thunderbird"), "thunderbird"),
        LinuxApp("gimp", "GIMP", "Image manipulation program", "Graphics", listOf("gimp"), "gimp"),
        LinuxApp("inkscape", "Inkscape", "Vector graphics editor", "Graphics", listOf("inkscape"), "inkscape"),
        LinuxApp("krita", "Krita", "Digital painting studio", "Graphics", listOf("krita"), "krita"),
        LinuxApp("blender", "Blender", "3D creation suite", "Graphics", listOf("blender"), "blender --gl-backend=egl"),
        LinuxApp("libreoffice", "LibreOffice", "Office productivity suite", "Office", listOf("libreoffice"), "libreoffice"),
        LinuxApp("vscode", "VS Code", "Code editing, redefined", "Development", listOf("code"), "code --no-sandbox --disable-gpu"),
        LinuxApp("obs", "OBS Studio", "Recording & streaming", "Video", listOf("obs-studio"), "obs"),
        LinuxApp("vlc", "VLC", "Multimedia player", "Audio/Video", listOf("vlc"), "vlc"),
        LinuxApp("gedit", "Text Editor", "GNOME text editor", "Editor", listOf("gedit"), "gedit"),
        LinuxApp("nautilus", "Files", "GNOME file manager", "Utility", listOf("nautilus"), "nautilus"),
        LinuxApp("gdebi", "GDebi", "Deb package installer", "Utility", listOf("gdebi"), "gdebi-gtk"),
    )
}
