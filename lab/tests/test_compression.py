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
    compute_ssim,
    format_report,
    target_size,
)


def test_target_size_no_limit_keeps_dimensions():
    assert target_size(4000, 3000, max_pixels=None) == (4000, 3000)


def test_target_size_under_limit_keeps_dimensions():
    assert target_size(4000, 3000, max_pixels=BALANCED_MAX_PIXELS) == (4000, 3000)
    assert target_size(4000, 3000, max_pixels=SMALLEST_MAX_PIXELS) == (4000, 3000)


def test_target_size_downscales_to_at_most_limit():
    # 200 MP photo (e.g. 16320 x 12240 = 199,756,800 pixels)
    orig_w, orig_h = 16320, 12240
    new_w, new_h = target_size(orig_w, orig_h, max_pixels=BALANCED_MAX_PIXELS)
    assert new_w * new_h <= BALANCED_MAX_PIXELS
    # Aspect ratio preserved within 0.1%
    assert pytest.approx(new_w / new_h, rel=1e-3) == orig_w / orig_h

    # Downscale to 12 MP
    small_w, small_h = target_size(orig_w, orig_h, max_pixels=SMALLEST_MAX_PIXELS)
    assert small_w * small_h <= SMALLEST_MAX_PIXELS
    assert pytest.approx(small_w / small_h, rel=1e-3) == orig_w / orig_h


def test_target_size_exact_boundary():
    # Exactly 12 MP: 4000 x 3000 = 12,000,000
    assert target_size(4000, 3000, max_pixels=12_000_000) == (4000, 3000)
    # Just above: 4001 x 3000 = 12,003,000
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
    report = aggregate_level_results(level, measurements)
    assert report.level == level
    assert report.sample_count == 10
    # Median of ssim: [0.90 .. 0.99], median = 0.945
    assert report.ssim_median == pytest.approx(0.945, abs=1e-4)
    # Worst decile (10th percentile) of ssim
    assert report.ssim_worst_decile == pytest.approx(0.909, abs=1e-3)
    # Median of saved share: [0.0 .. 0.45], median = 0.225
    assert report.saved_share_median == pytest.approx(0.225, abs=1e-4)
    # Worst decile of saved share: 10th percentile
    assert report.saved_share_worst_decile == pytest.approx(0.045, abs=1e-3)


def test_compress_and_measure_synthetic_image(tmp_path):
    test_img = Image.new("RGB", (200, 100), (120, 140, 160))
    exif = test_img.getexif()
    exif[0x0112] = 6  # Orientation: 6 (rotate 90 CW)
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

    # Ensure scratch dir is cleaned up (no temp files left)
    assert list(scratch_dir.iterdir()) == []

    # Downscale test: target size smaller than image
    small_level = SMALLEST
    m_small = compress_and_measure(img_path, small_level, temp_dir=scratch_dir)
    assert m_small.compressed_bytes > 0
    assert list(scratch_dir.iterdir()) == []


def test_compress_and_measure_downscaled(tmp_path):
    test_img = Image.new("RGB", (200, 200), (80, 120, 160))
    img_path = tmp_path / "orig_large.jpg"
    test_img.save(img_path, "JPEG", quality=95)

    custom_level = CompressionLevel("TestDownscale", quality=85, max_pixels=10_000)
    scratch_dir = tmp_path / "scratch"
    scratch_dir.mkdir()

    m = compress_and_measure(img_path, custom_level, temp_dir=scratch_dir)
    assert m.compressed_bytes > 0
    assert 0.80 <= m.ssim <= 1.0
    assert list(scratch_dir.iterdir()) == []


def test_format_report():
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
    )
    text = format_report([report])
    assert "High quality" in text
    assert "100" in text
    assert "0.9650" in text
    assert "0.9120" in text
    assert "38.4dB" in text
    assert "33.1dB" in text
    assert "45.2%" in text
    assert "10.5%" in text
    assert "95.4 / 190.7 MB" in text
