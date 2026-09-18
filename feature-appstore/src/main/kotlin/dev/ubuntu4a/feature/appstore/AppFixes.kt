package dev.ubuntu4a.feature.appstore

import dev.ubuntu4a.core.data.model.DistroInstance
import dev.ubuntu4a.core.proot.CommandRunner
import dev.ubuntu4a.core.proot.setup.RootfsProvisioner
import java.io.File

/**
 * Port of the Apps/ *.sh scripts (firefox.sh, chromium.sh, vscodefix.sh, libreofficefix.sh)
 * from github.com/wahasa/Ubuntu. Each fix writes files into the rootfs
 * directly and/or runs apt through proot — no .sh is shipped.
 */
object AppFixes {

    suspend fun apply(
        context: android.content.Context,
        instance: DistroInstance,
        appId: String,
        runner: CommandRunner,
    ) {
        val rootfs = File(context.filesDir, "containers/${instance.id}/rootfs")
        when (appId) {
            "firefox", "thunderbird" -> {
                RootfsProvisioner.write(rootfs, "etc/apt/sources.list.d/mozillateam-ppa.sources", mozillateamSources(instance.codeName))
                runner.exec(instance, "apt-get update ; apt-get install -y $appId")
            }
            "chromium" -> {
                RootfsProvisioner.write(rootfs, "etc/apt/sources.list.d/debian-stable.sources", debianSources())
                runner.exec(
                    instance,
                    "apt-get update ; apt-get install -y chromium ; " +
                        "sed -i 's/chromium %U/chromium --no-sandbox --disable-gpu --test-type %U/g' /usr/share/applications/chromium.desktop",
                )
            }
            "vscode" -> {
                runner.exec(
                    instance,
                    "cd /root && wget -q -O code.deb 'https://update.code.visualstudio.com/latest/linux-deb-${codeArch(instance.arch)}/stable' " +
                        "&& apt-get install -y ./code.deb && rm code.deb",
                    timeoutSec = 900,
                )
                RootfsProvisioner.write(rootfs, "usr/share/applications/code.desktop", codeDesktop())
            }
            "libreoffice" -> {
                runner.exec(
                    instance,
                    "wget -q -O /tmp/oosplash 'https://raw.githubusercontent.com/wahasa/Ubuntu/main/Patch/oosplash' " +
                        "&& rm -f /usr/lib/libreoffice/program/oosplash " +
                        "&& mv /tmp/oosplash /usr/lib/libreoffice/program/oosplash " +
                        "&& chmod +x /usr/lib/libreoffice/program/oosplash && mkdir -p /prod/version",
                    timeoutSec = 300,
                )
            }
            else -> Unit
        }
    }

    private fun codeArch(arch: String) = when (arch) {
        "arm64" -> "arm64"
        "armhf" -> "armhf"
        else -> "arm64"
    }

    private fun debianSources() = """
        |#Managed-by-Ubuntu4A — Debian stable repo for chromium (same trick as the reference Apps/chromium)
        |Types: deb
        |URIs: http://ftp.debian.org/debian
        |Suites: stable
        |Components: main
        |Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
        |""".trimMargin()

