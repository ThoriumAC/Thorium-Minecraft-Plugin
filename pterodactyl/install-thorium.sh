# Thorium: fetch the latest plugin release and seed its config.
mkdir -p plugins/Thorium
api=https://api.github.com/repos/ThoriumAC/Thorium-Minecraft-Plugin/releases/latest
url=$(curl -sfL "$api" | jq -r '.assets[] | select(.name | test("^thorium-minecraft-[0-9.]+\\.jar$")) | .browser_download_url' | head -n1)
if [ -z "$url" ]; then
    echo "Thorium: could not resolve the latest release from GitHub" >&2
    exit 1
fi
rm -f plugins/thorium-minecraft-*.jar
curl -sfL -o "plugins/$(basename "$url")" "$url" || { echo "Thorium: download failed" >&2; exit 1; }
if [ ! -f plugins/Thorium/config.yml ]; then
    printf 'server-token: "%s"\nserver-name: "%s"\nenforce: true\n' "${THORIUM_TOKEN}" "${THORIUM_SERVER_NAME}" > plugins/Thorium/config.yml
fi
