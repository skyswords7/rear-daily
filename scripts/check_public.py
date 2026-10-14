"""Scan the public file list and optional APK without printing secret values."""
from pathlib import Path
import argparse
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIRECTORIES = ("src", "res", "assets", "maml", "tests", "docs", "scripts", ".github")
TOP = ("AndroidManifest.xml", "README.md", "LICENSE", "CONTRIBUTING.md", "SECURITY.md",
       "build.py", "package-template.py", "dependencies.json", "source-manifest.example.json", ".gitignore")
PATTERNS = {
    "API token": rb"\bsk-[A-Za-z0-9_-]{24,}\b",
    "GitHub token": rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b",
    "cloud access key": rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "private key": rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----",
    "credential in URL": rb"https?://[^\s/\x22<>:]+:[^\s/\x22<>@]+@",
    "Windows user directory": rb"(?i)[A-Z]:[\\/]Users[\\/][^\s\\/\x22]+",
    "literal authorization": rb"(?i)Bearer [A-Za-z0-9_.-]{24,}",
}
PRIVATE_NAMES = re.compile(r"(?i)(?:\.keystore$|\.jks$|\.p12$|\.secret$|\.env(?:\.|$)|^vault(?:_compatible)?\.xml$|^local\.properties$|^verification-v.*\.txt$)")
FAILURES = []

def scan(name, data):
    for label, pattern in PATTERNS.items():
        for match in re.finditer(pattern, data):
            if (name == 'tests/com/reardaily/app/V05Tests.java' and label == 'credential in URL'
                    and match.group() == b'https://' + b'user:pass@'):
                continue
            # Report location only. Do not expose matched data, even on failure.
            line = data[:match.start()].count(b"\n") + 1
            FAILURES.append(f"{name}:{line}: {label}")
    if PRIVATE_NAMES.search(Path(name).name):
        FAILURES.append(f"{name}: private file")

def public_files():
    if (ROOT / ".git").exists():
        result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True)
        return [ROOT / p for p in result.stdout.decode("utf8").split("\0") if p]
    files = [ROOT / p for p in TOP if (ROOT / p).is_file()]
    for directory in DIRECTORIES:
        files.extend(p for p in (ROOT / directory).rglob("*")
                     if p.is_file() and "__pycache__" not in p.parts
                     and p.name not in ("rearscreen", "rearscreen-probe", "manifest-probe.xml"))
    return sorted(files)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    files = public_files()
    for path in files:
        scan(path.relative_to(ROOT).as_posix(), path.read_bytes())
    entries = 0
    if args.apk:
        with zipfile.ZipFile(args.apk) as archive:
            for entry in archive.infolist():
                if entry.file_size > 128 * 1024 * 1024:
                    raise SystemExit("APK member too large to inspect")
                data = archive.read(entry)
                scan("APK/" + entry.filename, data)
                entries += 1
                if entry.filename.endswith(".dex"):
                    for marker in (b"Lcom/reardaily/app/DailyInstrumentation;",
                                   b"Lcom/reardaily/app/SmokeInstrumentation;",
                                   b"Lcom/reardaily/app/UiTestActivity;"):
                        if marker in data:
                            FAILURES.append("APK/" + entry.filename + ": test class in production")
    if FAILURES:
        print("\n".join(FAILURES))
        return 1
    print(f"PASS: {len(files)} public files; {entries} APK members; no configured secret patterns found")
    return 0

if __name__ == "__main__":
    sys.exit(main())
