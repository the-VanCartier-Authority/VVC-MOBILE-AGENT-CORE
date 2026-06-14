#!/usr/bin/env python3
"""
Descargador reproducible de modelos LiteRT/TFLite para VVC Mobile Agent Core.

Los artefactos se descargan exclusivamente en app/src/main/assets/models/.
Por cada .tflite se genera un archivo lateral .sha256 para que la app valide
la integridad antes de cargar el binario en memoria.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path
from typing import Dict, Iterable, List

REPO_ROOT = Path(__file__).resolve().parents[1]
MODELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models"
MANIFEST_PATH = MODELS_DIR / "vvc_edge_models_manifest.json"
BUFFER_SIZE = 1024 * 1024

MODEL_CATALOG: List[Dict[str, str]] = [
    {
        "id": "audio_scribe_yamnet_classifier",
        "skill": "audio_scribe",
        "file": "audio_scribe_yamnet_classifier.tflite",
        "url": "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite",
        "source": "TensorFlow Lite Task Library / Google YAMNet audio classifier",
    },
    {
        "id": "mobile_actions_text_classifier",
        "skill": "mobile_actions",
        "file": "mobile_actions_text_classifier.tflite",
        "url": "https://storage.googleapis.com/download.tensorflow.org/models/tflite/text_classification/text_classification_v2.tflite",
        "source": "TensorFlow Lite Task Library / Google text classification v2",
    },
    {
        "id": "ask_image_mobilenet_quant_classifier",
        "skill": "ask_image",
        "file": "ask_image_mobilenet_quant_classifier.tflite",
        "url": "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/image_classification/android/mobilenet_v1_1.0_224_quantized_1_metadata_1.tflite",
        "source": "TensorFlow Lite Task Library / Google MobileNet quantized image classifier",
    },
]

OPTIONAL_MODEL_CATALOG: List[Dict[str, str]] = [
    {
        "id": "ask_image_mobilenetv4_float16_reference",
        "skill": "ask_image",
        "file": "ask_image_mobilenetv4_float16_reference.tflite",
        "url": "https://huggingface.co/byoussef/MobileNetV4_Conv_Medium_TFLite_224/resolve/main/mobilenetv4_conv_medium.e500_r224_in1k_float16.tflite",
        "source": "MobileNetV4 TFLite community conversion from TIMM weights; not enabled by default because it is float16, not INT8",
    },
]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(BUFFER_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(url: str, destination: Path) -> None:
    temporary = destination.with_suffix(destination.suffix + ".partial")
    request = urllib.request.Request(url, headers={"User-Agent": "VVC-Mobile-Agent-Core/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
        while True:
            chunk = response.read(BUFFER_SIZE)
            if not chunk:
                break
            output.write(chunk)
    temporary.replace(destination)


def write_hash_sidecar(model_path: Path, digest: str) -> None:
    model_path.with_suffix(model_path.suffix + ".sha256").write_text(f"{digest}  {model_path.name}\n", encoding="utf-8")


def selected_models(include_optional: bool, selected_ids: Iterable[str]) -> List[Dict[str, str]]:
    catalog = MODEL_CATALOG + (OPTIONAL_MODEL_CATALOG if include_optional else [])
    selected = set(selected_ids)
    if not selected:
        return catalog
    return [model for model in catalog if model["id"] in selected]


def write_manifest(entries: List[Dict[str, str]]) -> None:
    MANIFEST_PATH.write_text(json.dumps({"models": entries}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Descarga modelos LiteRT/TFLite locales de VVC.")
    parser.add_argument("--include-optional", action="store_true", help="Incluye referencias opcionales no estrictamente INT8/oficiales.")
    parser.add_argument("--model", action="append", default=[], help="ID específico de modelo a descargar; se puede repetir.")
    parser.add_argument("--force", action="store_true", help="Redescarga aunque el archivo exista.")
    args = parser.parse_args()

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    manifest_entries: List[Dict[str, str]] = []

    for model in selected_models(args.include_optional, args.model):
        destination = MODELS_DIR / model["file"]
        if args.force or not destination.exists():
            print(f"Descargando {model['id']} -> {destination}")
            download_file(model["url"], destination)
        else:
            print(f"Usando archivo existente {destination}")

        digest = sha256_file(destination)
        write_hash_sidecar(destination, digest)
        manifest_entries.append({**model, "sha256": digest, "relativePath": f"models/{model['file']}"})
        print(f"SHA-256 {model['file']}: {digest}")

    write_manifest(manifest_entries)
    print(f"Manifiesto escrito en {MANIFEST_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
