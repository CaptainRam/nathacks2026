"""Compile current Java analysis sources separately from stale Gradle outputs.

This creates no APK and changes no app source or Gradle build artifact. The two
temporary annotation declarations affect nullability metadata only. Android's
SDK jar is used to type-check OcrLine's Rect signatures; no Android operation is
executed by the downstream string/list analysis bridge.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def javac_path(explicit):
    if explicit:
        return str(Path(explicit).expanduser().resolve())
    candidates = [
        Path(os.environ.get("JAVA_HOME", "/nonexistent")) / "bin/javac",
        Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    command = shutil.which("javac")
    if command:
        return command
    raise RuntimeError("Cannot find javac; set JAVA_HOME or pass --javac")


def android_jar_path(explicit):
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if path.is_file():
            return path
        raise FileNotFoundError(path)
    sdk_roots = [
        Path(os.environ.get("ANDROID_HOME", "/nonexistent")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "/nonexistent")),
        Path.home() / "Library/Android/sdk",
    ]
    local = ROOT / "AllergyCheck/local.properties"
    if local.is_file():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk_roots.insert(0, Path(line.split("=", 1)[1]))
    for sdk in sdk_roots:
        jars = sorted((sdk / "platforms").glob("*/android.jar"), reverse=True)
        if jars:
            return jars[0]
    raise RuntimeError("Cannot find android.jar; pass --android-jar")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("/tmp/allergy-emulator-analysis"))
    parser.add_argument("--javac")
    parser.add_argument("--android-jar")
    args = parser.parse_args()
    output = args.output.expanduser().resolve()
    classes = output / "classes"
    stubs = output / "annotation-stubs/androidx/annotation"
    classes.mkdir(parents=True, exist_ok=True)
    stubs.mkdir(parents=True, exist_ok=True)
    # Refuse to clear an unrelated class directory supplied through --output.
    previous_classes = list(classes.rglob("*.class"))
    previous_manifest = output / "compile-manifest.json"
    if previous_classes and not previous_manifest.is_file():
        raise RuntimeError("Output contains classes without an isolated compile manifest; choose a new --output directory")
    if previous_classes:
        previous_metadata = json.loads(previous_manifest.read_text())
        owned = previous_metadata.get("class_sha256", {})
        if any(str(path.relative_to(classes)) not in owned for path in previous_classes):
            raise RuntimeError("Output contains classes not owned by the prior isolated compile; choose a new --output directory")
    for previous in previous_classes:
        previous.unlink()
    annotations = []
    for name in ("NonNull", "Nullable"):
        path = stubs / f"{name}.java"
        path.write_text(f"package androidx.annotation; public @interface {name} {{}}\n")
        annotations.append(path)
    java_root = ROOT / "AllergyCheck/app/src/main/java/com/example/allergycheck"
    sources = sorted((java_root / "analysis").glob("*.java")) + [
        java_root / "ocr/OcrResult.java", java_root / "ocr/OcrLine.java",
    ]
    if not sources or any(not source.is_file() for source in sources):
        raise RuntimeError("Expected current app analysis/OCR sources are missing")
    compiler = javac_path(args.javac)
    android_jar = android_jar_path(args.android_jar)
    command = [compiler, "--release", "11", "-encoding", "UTF-8", "-cp", str(android_jar),
               "-d", str(classes), *(str(path) for path in sources + annotations)]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode:
        raise RuntimeError("Current-source compilation failed:\n" + completed.stdout + completed.stderr)
    version = subprocess.run([compiler, "-version"], capture_output=True, text=True, check=True)
    manifest = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "classes": str(classes),
        "javac": compiler,
        "javac_version": (version.stdout + version.stderr).strip(),
        "android_jar": str(android_jar),
        "method": "Current app Java sources, isolated class directory, temporary nullability annotation declarations",
        "app_or_gradle_artifacts_changed": False,
        "command": command,
        "source_sha256": {str(path.relative_to(ROOT)): digest(path) for path in sources},
        "annotation_stubs_sha256": {str(path.relative_to(output)): digest(path) for path in annotations},
        "class_sha256": {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob("*.class"))},
    }
    path = output / "compile-manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"classes": str(classes), "manifest": str(path),
                      "source_files": len(sources), "compiled_classes": len(manifest["class_sha256"]),
                      "analyzer_class_sha256": manifest["class_sha256"]["com/example/allergycheck/analysis/Analyzer.class"]}))


if __name__ == "__main__":
    main()
