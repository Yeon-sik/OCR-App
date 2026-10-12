from pathlib import Path


root = Path(__file__).resolve().parent.parent
defaults = {
    "NUTRITION_SUPABASE_URL": "https://nutrition.invalid",
    "NUTRITION_SUPABASE_ANON_KEY": "ci-public-key-placeholder",
    "EMAIL": "ci@example.invalid",
    "PASSWORD": "ci-password-placeholder",
    "GEMINI_API_KEY": "ci-gemini-key-placeholder",
    "GEMINI_MODEL": "gemini-3.5-flash-lite",
}
lines = []
found = set()
for line in (root / ".env.example").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        key = key.strip()
        if key in defaults:
            found.add(key)
            if not value.strip().strip("\"'"):
                line = f"{key}={defaults[key]}"
    lines.append(line)
if missing := defaults.keys() - found:
    raise RuntimeError(f"CI template is missing required keys: {sorted(missing)}")
(root / ".env").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Prepared non-secret CI configuration from .env.example")
