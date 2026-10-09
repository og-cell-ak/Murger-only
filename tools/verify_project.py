from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SERVICE_PATH = ROOT / "app/src/main/java/com/ogcellak/murgeronly/MergeService.kt"
ACTIVITY_PATH = ROOT / "app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt"
GRADLE_PATH = ROOT / "app/build.gradle.kts"
MANIFEST_PATH = ROOT / "app/src/main/AndroidManifest.xml"
WORKFLOW_PATH = ROOT / ".github/workflows/build.yml"

service = SERVICE_PATH.read_text()
activity = ACTIVITY_PATH.read_text()
gradle = GRADLE_PATH.read_text()
manifest = MANIFEST_PATH.read_text()
workflow = WORKFLOW_PATH.read_text()

checks = {
    "structure": {
        "required files exist": lambda: all((ROOT / p).is_file() for p in [
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt",
            "app/src/main/java/com/ogcellak/murgeronly/MergeService.kt",
            "app/src/main/res/drawable/ic_murger_launcher.xml",
            ".github/workflows/build.yml",
        ]),
        "Media3 transformer dependencies declared": lambda: all(x in gradle for x in [
            "androidx.media3:media3-transformer:1.5.1",
            "androidx.media3:media3-common:1.5.1",
        ]),
        "release workflow builds and validates signed APK": lambda: all(x in workflow for x in [
            "assembleRelease", "apksigner", "APK integrity verification", "upload-artifact@v4"
        ]),
    },
    "formats": {
        "uses one sequential media sequence": lambda: (
            "EditedMediaItemSequence.Builder(mediaItems)" in service
            and "Composition.Builder(sequence).build()" in service
        ),
        "normalizes video and audio codecs": lambda: (
            "setVideoMimeType(MimeTypes.VIDEO_H264)" in service
            and "setAudioMimeType(MimeTypes.AUDIO_AAC)" in service
        ),
        "no old stream-copy incompatibility rejection path": lambda: (
            "MediaMuxer" not in service and "compatible(" not in service
            and "Lossless merge needs matching codec" not in service
        ),
        "input references handled as content URIs": lambda: "MediaItem.fromUri(uri)" in service,
    },
    "thread": {
        "Transformer starts on Android main looper": lambda: (
            "Handler(Looper.getMainLooper())" in service
            and "mainHandler.post" in service
            and "transformer.start(composition, output.absolutePath)" in service
        ),
        "progress is polled on the main dispatcher": lambda: (
            "Dispatchers.Main.immediate" in service
            and "transformer.getProgress(holder)" in service
            and "Converting clips to MP4" in service
        ),
        "cancel and exceptions handled": lambda: (
            "continuation.invokeOnCancellation" in service
            and "transformer.cancel()" in service
            and "resumeWithException" in service
        ),
    },
    "storage": {
        "large temporary export uses app external files where available": lambda: "getExternalFilesDir(Environment.DIRECTORY_MOVIES)" in service,
        "saves final output through MediaStore": lambda: (
            "MediaStore.Video.Media.EXTERNAL_CONTENT_URI" in service
            and "openOutputStream(output, \"w\")" in service
            and "RELATIVE_PATH" in service
        ),
        "cleans intermediate and failed output": lambda: (
            "temp.delete()" in service
            and "context.contentResolver.delete(output, null, null)" in service
        ),
        "runs as a foreground service": lambda: (
            "startForeground(NOTIFICATION_ID" in service
            and "android:foregroundServiceType=\"dataSync\"" in manifest
        ),
    },
    "ui": {
        "labels conversions honestly": lambda: (
            "MIXED-FORMAT MP4 EXPORT" in activity
            and "H.264/AAC MP4" in activity
            and "quality slightly" in activity
        ),
        "selects multiple video files": lambda: (
            'type = "video/*"' in activity
            and "Intent.EXTRA_ALLOW_MULTIPLE" in activity
        ),
        "live progress and ETA remain": lambda: (
            "ProgressBar" in activity
            and "percent.text" in activity
            and "formatTime" in activity
            and "MergeService.PROGRESS" in activity
        ),
        "retry enabled after failure/completion": lambda: (
            'message.startsWith("Merge failed:")' in activity
            and "merge.isEnabled = names.size >= 2" in activity
        ),
        "launcher icon and notification permission/service set": lambda: (
            'android:icon="@drawable/ic_murger_launcher"' in manifest
            and "android.permission.FOREGROUND_SERVICE" in manifest
        ),
    },
}

requested = sys.argv[1:] or list(checks.keys())
failed = []
for category in requested:
    if category not in checks:
        raise SystemExit(f"Unknown check category: {category}")
    print(f"VERIFY: {category}")
    for label, test in checks[category].items():
        try:
            passed = bool(test())
        except Exception as exc:
            passed = False
            print(f"  FAIL: {label}: {exc}")
        if passed:
            print(f"  PASS: {label}")
        else:
            print(f"  FAIL: {label}")
            failed.append((category, label))

if failed:
    raise SystemExit(f"{len(failed)} verification check(s) failed")
print(f"PASS: {sum(len(checks[c]) for c in requested)} checks across {len(requested)} verification phase(s)")
