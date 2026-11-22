#!/usr/bin/env python3
"""Generate an anime-style Anne Bonny icon for the Ani app."""

import torch
from diffusers import StableDiffusionXLPipeline

MODEL = "cagliostrolab/animagine-xl-3.1"
OUTPUT = "anne_bonny_icon.png"

PROMPT = (
    "1girl, masterpiece, best quality, absurdres, "
    "pirate, red hair, copper red hair, tricorn hat with skull crossbones, "
    "green eyes, serious, intense gaze, gold earring, "
    "portrait, upper body, head and shoulders, full hat visible, "
    "night, dark background, rain, noir, low key lighting"
)

NEGATIVE = (
    "lowres, bad anatomy, bad hands, text, error, missing fingers, "
    "extra digit, fewer digits, cropped, worst quality, low quality, "
    "normal quality, jpeg artifacts, signature, watermark, username, blurry, "
    "nsfw, nude, bright background, white background, daylight, sunny"
)

def main():
    print(f"Loading model: {MODEL}")
    pipe = StableDiffusionXLPipeline.from_pretrained(
        MODEL,
        torch_dtype=torch.float16,
        use_safetensors=True,
    )
    pipe.to("cuda")

    print("Generating image...")
    image = pipe(
        prompt=PROMPT,
        negative_prompt=NEGATIVE,
        width=1024,
        height=1024,
        num_inference_steps=28,
        guidance_scale=9.0,
        generator=torch.Generator("cuda").manual_seed(13),
    ).images[0]

    image.save(OUTPUT)
    print(f"Saved to {OUTPUT}")

if __name__ == "__main__":
    main()
