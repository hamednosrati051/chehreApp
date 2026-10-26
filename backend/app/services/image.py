"""تصویر OpenAI-compatible (مثل Sunburst از AvalAI).

مشخصات مدل (base_url، کلید، نام مدل) از ردیف app_models می‌آید —
یعنی از بخش «مدل‌ها»ی زیرشاخه‌ی ادمین خود اپ، نه از نورا.
"""

import requests


def _model_creds(model_row):
    """مشخصات اتصال مدل، مثل نورا: اول ‎__provider__‎، بعد fallback قدیمی.

    (ردیف‌های قدیمی کلید را تخت در config_json و base_url را در ستون
    دارند؛ هر دو هنوز خوانده می‌شوند تا مهاجرت بی‌خطر باشد.)
    """
    cfg = model_row.config_json or {}
    prov = cfg.get("__provider__") or {}
    api_key = prov.get("api_key") or cfg.get("api_key") or ""
    base_url = (prov.get("base_url") or model_row.base_url or "https://api.avalai.ir/v1").rstrip("/")
    model_name = model_row.provider_model_name or model_row.name
    return api_key, base_url, model_name


def _call(model_row, files, data):
    api_key, base_url, _ = _model_creds(model_row)
    resp = requests.post(
        f"{base_url}/images/edits",
        headers={"Authorization": f"Bearer {api_key}"},
        files=files,
        data=data,
        timeout=300,
    )
    resp.raise_for_status()
    payload = resp.json()
    item = payload["data"][0]
    result = {}
    if item.get("url"):
        result["url"] = item["url"]
    if item.get("b64_json"):
        # worker آن را در فضای ذخیره‌سازی خود اپ آپلود و URL می‌کند.
        result["b64_json"] = item["b64_json"]
    if not result:
        raise RuntimeError("unexpected image-edit response")
    # usage (اگر provider برگرداند) برای لاگ هزینه‌ی واقعی در worker.
    if payload.get("usage"):
        result["usage"] = payload["usage"]
    return result


def edit_image(model_row, image_path, prompt):
    """سلفی + پرامپت مخفی دنیا → تصویر سبک‌دار (کیفیت medium، تصمیم ثابت)."""
    _, _, model_name = _model_creds(model_row)
    with open(image_path, "rb") as f:
        return _call(
            model_row,
            files={"image": f},
            data={
                "model": model_name,
                "prompt": prompt,
                "quality": "medium",
            },
        )


def generate_image(model_row, prompt):
    """تولید بدون تصویر مرجع (در صورت نیاز؛ کیفیت medium)."""
    _, _, model_name = _model_creds(model_row)
    return _call(
        model_row,
        files={},
        data={"model": model_name, "prompt": prompt, "quality": "medium"},
    )
