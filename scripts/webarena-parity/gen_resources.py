"""Write the data tables OdoBot's Python-compatible helpers need, taken from the Python that WebArena's evaluators run on.

Run it in the WebArena environment (WSL), e.g.:

    ~/miniconda3/envs/webarena/bin/python -I gen_resources.py /mnt/c/.../odo-bot/src/main/resources/webarena

It writes py-html.json: CPython's html.entities.html5 table and the invalid character reference tables of
html.unescape, which ca.ualberta.odobot.task.webarena.py.PyHtml ports. The punkt tables in punkt_tab/english are copied
as they are from nltk_data/tokenizers/punkt_tab/english.
"""
import html
import html.entities
import json
import sys
from pathlib import Path


def main(out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    data = {
        "python": sys.version,
        "html5": html.entities.html5,
        "invalidCharrefs": {str(k): v for k, v in html._invalid_charrefs.items()},
        "invalidCodepoints": sorted(html._invalid_codepoints),
    }
    (out / "py-html.json").write_text(json.dumps(data, ensure_ascii=True, sort_keys=True, indent=0), encoding="utf-8")
    print(f"wrote {out / 'py-html.json'}: {len(data['html5'])} entities")


if __name__ == "__main__":
    main(sys.argv[1])
