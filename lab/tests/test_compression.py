import importlib.util
import sqlite3
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from cribrum_lab.compression import (
    BALANCED_MAX_PIXELS,
    HIGH_QUALITY,
    SMALLEST,
    SMALLEST_MAX_PIXELS,
    CompressionLevel,
    ImageMeasurement,
    LevelReport,
    aggregate_level_results,
    compress_and_measure,
    compute_psnr,
    compute_quality_metrics,
    compute_ssim,
    format_report,
    target_size,
)

_SCRIPT = Path(__file__).parents[1] / "scripts" / "measure_compression.py"
_spec = importlib.util.spec_from_file_location("measure_compression", _SCRIPT)
_mc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mc)
find_jpeg_paths = _mc.find_jpeg_paths
recommendation = _mc.recommendation


def test_target_size_no_limit_keeps_dimensions():
    assert target_size(4000, 3000, max_pixels=None) == (4000, 3000)


def test_target_size_under_limit_keeps_dimensions():
    assert target_size(4000, 3000, max_pixels=BALANCED_MAX_PIXELS) == (4000, 3000)
    assert target_size(4000, 3000, max_pixels=SMALLEST_MAX_PIXELS) == (4000, 3000)


def test_target_size_downscales_to_at_most_limit():
    orig_w, orig_h = 16320, 12240
    new_w, new_h = target_size(orig_w, orig_h, max_pixels=BALANCED_MAX_PIXELS)
    assert new_w * new_h <= BALANCED_MAX_PIXELS
    assert pytest.approx(new_w / new_h, rel=1e-3) == orig_w / orig_h

    small_w, small_h = target_size(orig_w, orig_h, max_pixels=SMALLEST_MAX_PIXELS)
    assert small_w * small_h <= SMALLEST_MAX_PIXELS
    assert pytest.approx(small_w / small_h, rel=1e-3) == orig_w / orig_h


def test_target_size_exact_boundary():
    assert target_size(4000, 3000, max_pixels=12_000_000) == (4000, 3000)
    w, h = target_size(4001, 3000, max_pixels=12_000_000)
    assert w * h <= 12_000_000


def test_ssim_identical_image():
    img = Image.new("RGB", (64, 64), (100, 150, 200))
    assert compute_ssim(img, img) == pytest.approx(1.0, abs=1e-5)
    assert compute_psnr(img, img) == float("inf")


def test_ssim_degraded_image():
    img1 = Image.new("RGB", (64, 64), (100, 150, 200))
    img2 = Image.new("RGB", (64, 64), (110, 140, 210))
    s12 = compute_ssim(img1, img2)
    s21 = compute_ssim(img2, img1)
    assert 0.0 < s12 < 1.0
    assert s12 == pytest.approx(s21, abs=1e-5)
    psnr = compute_psnr(img1, img2)
    assert 20.0 < psnr < 60.0


