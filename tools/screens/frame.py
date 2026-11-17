#!/usr/bin/env python3
"""The listing's images (fastlane/metadata/android/en-US/images, plans/android-release-1.0.md T9), drawn by headless
Chrome from HTML: each phone screenshot framed on the icon's blue with its headline, the feature graphic, and the
512-pixel icon from the launcher's own layers. The screenshots are taken on the test phone first (DEVELOPMENT.md,
"The listing's screenshots").

    tools/screens/frame.py <folder of screenshots>

The folder holds the phone's screenshots under the names in SHOTS (1080 × 2400). Screenshots are 1080 × 1920: Google
Play takes no screenshot longer than twice its width, and the same files serve F-Droid and IzzyOnDroid."""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
IMAGES = ROOT / "fastlane/metadata/android/en-US/images"
DRAWABLE = ROOT / "app/src/main/res/drawable"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# File, headline, the line under it, and rows of the screenshot to leave out (a browser's address bar).
SHOTS = [
    ("1-list.png", "Your passwords, easy to read", "A KeePass password manager for Android", None),
    ("2-card.png", "A login and its code", "The one-time code counts down beside it", None),
    ("3-card.png", "Cards look like cards", "Bank accounts, IDs, Wi-Fi and SSH logins too", None),
    ("4-wallet.png", "Recovery phrase: checked, never copied", "Each word checked against BIP-39", None),
    ("5-autofill.png", "Autofill in apps and browsers", "And “Save this password?” after you sign in", (118, 296)),
    ("6-codes.png", "All your 2FA codes in one place", "Each with the next one ready", None),
    ("7-share.png", "Share entries, end-to-end encrypted", "Checked with six pictures, no server involved", None),
    ("8-sync.png", "Syncthing made two versions?", "See both and pick one on the phone", None),
]

FONT = "-apple-system, 'SF Pro Display', 'Helvetica Neue', sans-serif"
BLUE = "linear-gradient(160deg, #5A93FF 0%, #2F63E0 48%, #16358C 100%)"


def render(html: str, out: Path, width: int, height: int):
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False) as page:
        page.write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--allow-file-access-from-files", f"--window-size={width},{height}", f"--screenshot={out}",
                    Path(page.name).as_uri()], check=True, capture_output=True)
    Path(page.name).unlink()
    print(f"wrote {out.relative_to(ROOT)}")


def screen(image: Path, cut) -> str:
    """The screenshot at 840 pixels wide, a cut's rows left out; its foot runs off the frame."""
    scale = 840 / 1080
    if cut is None:
        return f'<img src="{image.as_uri()}" style="display:block;width:840px">'
    top, bottom = cut
    return (f'<div style="height:{top * scale:.0f}px;overflow:hidden"><img src="{image.as_uri()}" style="display:block;width:840px"></div>'
            f'<div style="overflow:hidden"><img src="{image.as_uri()}" style="display:block;width:840px;margin-top:-{bottom * scale:.0f}px"></div>')


def frame(image: Path, headline: str, line: str, cut) -> str:
    return f"""<!doctype html><meta charset="utf-8"><style>
body {{ margin: 0; width: 1080px; height: 1920px; overflow: hidden; background: {BLUE}; font-family: {FONT}; }}
h1 {{ margin: 0; padding: 112px 80px 0; color: #fff; font-size: 68px; line-height: 1.12; font-weight: 700;
      letter-spacing: -0.5px; text-align: center; text-wrap: balance; }}
p {{ margin: 22px 80px 0; color: rgba(255,255,255,.82); font-size: 36px; line-height: 1.3; text-align: center; text-wrap: balance; }}
.phone {{ position: absolute; left: 110px; top: 470px; width: 840px; border-radius: 58px; overflow: hidden;
          outline: 14px solid #0b1638; box-shadow: 0 30px 80px rgba(5,14,45,.55); }}
</style><h1>{headline}</h1><p>{line}</p><div class="phone">{screen(image, cut)}</div>"""


def svg_layers() -> str:
    """The launcher's background and foreground (tools/make-icon.py's vector drawables) as one SVG body."""
    def body(xml: str) -> str:
        xml = re.sub(r"<\?xml.*?\?>|<!--.*?-->", "", xml, flags=re.S)
        xml = re.sub(r'<group android:pivotX="([\d.]+)" android:pivotY="([\d.]+)" android:rotation="([\d.]+)"\s+'
                     r'android:translateX="([-\d.]+)" android:translateY="([-\d.]+)">',
                     r'<g transform="translate(\4 \5) rotate(\3 \1 \2)">', xml).replace("</group>", "</g>")
        xml = re.sub(r'<path android:pathData="([^"]+)" android:fillColor="#FF(\w+)" />', r'<path d="\1" fill="#\2"/>', xml)
        xml = re.sub(r'<path android:pathData="([^"]+)" android:strokeColor="#FF(\w+)" android:strokeWidth="([\d.]+)"\s+'
                     r'android:strokeLineCap="round" android:strokeLineJoin="round" />',
                     r'<path d="\1" fill="none" stroke="#\2" stroke-width="\3" stroke-linecap="round" stroke-linejoin="round"/>', xml)
        return re.search(r"<vector[^>]*>(.*)</vector>", xml, re.S).group(1)
    gradient = ('<linearGradient id="bg" x1="54" y1="0" x2="54" y2="108" gradientUnits="userSpaceOnUse">'
                '<stop offset="0" stop-color="#5A93FF"/><stop offset=".5" stop-color="#2F63E0"/><stop offset="1" stop-color="#16358C"/>'
                '</linearGradient>')
    return f'<defs>{gradient}</defs><rect width="108" height="108" fill="url(#bg)"/>' + body((DRAWABLE / "ic_launcher_foreground.xml").read_text())


def icon() -> str:
    """Full bleed, as Google Play asks (it rounds the corners itself); the adaptive icon's visible 72 of 108."""
    return (f'<!doctype html><style>body{{margin:0}}</style>'
            f'<svg width="512" height="512" viewBox="18 18 72 72">{svg_layers()}</svg>')


def feature_graphic() -> str:
    return f"""<!doctype html><meta charset="utf-8"><style>
body {{ margin: 0; width: 1024px; height: 500px; overflow: hidden; background: {BLUE}; font-family: {FONT};
        display: flex; align-items: center; gap: 56px; padding: 0 84px; box-sizing: border-box; color: #fff; }}
svg {{ flex: none; border-radius: 64px; box-shadow: 0 18px 50px rgba(5,14,45,.45); }}
h1 {{ margin: 0; font-size: 92px; font-weight: 700; letter-spacing: -1px; }}
p {{ margin: 12px 0 0; font-size: 34px; line-height: 1.3; color: rgba(255,255,255,.86); }}
</style><svg width="280" height="280" viewBox="18 18 72 72">{svg_layers()}</svg>
<div><h1>Keething</h1><p>A KeePass password manager for Android.</p></div>"""


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    shots = Path(sys.argv[1]).resolve()
    missing = [name for name, *_ in SHOTS if not (shots / name).exists()]
    if missing:
        sys.exit(f"missing in {shots}: {', '.join(missing)}")
    for n, (name, headline, line, cut) in enumerate(SHOTS, start=1):
        render(frame(shots / name, headline, line, cut), IMAGES / f"phoneScreenshots/{n}.png", 1080, 1920)
    render(feature_graphic(), IMAGES / "featureGraphic.png", 1024, 500)
    render(icon(), IMAGES / "icon.png", 512, 512)


if __name__ == "__main__":
    main()
