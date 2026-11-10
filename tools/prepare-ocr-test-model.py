#!/usr/bin/env python3
"""Prepare the pinned, test-only Tesseract English model."""

import hashlib
from pathlib import Path
import sys
import urllib.request


URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/65727574dfcd264acbb0c3e07860e4e9e9b22185/eng.traineddata"
SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
SIZE = 4_113_088


def valid(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == SHA256


def prepare(path: Path) -> None:
    if valid(path):
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".part")
    try:
        with urllib.request.urlopen(URL, timeout=45) as response:
            payload = response.read(SIZE + 1)
        if len(payload) != SIZE or hashlib.sha256(payload).hexdigest() != SHA256:
            raise ValueError("Le modèle OCR de test ne correspond pas à la source épinglée.")
        temporary.write_bytes(payload)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))
