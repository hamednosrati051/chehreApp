"""اینترفیس تبدیل صدا — پیاده‌سازی بعد از انتخاب route.

حامد هنوز route بخش صدا را انتخاب نکرده
(RVC روی GPU اجاره‌ای / Kits.AI / ElevenLabs).
تا آن موقع این ماژول عمداً stub است و job صدا با پیام روشن fail می‌شود.
"""


class VoiceConverter:
    def convert(self, input_audio_path, model_config):
        raise NotImplementedError("voice route not selected yet")


def get_converter(provider):
    raise NotImplementedError(
        f"no voice converter implemented for provider={provider} yet"
    )
