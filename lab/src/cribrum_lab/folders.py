"""What a file's folder says about where the image came from."""

from pathlib import Path

_MESSAGING_FOLDERS = {
    "whatsapp",
    "com.whatsapp",
    "whatsapp business",
    "com.whatsapp.w4b",
    "telegram",
    "org.telegram.messenger",
    "com.instagram.android",
    "messenger",
    "com.facebook.orca",
    "signal",
    "org.thoughtcrime.securesms",
}
"""Folder names used by messaging apps, lowercase, in their old and Android 11+ locations."""


def is_camera_folder(path: Path) -> bool:
    return any(part.lower() == "dcim" for part in path.parent.parts)


def is_messaging_folder(path: Path) -> bool:
    return any(part.lower() in _MESSAGING_FOLDERS for part in path.parent.parts)
