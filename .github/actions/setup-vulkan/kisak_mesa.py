"""Fetches Mesa's lavapipe packages of one kisak-mesa PPA build from Launchpad, with what they need, for setup-vulkan.

Usage: kisak_mesa.py MESA LIBDRM SERIES OUT, e.g. 26.2.6~kisak1 2.4.134-3~kisak resolute /tmp/mesa.

Mesa's packages and the libdrm they need come from Launchpad as files of the exact builds asked for (Launchpad keeps
them after the PPA publishes newer ones), not through apt: the runner's apt sees the PPA only for its amd64v3 variant,
and the image's own Mesa packages pin the installed Mesa. Whatever else they need that the runner lacks or has too old
is downloaded with `apt-get download` from the runner's sources. Nothing is installed: setup-vulkan unpacks them.
"""
import glob
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

API = "https://api.launchpad.net/devel/~kisak/+archive/ubuntu/kisak-mesa"
BUILDS = {  # source: the binaries of it lavapipe needs
    "mesa": {"mesa-vulkan-drivers", "mesa-libgallium"},
    "libdrm": {"libdrm2", "libdrm-amdgpu1", "libdrm-intel1", "libdrm-common"},
}


def get(url):
    with urllib.request.urlopen(url) as response:
        return json.load(response)


def collection(url):
    while url:
        page = get(url)
        yield from page["entries"]
        url = page.get("next_collection_link")


def fetch(source_name, version, series, out):
    """Downloads the amd64 binaries of {@code source_name}'s build {@code version} for {@code series}."""
    query = urllib.parse.urlencode({"ws.op": "getPublishedSources", "source_name": source_name, "exact_match": "true",
                                    "distro_series": "https://api.launchpad.net/devel/ubuntu/" + series})
    source = next((s for s in collection(API + "?" + query)
                   if s["source_package_version"].startswith(version + "~")), None)
    if source is None:
        sys.exit(f"::error::kisak-mesa has no build of {source_name} {version} for {series}")
    missing = set(BUILDS[source_name])
    for binary in collection(source["self_link"] + "?ws.op=getPublishedBinaries"):
        name = binary["binary_package_name"]
        if name in missing and binary["distro_arch_series_link"].endswith("/amd64"):
            for url in get(binary["self_link"] + "?ws.op=binaryFileUrls"):
                file = url.rsplit("/", 1)[1]
                print("fetching", file)
                urllib.request.urlretrieve(url, os.path.join(out, file))
            missing.discard(name)
    missing -= {"libdrm-common"}  # architecture-independent: not under amd64, and the runner's does
    if missing:
        sys.exit(f"::error::kisak-mesa {source_name} {source['source_package_version']} lacks {sorted(missing)}")
    print(source_name, source["source_package_version"])


def field(deb, name):
    return subprocess.run(["dpkg-deb", "-f", deb, name], capture_output=True, text=True).stdout.strip()


def satisfied(package, relation):
    """Whether the runner has {@code package} installed, at a version meeting {@code relation} (e.g. '>= 2.4')."""
    installed = subprocess.run(["dpkg-query", "-W", "-f=${Version}", package], capture_output=True, text=True)
    if installed.returncode != 0 or not installed.stdout:
        return False
    if not relation:
        return True
    op, version = relation.split()
    op = {">=": "ge", "<=": "le", ">>": "gt", "<<": "lt", "=": "eq"}[op]
    return subprocess.run(["dpkg", "--compare-versions", installed.stdout, op, version]).returncode == 0


def needs(out):
    """The packages the debs in {@code out} depend on that neither they nor the runner provide well enough."""
    debs = glob.glob(os.path.join(out, "*.deb"))
    have = {field(deb, "Package"): field(deb, "Version") for deb in debs}
    wanted = set()
    for deb in debs:
        for clause in field(deb, "Depends").split(","):
            choices = []
            for choice in clause.split("|"):
                choice = choice.strip()
                if not choice:
                    continue
                name = choice.split()[0].split(":")[0]
                relation = choice[choice.index("(") + 1:choice.index(")")] if "(" in choice else ""
                choices.append((name, relation))
            if not choices or any(name in have or satisfied(name, relation) for name, relation in choices):
                continue
            wanted.add(choices[0][0])
    return wanted


def main(mesa, libdrm, series, out):
    fetch("mesa", mesa, series, out)
    fetch("libdrm", libdrm, series, out)
    for _ in range(5):  # what they need, and what that needs
        wanted = needs(out)
        if not wanted:
            return
        print("downloading", " ".join(sorted(wanted)))
        subprocess.run(["apt-get", "download", *sorted(wanted)], cwd=out, check=True)
    sys.exit("::error::Mesa's dependencies did not settle")


if __name__ == "__main__":
    main(*sys.argv[1:5])