    private fun mozillateamSources(codeName: String) = """
        |Types: deb
        |URIs: https://ppa.launchpadcontent.net/mozillateam/ppa/ubuntu/
        |Suites: $codeName
        |Components: main
        |Signed-By:
        | -----BEGIN PGP PUBLIC KEY BLOCK-----
        | .
        | mQINBGYov84BEADSrLhiWvqL3JJ3fTxjCGD4+viIUBS4eLSc7+Q7SyHm/wWfYNwT
        | EqEvMMM9brWQyC7xyE2JBlVk5/yYHkAQz3f8rbkv6ge3J8Z7G4ZwHziI45xJKJ0M
        | 9SgJH24WlGxmbbFfK4SGFNlg9x1Z0m5liU3dUSfhvTQdmBNqwRCAjJLZSiS03IA0
        | 56V9r3ACejwpNiXzOnTsALZC2viszGiI854kqhUhFIJ/cnWKSbAcg6cy3ZAsne6K
        | vxJVPsdEl12gxU6zENZ/4a4DV1HkxIHtpbh1qub1lhpGR41ZBXv+SQhwuMLFSNeu
        | UjAAClC/g1pJ0gzI0ko1vcQFv+Q486jYY/kv+k4szzcB++nLILmYmgzOH0NEqT57
        | XtdiBWhlb6oNfF/nYZAaToBU/QjtWXq3YImG2NiCUrCj9zAKHdGUsBU0FxN7HkVB
        | B8aF0VYwB0I2LRO4Af6Ry1cqMyCQnw3FVh0xw7Vz4gQ57acUYeAJpT68q8E2XcUx
        | riEP65/MBPoFlANLVMSrnsePEXmVzdysmXKnFVefeQ4E3dIDufXUIhrfmL1pMdTG
        | anhmDEjY7I3pQQQIaLpnNhhSDZKDSk9C/Ax/8gEUgnnmd6BwZxh8Q7oDXcm2tyeu
        | n2m9wCZI/eJI9P9G8ON8AkKvG4xFR+eqhowwzu7TLDr3feliG+UN+mJ8jwARAQAB
        | tB5MYXVuY2hwYWQgUFBBIGZvciBNb3ppbGxhIFRlYW2JAk4EEwEKADgWIQRzi+uT
        | IdGq7BPqk5GuvfSBm+IYZwUCZii/zgIbAwULCQgHAgYVCgkICwIEFgIDAQIeAQIX
        | gAAKCRCuvfSBm+IYZ38/D/46eEIyG7Gb65sxt3QnlIN0+90kUjz83QpCnIyALZDc
        | H2wPYBCMbyJFMG+rqVE8Yoh6WF0Rqy76LG+Y/xzO9eKIJGxVcSU75ifoq/M7pI1p
        | aiqA9T8QcFBmo83FFoPvnid67aqg/tFsHl+YF9rUxMZndGRE9Hk96lkH1Y2wHMEs
        | mAa582RELVEDDD2ellOPmQr69fRPa5IdJHkXjqGtoNQy5hAp49ofMLmeQ82d2OA+
        | kpzgiuSw8Nh1VrMZludcUArSQDCHoXuiPG/7Wn9Vy6fvKkTQK3mCW8i5HgCa0qxe
        | vOKlDMz4virEEADMBs79iIyM6w1xm8JOD4734sgii2MPcQgmAlbu5LyBM5FfuO0u
        | rTMvZM0btSWQX3nIsxQ3far9MJvUT4nebhTo59cED+1EjkD14mReTHwtWt1aye/b
        | I8Rvor15RFiB8Ku6c41YmNKarSCzJDs4VEfsos4oMieEqA98J4ZOX67IT++ortcB
        | uXmDJgvzGWEeyVOMoc/4oDJHNQjJg9XRGy8b/J3AVhk2BE/CD4lKhX3hWGbufrQz
        | E8ENWuT4m3igQnBmOsrGlBPYIOKZvczQxri01vcKY95dKXb1jtnR9yR+JKgEP388
        | 1B/8dEohynhMnzEqR9TIMEEy9Y8RKZ+Jiy+/Lg2XGrChiLsouUetfMQww6BTK+++
        | pw==
        | =tIux
        | -----END PGP PUBLIC KEY BLOCK-----
        |""".trimMargin()

    private fun codeDesktop() = """
        |[Desktop Entry]
        |Name=Visual Studio Code
        |Comment=Code Editing. Redefined.
        |GenericName=Text Editor
        |Exec=/usr/share/code/code --no-sandbox --unity-launch %F
        |Icon=vscode
        |Type=Application
        |StartupNotify=false
        |StartupWMClass=Code
        |Categories=TextEditor;Development;IDE;
        |MimeType=text/plain;inode/directory;application/x-code-workspace;
        |Actions=new-empty-window;
        |Keywords=vscode;
        |Path=
        |Terminal=false
        |
        |[Desktop Action new-empty-window]
        |Name=New Empty Window
        |Exec=/usr/share/code/code --no-sandbox --new-window %F
        |Icon=vscode
        |""".trimMargin()
}
