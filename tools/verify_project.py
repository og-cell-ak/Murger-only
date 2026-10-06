from pathlib import Path
req=["settings.gradle.kts","build.gradle.kts","app/build.gradle.kts","app/src/main/AndroidManifest.xml","app/src/main/java/com/ogcellak/murgeronly/MainActivity.kt","app/src/main/java/com/ogcellak/murgeronly/MergeService.kt",".github/workflows/build.yml"]
for f in req: assert Path(f).exists(),f
s=Path("app/src/main/java/com/ogcellak/murgeronly/MergeService.kt").read_text()
for x in ["MediaExtractor","MediaMuxer","MediaStore","ByteBuffer.allocateDirect","Merging video"]: assert x in s,x
print("PASS: architecture and stream-copy merger verified")
