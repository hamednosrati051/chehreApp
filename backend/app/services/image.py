"""تصویر: OpenAI-compatible (مثل Sunburst از AvalAI) + ویرایش Gemini
از راه چت (provider_type=chat_image_edit_gemini).

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
    if resp.status_code >= 400:
        # قبلاً raise_for_status بدنه‌ی پاسخ را دور می‌انداخت و اپ فقط
        # «Client Error» نشان می‌داد؛ حالا پیام واقعی provider در
        # error_message نسل ذخیره می‌شود تا دلیل ۴۰۰ معلوم باشد.
        raise RuntimeError(f"provider http {resp.status_code}: {resp.text[:500]}")
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


def _edit_via_chat(model_row, image_path, prompt):
    """ویرایش با مدل‌های Gemini از راه v1/chat/completions (مثل نورا).

    پیام چندرسانه‌ای: متن پرامپت + عکس ورودی (base64)؛ خروجی در
    choices[0].message.images[0].image_url.url برمی‌گردد (data URL).
    شکل خروجی مثل _call است تا worker بدون تغییر کار کند.
    """
    import base64

    api_key, base_url, model_name = _model_creds(model_row)
    with open(image_path, "rb") as f:
        raw = f.read()
    mime = "image/png" if raw[:8] == b"\x89PNG\r\n\x1a\n" else "image/jpeg"
    image_b64 = base64.b64encode(raw).decode("ascii")
    resp = requests.post(
        f"{base_url}/chat/completions",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": model_name,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:{mime};base64,{image_b64}"
                            },
                        },
                    ],
                }
            ],
            "modalities": ["image", "text"],
        },
        timeout=300,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"provider http {resp.status_code}: {resp.text[:500]}")
    payload = resp.json()
    message = (payload.get("choices") or [{}])[0].get("message") or {}
    images = message.get("images") or []
    url = ""
    if images:
        url = (images[0].get("image_url") or {}).get("url") or ""
    if not url:
        raise RuntimeError("unexpected chat image-edit response")
    result = {}
    if url.startswith("data:"):
        b64 = url.split(",", 1)[1] if "," in url else ""
        if not b64:
            raise RuntimeError("unexpected chat image-edit response")
        result["b64_json"] = b64
    else:
        result["url"] = url
    if payload.get("usage"):
        result["usage"] = payload["usage"]
    return result


def _edit_via_cloudflare(model_row, image_path, prompt):
    """ویرایش با Cloudflare Workers AI (FLUX.2 Klein 4B).

    ورودی باید کوچک‌تر از 512×512 باشد، پس سلفی اول کوچک می‌شود؛
    پاسخ JSON با تصویر base64 است (نه بایت خام)؛ خروجی مثل _call به
    شکل b64_json برمی‌گردد تا worker بدون تغییر کار کند.
    """
    import base64
    import io

    from PIL import Image

    api_key, base_url, model_name = _model_creds(model_row)
    with Image.open(image_path) as im:
        im = im.convert("RGB")
        w, h = im.size
        scale = min(1.0, 512 / max(w, h))
        if scale < 1.0:
            im = im.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=90)
        data = buf.getvalue()
    resp = requests.post(
        f"{base_url}/{model_name}",
        headers={"Authorization": f"Bearer {api_key}"},
        files={"input_image_0": ("input.jpg", data, "image/jpeg")},
        data={"prompt": "Use image 0 as the person. " + prompt},
        timeout=300,
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"provider http {resp.status_code}: {resp.text[:500]}")
    ctype = resp.headers.get("content-type") or ""
    if ctype.startswith("image/"):
        return {"b64_json": base64.b64encode(resp.content).decode("ascii")}
    payload = resp.json()
    b64 = (payload.get("result") or {}).get("image")
    if not b64:
        raise RuntimeError("unexpected cloudflare image response")
    return {"b64_json": b64}


def edit_image(model_row, image_path, prompt):
    """سلفی + پرامپت مخفی دنیا → تصویر سبک‌دار.

    مدل‌های Gemini (provider_type=chat_image_edit_gemini) از مسیر چت
    می‌روند؛ Cloudflare (provider_type=cloudflare_workers_ai) از
    Workers AI؛ بقیه (openai_compat) از /images/edits مثل قبل.
    """
    if (model_row.provider_type or "") == "cloudflare_workers_ai":
        return _edit_via_cloudflare(model_row, image_path, prompt)
    if (model_row.provider_type or "") == "chat_image_edit_gemini":
        return _edit_via_chat(model_row, image_path, prompt)
    _, _, model_name = _model_creds(model_row)
    with open(image_path, "rb") as f:
        return _call(
            model_row,
            files={"image": f},
            data={
                "model": model_name,
                "prompt": prompt,
                "quality": "medium",
                # moderation ثابت low — تصمیم مالک (۱۴۰۴-۰۸-۰۵) برای ردهای ایمنی.
                "moderation": "low",
            },
        )


def generate_image(model_row, prompt):
    """تولید بدون تصویر مرجع (در صورت نیاز؛ کیفیت medium)."""
    _, _, model_name = _model_creds(model_row)
    return _call(
        model_row,
        files={},
        data={
            "model": model_name,
            "prompt": prompt,
            "quality": "medium",
            # moderation ثابت low — مثل edit_image.
            "moderation": "low",
        },
    )
