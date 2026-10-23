#!/usr/bin/env python3
"""Exercise the packaged desktop app on an owned display and private session bus."""
import os
import ctypes
from pathlib import Path
import re
import selectors
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent.parent
APP = Path(os.environ.get("NEVUMI_TEST_APP", str(ROOT / "desktopApp/build/compose/binaries/main/app/nevumi/bin/nevumi")))
JAVA = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk")) / "bin"


def first_line(process, timeout=10):
    with selectors.DefaultSelector() as ready:
        ready.register(process.stdout, selectors.EVENT_READ)
        if not ready.select(timeout):
            raise AssertionError("Test session did not become ready")
        line = process.stdout.readline().strip()
        if not line:
            raise AssertionError("Test session exited before becoming ready")
        return line


def stop(process):
    if process is None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def close_window(window_id, display_name):
    class ClientMessage(ctypes.Structure):
        _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                    ("display", ctypes.c_void_p), ("window", ctypes.c_ulong), ("message_type", ctypes.c_ulong),
                    ("format", ctypes.c_int), ("data", ctypes.c_long * 5)]

    class Event(ctypes.Union):
        _fields_ = [("message", ClientMessage), ("padding", ctypes.c_long * 24)]

    x11 = ctypes.CDLL("libX11.so.6")
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    x11.XInternAtom.restype = ctypes.c_ulong
    x11.XSendEvent.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_long, ctypes.POINTER(Event)]
    x11.XFlush.argtypes = [ctypes.c_void_p]
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    display = x11.XOpenDisplay(display_name.encode())
    if not display:
        raise AssertionError("Could not connect to the test display")
    try:
        event = Event()
        event.message = ClientMessage(33, 0, 1, display, window_id,
                                      x11.XInternAtom(display, b"WM_PROTOCOLS", 0), 32,
                                      (ctypes.c_long * 5)(x11.XInternAtom(display, b"WM_DELETE_WINDOW", 0), 0, 0, 0, 0))
        if not x11.XSendEvent(display, window_id, 0, 0, ctypes.byref(event)):
            raise AssertionError("Could not request a normal window close")
        x11.XFlush(display)
    finally:
        x11.XCloseDisplay(display)


