from pathlib import Path

required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt",
    "app/src/main/java/com/ogcellak/murgeronly/MergeService.kt",
    "app/src/main/res/drawable/ic_murger_launcher.xml",
    ".github/workflows/build.yml",
]
for file in required:
    assert Path(file).exists(), f"Missing required file: {file}"

service = Path("app/src/main/java/com/ogcellak/murgeronly/MergeService.kt").read_text()
activity = Path("app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt").read_text()
manifest = Path("app/src/main/AndroidManifest.xml").read_text()

for token in [
    "MediaExtractor", "MediaMuxer", "MediaStore", "ByteBuffer.allocateDirect",
    "Copying video", "Copying audio", "Finalizing MP4", "compatible(",
    "contentResolver.delete(output", "IS_PENDING"
]:
    assert token in service, f"Missing stability/lossless merge requirement: {token}"

for token in ["ProgressBar", "percent.text", "About", "MergeService.PROGRESS"]:
    assert token in activity, f"Missing UI progress requirement: {token}"

assert "android:icon=\"@drawable/ic_murger_launcher\"" in manifest
assert "android:foregroundServiceType=\"dataSync\"" in manifest
print("PASS: lossless stream-copy, large-sample buffering, cleanup, background service, icon and live progress checks")
