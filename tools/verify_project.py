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
        "required app and workflow files exist": lambda: all((ROOT / p).is_file() for p in [
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt",
            "app/src/main/java/com/ogcellak/murgeronly/MergeService.kt",
            "app/src/main/res/drawable/ic_murger_launcher.xml",
            ".github/workflows/build.yml",
        ]),
        "native Android media APIs are used without transcoding dependencies": lambda: (
            "android.media.MediaExtractor" in service
            and "android.media.MediaMuxer" in service
            and "media3-transformer" not in gradle
        ),
        "release workflow builds, signs, verifies, and uploads APK": lambda: all(x in workflow for x in [
            "assembleRelease", "apksigner", "APK integrity verification", "upload-artifact@v4"
        ]),
    },
    "formats": {
        "picker is restricted to MP4 MIME type": lambda: 'type = "video/mp4"' in activity,
        "runtime checks MP4 file signature": lambda: (
            "hasMp4Signature" in service and '"ftyp"' in service
        ),
        "extracts input tracks directly from each document URI": lambda: (
            "extractor.setDataSource(context, uri, null)" in service
            and "MediaExtractor" in service
        ),
        "writes a single MP4 with Android MediaMuxer": lambda: (
            "MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4" in service
            and "MediaMuxer(output.absolutePath" in service
        ),
        "copies encoded samples without re-encoding": lambda: (
            "extractor.readSampleData(buffer, 0)" in service
            and "muxer.writeSampleData" in service
            and "MediaCodec.BufferInfo" in service
            and "Transformer" not in service
        ),
        "preserves audio and video tracks": lambda: (
            'mime.startsWith("video/") || mime.startsWith("audio/")' in service
            and 'selected.any' in service
        ),
        "rejects incompatible streams instead of degrading them": lambda: (
            "formatsMatch" in service
            and "different number of audio/video tracks" in service
            and "Lossless merging requires matching track formats" in service
        ),
    },
    "thread": {
        "merge work runs away from the UI thread": lambda: (
            "CoroutineScope(SupervisorJob() + Dispatchers.IO)" in service
            and "withContext(Dispatchers.IO)" in service
        ),
        "foreground service and progress updates remain enabled": lambda: (
            "startForeground(NOTIFICATION_ID" in service
            and "sendBroadcast(Intent(PROGRESS)" in service
            and "setProgress(100" in service
        ),
        "errors and service cancellation are handled": lambda: (
            "Merge failed:" in service and "scope.cancel()" in service
            and "extractors.forEach" in service and "muxer?.release()" in service
        ),
    },
    "storage": {
        "uses app external movie storage for temporary output": lambda: (
            "getExternalFilesDir(Environment.DIRECTORY_MOVIES)" in service
        ),
        "saves final result in Movies/Merger Only": lambda: (
            "MediaStore.Video.Media.EXTERNAL_CONTENT_URI" in service
            and "Movies/Merger Only" in service
            and "openOutputStream(output, \"w\")" in service
        ),
        "cleans temporary files and incomplete media rows": lambda: (
            "temp.delete()" in service
            and "context.contentResolver.delete(output, null, null)" in service
        ),
    },
    "ui": {
        "explains original quality and no re-encoding": lambda: (
            "LOSSLESS STREAM MERGE" in activity
            and "without re-encoding" in activity
            and "Incompatible MP4s show an error" in activity
        ),
        "filters out non-MP4 documents on selection": lambda: (
            "isMp4Document(uri)" in activity
            and 'endsWith(".mp4")' in activity
            and "Only MP4 video files can be added." in activity
        ),
        "supports multi-select and clear selection": lambda: (
            "Intent.EXTRA_ALLOW_MULTIPLE" in activity
            and "names.clear()" in activity
        ),
        "retains live progress, ETA, and retry after failure": lambda: (
            "ProgressBar" in activity and "formatTime" in activity
            and 'message.startsWith("Merge failed:")' in activity
            and "merge.isEnabled = names.size >= 2" in activity
        ),
        "launcher icon and foreground service permissions remain": lambda: (
            'android:icon="@drawable/ic_murger_launcher"' in manifest
            and "android.permission.FOREGROUND_SERVICE" in manifest
            and 'android:foregroundServiceType="dataSync"' in manifest
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