class LinuxPlayerTest(unittest.TestCase):
    def test_window_opens_and_closes_while_desktop_services_are_still_starting(self):
        self.assertTrue(APP.is_file(), "Run :desktopApp:createDistributable first")
        with tempfile.TemporaryDirectory(prefix="nevumi-startup-") as temporary:
            directory = Path(temporary)
            display = app = None
            with socket.socket(socket.AF_UNIX) as stalled_bus, (directory / "session.log").open("w+") as log:
                stalled_bus.bind(str(directory / "system-bus"))
                stalled_bus.listen(1)
                try:
                    display = subprocess.Popen(
                        ["Xvfb", "-displayfd", "1", "-screen", "0", "1280x900x24", "-nolisten", "tcp", "-noreset"],
                        stdout=subprocess.PIPE, stderr=log, text=True,
                    )
                    display_name = ":" + first_line(display, timeout=30)
                    environment = dict(os.environ, DISPLAY=display_name, SKIKO_RENDER_API="SOFTWARE",
                                       DBUS_SYSTEM_BUS_ADDRESS="unix:path=" + str(directory / "system-bus"),
                                       DBUS_SESSION_BUS_ADDRESS="unix:path=" + str(directory / "session-bus"),
                                       XDG_CONFIG_HOME=str(directory / "config"), XDG_CACHE_HOME=str(directory / "cache"))
                    environment.pop("WAYLAND_DISPLAY", None)
                    app = subprocess.Popen([str(APP), "--data-dir=" + str(directory / "music"), "--audio-output=null"],
                                           env=environment, stdout=log, stderr=log)
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        window = subprocess.run(["xwininfo", "-name", "Nevumi"], env=environment,
                                                capture_output=True, text=True, timeout=3)
                        if "Map State: IsViewable" in window.stdout:
                            break
                        if app.poll() is not None:
                            log.seek(0)
                            self.fail("Desktop player exited: " + log.read())
                        with selectors.DefaultSelector() as delay:
                            delay.select(0.1)
                    else:
                        log.seek(0)
                        self.fail("The window waited for desktop services: " + log.read())
                    # A connected service that never answers must not hold the first window hostage.
                    stalled_bus.settimeout(3)
                    connection, _ = stalled_bus.accept()
                    with connection:
                        window_id = int(re.search(r"Window id: (0x[0-9a-f]+)", window.stdout).group(1), 16)
                        close_window(window_id, display_name)
                        try:
                            app.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            log.seek(0)
                            self.fail("Closing the window waited for desktop services: " + log.read())
                finally:
                    stop(app)
                    stop(display)
                    if display is not None:
                        display.stdout.close()

    def test_packaged_visualizer_loads_its_native_libraries_and_renders_audio(self):
        self.assertTrue(APP.is_file(), "Run :desktopApp:createDistributable first")
        with tempfile.TemporaryDirectory(prefix="nevumi-packaged-visualizer-") as temporary:
            directory = Path(temporary)
            image = directory / "nevumi"
            shutil.copytree(APP.parent.parent, image)
            source = directory / "VisualizerProbe.java"
            source.write_text('''
import me.d3sox.nevumi.visualizer.ProjectMNative;
import java.nio.file.Files;
import java.nio.file.Path;
public class VisualizerProbe {
    public static void main(String[] args) throws Exception {
        var preset = Files.createTempFile("nevumi-visualizer-", ".milk");
        Files.writeString(preset, "[preset00]\\nfDecay=0.9\\nnWaveMode=1\\nfWaveAlpha=1\\nwave_r=1\\nwave_g=0.5\\nwave_b=0.2\\n");
        var nativeApi = ProjectMNative.INSTANCE;
        nativeApi.initialize();
        long handle = nativeApi.create(320, 240, true, preset.getParent().toString());
        try {
            nativeApi.load(handle, preset.toString(), false);
            float[] pcm = new float[2048];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (float)Math.sin((i / 2) * 0.1) * 0.7f;
            for (int frame = 0; frame < 12; frame++) {
                nativeApi.render(handle, pcm, 320, 240, true);
                Thread.sleep(33);
            }
            byte[] pixels = nativeApi.pixels(handle);
            int colored = 0;
            for (int i = 0; i < pixels.length; i += 4)
                if ((pixels[i] & 255) + (pixels[i + 1] & 255) + (pixels[i + 2] & 255) > 30) colored++;
            if (colored < 50) throw new AssertionError("The packaged visualizer rendered no audio");
            System.out.println("Packaged MilkDrop ready");
        } finally { nativeApi.destroy(handle); Files.delete(preset); }
    }
}
''')
            classpath = os.pathsep.join(map(str, (image / "lib/app").glob("*.jar")))
            subprocess.run([str(JAVA / "javac"), "-cp", classpath, str(source)], check=True, capture_output=True)
            probe = image / "lib/app/visualizer-probe.jar"
            subprocess.run([str(JAVA / "jar"), "--create", "--file", str(probe),
                            "-C", str(directory), "VisualizerProbe.class"], check=True, capture_output=True)
            configuration = image / "lib/app/nevumi.cfg"
            text = configuration.read_text().replace("app.mainclass=me.d3sox.nevumi.desktop.MainKt", "app.mainclass=VisualizerProbe")
            configuration.write_text(text.replace("[Application]", "[Application]\napp.classpath=$APPDIR/visualizer-probe.jar"))
            result = subprocess.run(["xvfb-run", "--auto-servernum", str(image / "bin/nevumi")],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Packaged MilkDrop ready", result.stdout)
            self.assertNotIn("pure virtual method called", result.stderr)

    def test_packaged_runtime_supports_pinned_tls_and_server_certificates(self):
        self.assertTrue(APP.is_file(), "Run :desktopApp:createDistributable first")
        with tempfile.TemporaryDirectory(prefix="nevumi-runtime-") as temporary:
            directory = Path(temporary)
            image = directory / "nevumi"
            shutil.copytree(APP.parent.parent, image)
            source = directory / "TlsProbe.java"
            source.write_text('''
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import javax.net.ssl.SSLContext;
public class TlsProbe {
    public static void main(String[] args) throws Exception {
        var keys = KeyPairGenerator.getInstance("EC");
        keys.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = keys.generateKeyPair();
        var signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(new byte[]{1, 2, 3});
        var signed = signature.sign();
        signature.initVerify(pair.getPublic());
        signature.update(new byte[]{1, 2, 3});
        if (!signature.verify(signed)) throw new AssertionError("Signature verification failed");
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, null, null);
        if (tls.getDefaultSSLParameters().getCipherSuites().length == 0)
            throw new AssertionError("No TLS cipher suites");
        System.out.println("TLS ready");
    }
}
''')
            subprocess.run([str(JAVA / "javac"), str(source)], check=True, capture_output=True)
            probe = image / "lib/app/probe.jar"
            subprocess.run([str(JAVA / "jar"), "--create", "--file", str(probe),
                            "-C", str(directory), "TlsProbe.class"], check=True, capture_output=True)
            configuration = image / "lib/app/nevumi.cfg"
            original = configuration.read_text()
            original = original.replace("app.mainclass=me.d3sox.nevumi.desktop.MainKt", "app.mainclass=TlsProbe")
            configuration.write_text(original.replace("[Application]", "[Application]\napp.classpath=$APPDIR/probe.jar"))
            launcher = image / "bin/nevumi"
            link = directory / "nevumi-link"
            link.symlink_to(launcher)
            for command in [launcher, link]:
                with self.subTest(launcher=command.name):
                    result = subprocess.run([str(command)], capture_output=True, text=True, timeout=15)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn("TLS ready", result.stdout)
                    # The launcher helper can abort even when its parent returns success.
                    self.assertNotIn("pure virtual method called", result.stderr)
                    self.assertNotIn("terminate called without an active exception", result.stderr)

    def test_packaged_player_registers_desktop_media_controls(self):
        self.check_desktop_media_controls()

    def test_packaged_player_starts_with_english_and_german_locales_and_default_audio(self):
        for locale in ["en_US.UTF-8", "de_DE.UTF-8"]:
            with self.subTest(locale=locale):
                self.check_desktop_media_controls({"LC_ALL": locale, "LANG": locale}, default_audio=True)

    def check_desktop_media_controls(self, locale_environment=None, default_audio=False):
        self.assertTrue(APP.is_file(), "Run :desktopApp:createDistributable first")
        with tempfile.TemporaryDirectory(prefix="music-player-desktop-") as temporary:
            directory = Path(temporary)
            display = bus = app = None
            with (directory / "session.log").open("w+") as log:
                try:
                    display = subprocess.Popen(
                        ["Xvfb", "-displayfd", "1", "-screen", "0", "1280x900x24", "-nolisten", "tcp"],
                        stdout=subprocess.PIPE, stderr=log, text=True,
                    )
                    # A cold Xvfb start on CI runners can take longer than the other services.
                    display_name = ":" + first_line(display, timeout=30)
                    address = "unix:path=" + str(directory / "bus")
                    bus_configuration = directory / "bus.conf"
                    bus_configuration.write_text(
                        '<busconfig><type>session</type><listen>' + address + '</listen><auth>EXTERNAL</auth>'
                        '<policy context="default"><allow user="*"/><allow own="*"/>'
                        '<allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>'
                    )
                    bus = subprocess.Popen(
                        ["dbus-daemon", "--config-file=" + str(bus_configuration), "--nofork", "--print-address=1"],
                        stdout=subprocess.PIPE, stderr=log, text=True,
                    )
                    self.assertTrue(first_line(bus).startswith("unix:path="))
                    runtime = directory / "runtime"
                    runtime.mkdir(mode=0o700)
                    environment = dict(os.environ, DISPLAY=display_name, DBUS_SESSION_BUS_ADDRESS=address,
                                       XDG_RUNTIME_DIR=str(runtime), XDG_CONFIG_HOME=str(directory / "config"),
                                       XDG_CACHE_HOME=str(directory / "cache"), SKIKO_RENDER_API="SOFTWARE")
                    environment.pop("WAYLAND_DISPLAY", None)
                    environment.update(locale_environment or {})
                    music = directory / "library"
                    music.mkdir()
                    shutil.copyfile(ROOT / "androidApp/src/androidTest/assets/fixture.flac", music / "fixture.flac")
                    command = [str(APP), "--data-dir=" + str(directory / "music"), "--import-folder=" + str(music)]
                    if not default_audio:
                        command.append("--audio-output=null")
                    app = subprocess.Popen(
                        command,
                        env=environment, stdout=log, stderr=log,
                    )

                    def call(destination, path, method, *arguments):
                        result = subprocess.run(
                            ["gdbus", "call", "--address", address, "--dest", destination,
                             "--object-path", path, "--method", method, *arguments],
                            capture_output=True, text=True, timeout=5,
                        )
                        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                        return result.stdout

                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        if app.poll() is not None:
                            log.seek(0)
                            self.fail("Desktop player exited: " + log.read())
                        owner = call("org.freedesktop.DBus", "/org/freedesktop/DBus",
                                     "org.freedesktop.DBus.NameHasOwner", "org.mpris.MediaPlayer2.nevumi")
                        if "true" in owner:
                            break
                        time.sleep(0.1)
                    else:
                        log.seek(0)
                        self.fail("Desktop media session was not registered: " + log.read())
                    root = call("org.mpris.MediaPlayer2.nevumi", "/org/mpris/MediaPlayer2",
                                "org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2")
                    self.assertIn("'Identity': <'Nevumi'>", root)
                    player = call("org.mpris.MediaPlayer2.nevumi", "/org/mpris/MediaPlayer2",
                                  "org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player")
                    self.assertIn("'PlaybackStatus': <'Stopped'>", player)
                    self.assertIn("'Volume': <1.0>", player)
                    window = subprocess.run(["xwininfo", "-name", "Nevumi"], env=environment,
                                            capture_output=True, text=True, timeout=3)
                    self.assertEqual(0, window.returncode, window.stderr)
                    window_id = int(re.search(r"Window id: (0x[0-9a-f]+)", window.stdout).group(1), 16)
                    close_window(window_id, display_name)
                    app.wait(timeout=10)
                    self.assertEqual(0, app.returncode)
                    log.seek(0)
                    output = log.read()
                    self.assertNotIn("pure virtual method called", output)
                    self.assertNotIn("terminate called without an active exception", output)
                finally:
                    stop(app)
                    stop(bus)
                    stop(display)
                    if bus is not None:
                        bus.stdout.close()
                    if display is not None:
                        display.stdout.close()


if __name__ == "__main__":
    unittest.main()