def test_ssim_noise_image_known_value():
    rng = np.random.RandomState(42)
    arr1 = rng.randint(40, 220, (64, 64), dtype=np.uint8)
    noise = rng.randint(-25, 25, (64, 64))
    arr2 = np.clip(arr1.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    img1 = Image.fromarray(arr1, mode="L")
    img2 = Image.fromarray(arr2, mode="L")
    ssim, psnr = compute_quality_metrics(img1, img2)
    assert ssim == pytest.approx(0.963359, abs=1e-5)
    assert psnr == pytest.approx(24.9740, abs=1e-3)


def test_ssim_chunk_boundary_equivalence():
    rng = np.random.RandomState(123)
    arr1 = rng.randint(0, 256, (64, 64), dtype=np.uint8)
    arr2 = rng.randint(0, 256, (64, 64), dtype=np.uint8)
    img1 = Image.fromarray(arr1, mode="L")
    img2 = Image.fromarray(arr2, mode="L")
    s8, p8 = compute_quality_metrics(img1, img2, chunk_rows=8)
    s32, p32 = compute_quality_metrics(img1, img2, chunk_rows=32)
    s1024, p1024 = compute_quality_metrics(img1, img2, chunk_rows=1024)
    assert s8 == pytest.approx(s1024, abs=1e-6)
    assert p8 == pytest.approx(p1024, abs=1e-6)
    assert s32 == pytest.approx(s1024, abs=1e-6)


def test_quality_metrics_under_8_pixels():
    img1 = Image.new("RGB", (5, 7), (100, 150, 200))
    img2 = Image.new("RGB", (5, 7), (110, 140, 210))
    s, p = compute_quality_metrics(img1, img2)
    assert 0.0 < s < 1.0
    assert 20.0 < p < 60.0


def test_quality_metrics_non_multiple_of_8():
    img1 = Image.new("RGB", (67, 73), (100, 150, 200))
    img2 = Image.new("RGB", (67, 73), (110, 140, 210))
    s, p = compute_quality_metrics(img1, img2)
    assert 0.0 < s < 1.0
    assert 20.0 < p < 60.0


def test_ssim_dimension_mismatch_raises():
    img1 = Image.new("RGB", (64, 64), (100, 150, 200))
    img2 = Image.new("RGB", (32, 32), (100, 150, 200))
    with pytest.raises(ValueError, match="Image sizes must match"):
        compute_ssim(img1, img2)


def test_aggregate_level_results():
    level = CompressionLevel("Balanced", quality=85, max_pixels=50_000_000)
    measurements = [
        ImageMeasurement(
            level=level,
            original_bytes=1000,
            compressed_bytes=1000 - i * 50,
            saved_bytes=i * 50,
            saved_share=i * 0.05,
            ssim=0.90 + i * 0.01,
            psnr=30.0 + i,
        )
        for i in range(10)
    ]
    report = aggregate_level_results(level, measurements, skipped_count=1)
    assert report.level == level
    assert report.sample_count == 10
    assert report.skipped_count == 1
    assert report.refused_share == pytest.approx(0.1, abs=1e-4)  # 1 out of 10 has saved_bytes <= 0
    assert report.ssim_median == pytest.approx(0.945, abs=1e-4)
    assert report.ssim_worst_decile == pytest.approx(0.909, abs=1e-3)
    assert report.saved_share_median == pytest.approx(0.225, abs=1e-4)
    assert report.saved_share_worst_decile == pytest.approx(0.045, abs=1e-3)


def test_compress_and_measure_synthetic_image(tmp_path):
    test_img = Image.new("RGB", (200, 100), (120, 140, 160))
    exif = test_img.getexif()
    exif[0x0112] = 6
    img_path = tmp_path / "original.jpg"
    test_img.save(img_path, "JPEG", quality=95, exif=exif)

    scratch_dir = tmp_path / "scratch"
    scratch_dir.mkdir()

    m_high = compress_and_measure(img_path, HIGH_QUALITY, temp_dir=scratch_dir)
    assert m_high.level == HIGH_QUALITY
    assert m_high.original_bytes == img_path.stat().st_size
    assert m_high.compressed_bytes > 0
    assert 0.90 <= m_high.ssim <= 1.0
    assert m_high.psnr > 30.0
    assert list(scratch_dir.iterdir()) == []

    small_level = SMALLEST
    m_small = compress_and_measure(img_path, small_level, temp_dir=scratch_dir)
    assert m_small.compressed_bytes > 0
    assert list(scratch_dir.iterdir()) == []


def test_compress_and_measure_downscaled(tmp_path):
    orig_w, orig_h = 200, 200
    test_img = Image.new("RGB", (orig_w, orig_h), (80, 120, 160))
    img_path = tmp_path / "orig_large.jpg"
    test_img.save(img_path, "JPEG", quality=95)

    max_px = 10_000
    target_w, target_h = target_size(orig_w, orig_h, max_pixels=max_px)
    assert (target_w, target_h) == (100, 100)

    custom_level = CompressionLevel("TestDownscale", quality=85, max_pixels=max_px)
    scratch_dir = tmp_path / "scratch"
    scratch_dir.mkdir()

    m = compress_and_measure(img_path, custom_level, temp_dir=scratch_dir)
    assert m.compressed_bytes > 0
    assert 0.80 <= m.ssim <= 1.0
    assert list(scratch_dir.iterdir()) == []


def test_compress_and_measure_cmyk_image(tmp_path):
    cmyk_img = Image.new("CMYK", (100, 100), (0, 100, 100, 0))
    img_path = tmp_path / "cmyk.jpg"
    cmyk_img.save(img_path, "JPEG")

    custom_level = CompressionLevel("DownscaleCMYK", quality=85, max_pixels=2500)
    scratch_dir = tmp_path / "scratch"
    m = compress_and_measure(img_path, custom_level, temp_dir=scratch_dir)
    assert m.compressed_bytes > 0
    assert 0.80 <= m.ssim <= 1.0
    assert list(scratch_dir.iterdir()) == []


def test_saved_share_sign_when_file_grows(tmp_path):
    img = Image.new("RGB", (64, 64), (100, 120, 140))
    img_path = tmp_path / "low_q.jpg"
    img.save(img_path, "JPEG", quality=40)

    high_q_level = CompressionLevel("GrowLevel", quality=95, max_pixels=None)
    m = compress_and_measure(img_path, high_q_level, temp_dir=tmp_path / "scratch")
    assert m.compressed_bytes > m.original_bytes
    assert m.saved_bytes < 0
    assert m.saved_share < 0.0


def test_format_report_and_recommendation():
    lvl = CompressionLevel("High quality", quality=90, max_pixels=None)
    report = LevelReport(
        level=lvl,
        sample_count=100,
        ssim_median=0.965,
        ssim_worst_decile=0.912,
        saved_share_median=0.452,
        saved_share_worst_decile=0.105,
        psnr_median=38.4,
        psnr_worst_decile=33.1,
        total_saved_bytes=100_000_000,
        total_original_bytes=200_000_000,
        refused_share=0.08,
        skipped_count=2,
    )
    text = format_report([report])
    assert "High quality" in text
    assert "100" in text
    assert "2" in text
    assert "0.9650" in text
    assert "0.9120" in text
    assert "38.4dB" in text
    assert "33.1dB" in text
    assert "45.2%" in text
    assert "10.5%" in text
    assert "8.0%" in text
    assert "95.4 / 190.7 MB" in text

    rec_text = recommendation([report])
    assert "High quality" in rec_text
    assert "refused 8.0%" in rec_text


def test_find_jpeg_paths(tmp_path):
    folder = tmp_path / "photos"
    folder.mkdir()
    p1 = folder / "img1.jpg"
    p2 = folder / "img2.jpeg"
    p3 = folder / "doc.png"
    Image.new("RGB", (10, 10)).save(p1)
    Image.new("RGB", (10, 10)).save(p2)
    p3.write_bytes(b"dummy")

    found = find_jpeg_paths(folder=folder)
    assert found == [p1, p2]

    labels_db = tmp_path / "labels.sqlite"
    with sqlite3.connect(labels_db) as con:
        con.execute("CREATE TABLE labels (path TEXT)")
        con.execute("INSERT INTO labels VALUES (?)", (str(p1),))
        con.execute("INSERT INTO labels VALUES (?)", (str(p3),))
    assert find_jpeg_paths(labels_db=labels_db) == [p1]

    index_db = tmp_path / "index.sqlite"
    with sqlite3.connect(index_db) as con:
        con.execute("CREATE TABLE images (path TEXT, mime TEXT, camera_make TEXT)")
        con.execute("INSERT INTO images VALUES (?, 'image/jpeg', 'TestCam')", (str(p1),))
        con.execute("INSERT INTO images VALUES (?, 'image/jpeg', NULL)", (str(p2),))
    cam_found = find_jpeg_paths(labels_db=labels_db, index_db=index_db, camera_only=True)
    assert cam_found == [p1]
