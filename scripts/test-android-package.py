#!/usr/bin/env python3
"""Sign and install the release on an owned Nevumi emulator with its lab locks held."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
APP = "me.d3sox.nevumi"
SDK = Path(os.environ.get("ANDROID_HOME", "/home/nico/Android/Sdk"))
JAVA = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk")) / "bin"


class AndroidPackageTest(unittest.TestCase):
    def test_signed_release_installs_and_includes_the_offline_license(self):
        serial = os.environ["NEVUMI_TEST_SERIAL"]
        self.assertRegex(serial, r"^emulator-[0-9]{4}$")
        allowed_ports = range(5560, 5684, 2)
        if os.environ.get("GITHUB_ACTIONS") == "true": allowed_ports = [5554, *allowed_ports]
        self.assertIn(int(serial.split("-")[1]), allowed_ports)
        def adb(*arguments):
            result = subprocess.run([str(SDK / "platform-tools/adb"), "-s", serial, *arguments],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            return result.stdout
        self.assertIn("nevumi_", adb("emu", "avd", "name"))
        self.assertEqual("0", adb("shell", "am", "get-current-user").strip())
        self.assertTrue(APK.is_file(), "Run :androidApp:assembleRelease first")
        with tempfile.TemporaryDirectory(prefix="nevumi-android-package-") as temporary:
            directory = Path(temporary)
            keys = directory / "test.jks"
            subprocess.run([str(JAVA / "keytool"), "-genkeypair", "-noprompt", "-keystore", str(keys),
                            "-alias", "nevumi-test", "-storepass", "test-password", "-keypass", "test-password",
                            "-keyalg", "RSA", "-keysize", "3072", "-validity", "1",
                            "-dname", "CN=Nevumi installation test"], check=True, capture_output=True, timeout=30)
            signed = directory / "nevumi.apk"
            result = subprocess.run(
                ["python", str(ROOT / "scripts/sign-android.py"), "--input", str(APK), "--output", str(signed),
                 "--keystore", str(keys), "--alias", "nevumi-test"],
                env=dict(os.environ, NEVUMI_ANDROID_KEYSTORE_PASSWORD="test-password"),
                capture_output=True, text=True, timeout=45,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            # This emulator contains only this thread's synthetic test app and data.
            adb("shell", "am", "force-stop", APP)
            if "package:" + APP in adb("shell", "pm", "list", "packages", APP).splitlines():
                adb("uninstall", APP)
            adb("install", "--user", "0", str(signed))
            try:
                package = adb("shell", "dumpsys", "package", APP)
                version = re.search(r"(?m)^appVersion=(.+)$", (ROOT / "gradle.properties").read_text())[1]
                self.assertIn("versionName=" + version, package)
                self.assertRegex(package, r"(?m)^\s*flags=\[ (?![^\n]*DEBUGGABLE)")
                adb("shell", "am", "start", "-W", "--user", "0", "-n", APP + "/.MainActivity")
                def tree():
                    adb("shell", "uiautomator", "dump", "/data/local/tmp/nevumi-package-ui.xml")
                    return ET.fromstring(adb("shell", "cat", "/data/local/tmp/nevumi-package-ui.xml"))
                def click(label, last=False):
                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        root = tree()
                        # Answer permission prompts, and "isn't responding" dialogs that slow emulators show for system apps.
                        # Nevumi's own dialog is a failure, not something to wait out.
                        title = next((n.get("text", "") for n in root.iter("node") if n.get("resource-id") == "android:id/alertTitle"), "")
                        if "Nevumi" in title:
                            self.fail("Nevumi stopped responding: " + title)
                        dialog = next((n for n in root.iter("node") if n.get("resource-id", "").endswith(":id/permission_deny_button")
                                       or n.get("resource-id") == "android:id/aerr_wait"), None)
                        if dialog is not None:
                            x1, y1, x2, y2 = map(int, re.findall(r"\d+", dialog.get("bounds")))
                            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                            continue
                        # A slow emulator can leave the launcher in front after the launch, so bring the app back.
                        if not any(n.get("package") == APP for n in root.iter("node")):
                            adb("shell", "am", "start", "--user", "0", "-n", APP + "/.MainActivity")
                            time.sleep(1)
                            continue
                        nodes = [node for node in root.iter("node") if label in [node.get("text"), node.get("content-desc")]]
                        if nodes:
                            node = nodes[-1] if last else nodes[0]
                            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                            return
                        if label in ["About Nevumi", "Paired devices"]:
                            size = adb("shell", "wm", "size").strip().split(":")[-1].strip()
                            width, height = map(int, size.split("x"))
                            adb("shell", "input", "swipe", str(width//2), str(height*3//4),
                                str(width//2), str(height//3), "300")
                        time.sleep(0.2)
                    crashes = adb("logcat", "-d", "-b", "crash")
                    self.fail("Could not select " + label + ": " + ET.tostring(root, encoding="unicode") + "\nCrash log:\n" + crashes)
                click("Settings")
                click("About Nevumi")
                click("View license")
                self.assertIn("GNU AFFERO GENERAL PUBLIC LICENSE", ET.tostring(tree(), encoding="unicode"))
                # Back presses can land before the previous page settles, so restart on the library instead.
                adb("shell", "am", "force-stop", APP)
                adb("shell", "am", "start", "-W", "--user", "0", "-n", APP + "/.MainActivity")
                click("Settings")
                click("Paired devices")
                click("Create invitation")
                deadline = time.monotonic() + 15
                qr_visible = False
                while time.monotonic() < deadline:
                    pairing_tree = tree()
                    pairing_xml = ET.tostring(pairing_tree, encoding="unicode")
                    qr_visible = qr_visible or "Pairing QR code" in pairing_xml
                    if "nevumi://pair/" in pairing_xml and qr_visible:
                        break
                    size = adb("shell", "wm", "size").strip().split(":")[-1].strip()
                    width, height = map(int, size.split("x"))
                    adb("shell", "input", "swipe", str(width//2), str(height*3//4), str(width//2), str(height//3), "300")
                    time.sleep(0.2)
                else:
                    errors = [node.get("text", "") for node in pairing_tree.iter("node")
                              if any(word in node.get("text", "").lower() for word in ["failed", "error", "algorithm", "signer", "invalid", "denied", "could not"])]
                    self.fail("The optimized release could not create a pairing invitation: " + "; ".join(errors))
                # Exercise the JNI renderer and resource loading after R8, using only synthetic music.
                fixture = directory / "native.flac"
                subprocess.run(["ffmpeg", "-v", "error", "-stream_loop", "19", "-i",
                                str(ROOT / "androidApp/src/androidTest/assets/fixture.wav"), "-t", "60",
                                "-metadata", "title=Native package fixture", "-metadata", "artist=Nevumi",
                                "-c:a", "flac", str(fixture)], check=True, timeout=30)
                remote = "/sdcard/Music/NevumiPackageChecks/native.flac"
                adb("shell", "mkdir", "-p", "/sdcard/Music/NevumiPackageChecks")
                adb("push", str(fixture), remote)
                # Current Android ignores the old scan-file broadcast, so index the volume and wait for the file.
                adb("shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary")
                deadline = time.monotonic() + 60
                while "native.flac" not in adb("shell", "content", "query", "--uri", "content://media/external/audio/media",
                                               "--projection", "_display_name"):
                    if time.monotonic() > deadline:
                        self.fail("Android did not index the package fixture")
                    time.sleep(1)
                api = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
                permission = "android.permission.READ_MEDIA_AUDIO" if api >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
                adb("shell", "pm", "grant", APP, permission)
                if api >= 33: adb("shell", "pm", "grant", APP, "android.permission.POST_NOTIFICATIONS")
                adb("shell", "am", "force-stop", APP)
                adb("shell", "am", "start", "-W", "--user", "0", "-n", APP + "/.MainActivity")
                click("Settings"); click("Local music"); click("Scan music")
                deadline = time.monotonic() + 30
                while "Scan complete" not in ET.tostring(tree(), encoding="unicode"):
                    if time.monotonic() > deadline:
                        self.fail("Nevumi did not finish scanning the device's music")
                    time.sleep(0.5)
                for _ in range(2): adb("shell", "input", "keyevent", "4")
                click("Songs"); click("Native package fixture"); click("Native package fixture", last=True)
                click("MilkDrop visualizer")
                deadline = time.monotonic() + 15
                screenshot = directory / "milkdrop.png"
                while time.monotonic() < deadline:
                    adb("shell", "screencap", "-p", "/data/local/tmp/nevumi-package-milkdrop.png")
                    adb("pull", "/data/local/tmp/nevumi-package-milkdrop.png", str(screenshot))
                    with Image.open(screenshot) as pixels:
                        region = pixels.convert("RGB").crop((pixels.width // 8, pixels.height // 4,
                                                             pixels.width * 7 // 8, pixels.height * 2 // 3))
                    colored = sum(count for count, (red, green, blue) in region.getcolors(region.width * region.height)
                                  if max(red, green, blue) - min(red, green, blue) > 20)
                    if colored > 50: break
                    time.sleep(.2)
                else: self.fail("The optimized APK did not render MilkDrop audio")
                output = ROOT / ".test-environment/milkdrop-ui/optimized-android.png"
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(screenshot, output)
                click("Close visualizer")
            finally:
                adb("shell", "rm", "-f", "/data/local/tmp/nevumi-package-ui.xml")
                adb("shell", "rm", "-f", "/data/local/tmp/nevumi-package-milkdrop.png", "/sdcard/Music/NevumiPackageChecks/native.flac")
                adb("shell", "am", "force-stop", APP)
                adb("uninstall", APP)


if __name__ == "__main__":
    unittest.main()
