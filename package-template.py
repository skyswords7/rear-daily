"""Build the standalone MAML template without device dumps or vendor assets."""
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent

def package_template():
    source = ROOT / "maml" / "manifest.xml"
    destination = ROOT / "assets" / "setup" / "manifest.xml"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    with zipfile.ZipFile(ROOT / "maml" / "rearscreen", "w", zipfile.ZIP_DEFLATED) as archive:
        info = zipfile.ZipInfo("manifest.xml", (2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, source.read_bytes())

if __name__ == "__main__":
    package_template()
    print("Packaged standalone RearDaily template")
