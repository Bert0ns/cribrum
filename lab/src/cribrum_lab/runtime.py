"""ModelRuntime: on-device ML models behind one interface (docs/design.md, section 7).

The Android app puts LiteRT or ONNX Runtime behind the same interface. The lab runs the
same ONNX files, so both compute the same outputs.
"""

from collections.abc import Mapping
from pathlib import Path
from typing import Protocol

import numpy as np
import onnxruntime


class ModelRuntime(Protocol):
    def run(self, inputs: Mapping[str, np.ndarray]) -> dict[str, np.ndarray]: ...


class OnnxRuntime:
    def __init__(self, model: Path, threads: int = 0) -> None:
        """`threads` 0 lets ONNX Runtime use every core; worker processes should pass 1."""
        options = onnxruntime.SessionOptions()
        options.log_severity_level = 3  # warnings about unused initializers are noise
        options.intra_op_num_threads = threads
        self._session = onnxruntime.InferenceSession(
            model, options, providers=["CPUExecutionProvider"]
        )

    def run(self, inputs: Mapping[str, np.ndarray]) -> dict[str, np.ndarray]:
        names = [output.name for output in self._session.get_outputs()]
        return dict(zip(names, self._session.run(names, dict(inputs)), strict=True))
