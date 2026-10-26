"""تصویر OpenAI-compatible (مثل Sunburst از AvalAI).

مشخصات مدل (base_url، کلید، نام مدل) از ردیف app_models می‌آید —
یعنی از بخش «مدل‌ها»ی زیرشاخه‌ی ادمین خود اپ، نه از نورا.
"""

import requests


def _call(model_row, files, data):
    cfg = model_row.config_json or {}
    api_key = cfg.get("api_key")
    base_url = (model_row.base_url or "https://api.avalai.ir/v1").rstrip("/")
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
    with open(image_path, "rb") as f:
        return _call(
            model_row,
            files={"image": f},
            data={
                "model": model_row.name,
                "prompt": prompt,
                "quality": "medium",
            },
        )


def generate_image(model_row, prompt):
    """تولید بدون تصویر مرجع (در صورت نیاز؛ کیفیت medium)."""
    return _call(
        model_row,
        files={},
        data={"model": model_row.name, "prompt": prompt, "quality": "medium"},
    )
