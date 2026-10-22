#!/bin/ash
# PaperProxy install script (Pterodactyl). Downloads the PaperProxy jar from GitHub releases.
apk add --no-cache curl jq >/dev/null

mkdir -p /mnt/server
cd /mnt/server || exit 1

API="https://api.github.com/repos/LucasTHCRR/PaperProxy/releases"
RELEASE=/tmp/paperproxy-release.json
# Kept in a file: release notes contain escapes that "echo" would break in some shells.
if [ -z "${PAPERPROXY_VERSION}" ] || [ "${PAPERPROXY_VERSION}" = "latest" ]; then
  curl -fsSL "${API}?per_page=1" | jq '.[0]' > "${RELEASE}"
else
  curl -fsSL "${API}/tags/${PAPERPROXY_VERSION}" > "${RELEASE}"
fi
if [ ! -s "${RELEASE}" ] || [ "$(jq -r '.tag_name // empty' "${RELEASE}")" = "" ]; then
  echo "Release ${PAPERPROXY_VERSION} not found on GitHub."
  exit 1
fi

if [ "${OFFLINE_JAR}" = "1" ] || [ "${OFFLINE_JAR}" = "true" ]; then FULL=true; else FULL=false; fi
QUERY='[.assets[] | select(.name | test("^paperproxy-.*\\.jar$"))
              | select((.name | endswith("-full.jar")) == $full)][0]'
NAME=$(jq -r --argjson full "${FULL}" "${QUERY} | .name" "${RELEASE}")
URL=$(jq -r --argjson full "${FULL}" "${QUERY} | .browser_download_url" "${RELEASE}")
TAG=$(jq -r '.tag_name' "${RELEASE}")
if [ -z "${URL}" ] || [ "${URL}" = "null" ]; then
  echo "No PaperProxy jar found in release ${TAG}."
  exit 1
fi

echo "Downloading ${NAME} (${TAG})"
curl -fL -o "${SERVER_JARFILE}" "${URL}" || exit 1

# The small jar is published with a SHA-512 checksum; check it when available.
SHA_URL=$(jq -r --arg n "${NAME}.sha512" '.assets[] | select(.name == $n) | .browser_download_url' "${RELEASE}")
if [ -n "${SHA_URL}" ]; then
  EXPECTED=$(curl -fsSL "${SHA_URL}" | cut -d ' ' -f 1)
  ACTUAL=$(sha512sum "${SERVER_JARFILE}" | cut -d ' ' -f 1)
  if [ "${EXPECTED}" != "${ACTUAL}" ]; then
    echo "Checksum mismatch, removing the download."
    rm -f "${SERVER_JARFILE}"
    exit 1
  fi
  echo "Checksum verified."
fi

if [ "${FULL}" = "true" ]; then
  echo "PaperProxy installed (offline jar, no downloads needed on start)."
else
  echo "PaperProxy installed. Libraries are downloaded on the first start."
fi
