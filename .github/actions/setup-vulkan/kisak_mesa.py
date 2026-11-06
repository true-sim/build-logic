"""Fetches Mesa's lavapipe packages of one kisak-mesa PPA build from Launchpad, for setup-vulkan.

Usage: kisak_mesa.py VERSION SERIES OUT, e.g. 26.2.6~kisak1 resolute /tmp/mesa. Launchpad keeps a build's files after
the PPA publishes a newer one, so the version stays exactly the one asked for.
"""
import json
import sys
import urllib.parse
import urllib.request

API = "https://api.launchpad.net/devel/~kisak/+archive/ubuntu/kisak-mesa"
PACKAGES = {"mesa-vulkan-drivers", "mesa-libgallium"}


def get(url):
    with urllib.request.urlopen(url) as response:
        return json.load(response)


def binaries(url):
    while url:
        page = get(url)
        yield from page["entries"]
        url = page.get("next_collection_link")


def main(version, series, out):
    query = urllib.parse.urlencode({"ws.op": "getPublishedSources", "source_name": "mesa", "exact_match": "true",
                                    "distro_series": "https://api.launchpad.net/devel/ubuntu/" + series})
    sources = get(API + "?" + query)["entries"]
    source = next((s for s in sources if s["source_package_version"].startswith(version + "~")), None)
    if source is None:
        sys.exit(f"::error::kisak-mesa has no build of Mesa {version} for {series}")
    missing = set(PACKAGES)
    for binary in binaries(source["self_link"] + "?ws.op=getPublishedBinaries"):
        name = binary["binary_package_name"]
        if name in PACKAGES and binary["distro_arch_series_link"].endswith("/amd64"):
            for url in get(binary["self_link"] + "?ws.op=binaryFileUrls"):
                file = url.rsplit("/", 1)[1]
                print("fetching", file)
                urllib.request.urlretrieve(url, out + "/" + file)
            missing.discard(name)
    if missing:
        sys.exit(f"::error::kisak-mesa {source['source_package_version']} lacks {sorted(missing)}")
    print("Mesa", source["source_package_version"])


if __name__ == "__main__":
    main(*sys.argv[1:4])
