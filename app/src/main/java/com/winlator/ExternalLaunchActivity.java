package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/**
 * Entry point for the ATFCORE player (protected by a signature permission): opens a Windows
 * executable in the add-on's default container.
 *
 * Intent extras:
 *   exec_path  absolute path of the .exe on shared storage (required)
 *   title      game title (optional, for messages)
 *   unity_single_thread  Unity players render on one thread (recommended mode)
 *   game_root  folder the game owns (mapped to a drive when no drive holds it; optional)
 *   exec_args  extra arguments for the game (optional)
 *   atfcore_settings  JSON of per-game overrides, same keys as shortcut extras (optional):
 *              screenSize, graphicsDriver, dxwrapper, dxwrapperConfig, audioDriver, box64Preset,
 *              vulkanDriver, openglDriver, envVars, forceFullscreen, hudMode
 *
 * Result: RESULT_OK when the game was started, RESULT_CANCELED with an "error" extra otherwise.
 */
public class ExternalLaunchActivity extends Activity {
    public static final String EXTRA_EXEC_PATH = "exec_path";
    public static final String EXTRA_SINGLE_THREAD = "unity_single_thread";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARGS = "exec_args";
    public static final String EXTRA_SETTINGS = "atfcore_settings";
    public static final String EXTRA_ROOT = "game_root";
    public static final String EXTRA_ERROR = "error";
    private static final String DEFAULT_CONTAINER_NAME = "ATFCORE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String execPath = getIntent().getStringExtra(EXTRA_EXEC_PATH);
        if (execPath == null || !new File(execPath).isFile()) { fail(getString(R.string.atfcore_missing_executable)); return; }

        // First run: the Linux userland is installed by the main screen (it shows the progress).
        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) {
            Toast.makeText(this, R.string.atfcore_setup_first, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            fail(getString(R.string.atfcore_setup_first));
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        ArrayList<Container> containers = manager.getContainers();
        Container existing = null;
        for (Container c : containers) if (DEFAULT_CONTAINER_NAME.equals(c.getName())) existing = c;
        if (existing == null && !containers.isEmpty()) existing = containers.get(0);
        if (existing != null) {
            useDeviceDriver(existing);
            launch(existing, execPath);
            return;
        }

        JSONObject data = new JSONObject();
        try {
            data.put("name", DEFAULT_CONTAINER_NAME);
            // The driver the device suits best (Turnip on Adreno: Vulkan 1.3, so DXVK 2.x and
            // DXGI 1.6, which Unity 6 needs); the container default is Vortek.
            data.put("graphicsDriver", deviceDriver());
        } catch (JSONException e) { fail(e.toString()); return; }
        manager.createContainerAsync(data, container -> runOnUiThread(() -> {
            if (container == null) fail(getString(R.string.atfcore_container_failed));
            else launch(container, execPath);
        }));
    }

    // Containers made by earlier versions took the generic default (Vortek) even on Adreno GPUs,
    // whose Vortek path reports Vulkan 1.1 and falls back to DXVK 1.10 (no DXGI 1.6): fix once.
    // OpenGL through Zink (Mesa): Gladio crashed the add-on in glGetCompressedTexImage when Wine
    // set up OpenGL (Ren'Py, and DirectDraw games such as KiriKiri visual novels).
    private void useDeviceDriver(Container container) {
        if (!DEFAULT_CONTAINER_NAME.equals(container.getName()) || container.getExtra("atfcoreDriver").equals("2")) return;
        String device = deviceDriver();
        if (!device.equals(container.getGraphicsDriver())) container.setGraphicsDriver(device);
        container.putExtra("atfcoreDriver", "2");
        container.saveData();
    }

    /** The device's Vulkan driver (Turnip on Adreno, else Vortek) with Zink for OpenGL. */
    private String deviceDriver() {
        String vulkan = com.winlator.container.GraphicsDrivers.getDefaultDriver(this).split(",")[0];
        return vulkan + "," + com.winlator.container.GraphicsDrivers.ZINK;
    }

    private void launch(Container container, String execPath) {
        mapGameDrive(container, execPath, getIntent().getStringExtra(EXTRA_ROOT));
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", execPath);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        intent.putExtra("title", title != null ? title : new File(execPath).getName());
        // Unity players: a frameless window (the game keeps its own windowed/fullscreen setting).
        String args = "";
        if (new File(new File(execPath).getParentFile(), "UnityPlayer.dll").isFile()) {
            boolean singleThread = getIntent().getBooleanExtra(EXTRA_SINGLE_THREAD, false);
            args = singleThread ? "-popupwindow -force-gfx-direct" : "-popupwindow";
        }
        String extra = getIntent().getStringExtra(EXTRA_ARGS);
        if (extra != null && !extra.trim().isEmpty()) args = (args + " " + extra.trim()).trim();
        if (!args.isEmpty()) intent.putExtra("exec_args", args);
        String settings = getIntent().getStringExtra(EXTRA_SETTINGS);
        if (settings != null) intent.putExtra(EXTRA_SETTINGS, settings);
        startActivity(intent);
        setResult(RESULT_OK);
        finish();
    }

    // Wine sees only the container's drives: the game's folder (shared storage, e.g. the
    // player's mirror under Android/media) gets a drive letter of its own when no drive holds it.
    // The game's own folder is the root of drive Y: for this launch, so the game sees short paths
    // without spaces (Y:\\Game.exe): launchers that build unquoted command lines or paths break on
    // folders like "test_fixture_21". Y: is reassigned to the current game every launch.
    static final char GAME_DRIVE = 'Y';

    private static void mapGameDrive(Container container, String execPath, String gameRoot) {
        if (gameRoot != null && execPath.startsWith(gameRoot + "/") && new File(gameRoot).isDirectory()) {
            StringBuilder drives = new StringBuilder(GAME_DRIVE + ":" + gameRoot);
            for (com.winlator.container.Drive drive : container.drivesIterator())
                if (Character.toUpperCase(drive.letter.charAt(0)) != GAME_DRIVE) drives.append(drive.letter).append(':').append(drive.path);
            if (!drives.toString().equals(container.getDrives())) {
                container.setDrives(drives.toString());
                container.saveData();
            }
            return;
        }
        for (com.winlator.container.Drive drive : container.drivesIterator())
            if (execPath.startsWith(drive.path + "/")) return;
        // The player mirrors every game under one folder: one drive serves them all.
        final String mirror = "/Android/media/dev.openscene.player/windows";
        int at = execPath.indexOf(mirror + "/");
        String folder = at >= 0 ? execPath.substring(0, at + mirror.length())
                : gameRoot != null && execPath.startsWith(gameRoot + "/") ? new File(gameRoot).getParent()
                : new File(execPath).getParent();
        if (folder == null) return;
        String drives = container.getDrives();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (com.winlator.container.Drive drive : container.drivesIterator()) used.add(drive.letter.toUpperCase());
        for (char letter = 'G'; letter <= 'Y'; letter++) {
            if (used.contains(String.valueOf(letter))) continue;
            container.setDrives(drives + letter + ":" + folder);
            container.saveData();
            return;
        }
    }

    private void fail(String message) {
        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR, message);
        setResult(RESULT_CANCELED, result);
        finish();
    }
}
