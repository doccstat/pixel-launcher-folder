"""Keep the newest successful Actions candidate; never modify published releases."""

import json
import os
import re
import subprocess
import sys

CANDIDATE = re.compile(r"([1-9][0-9]*)-(.+-build\.[1-9][0-9]*)")
ASSETS = ("PixelLauncherFolders.apk", "PixelLauncherFolders.apk.sha256")


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def api(endpoint, *args):
    return json.loads(gh("api", endpoint, *args))


def managed(release):
    match = CANDIDATE.fullmatch(release["tag_name"])
    return bool(match and release.get("author", {}).get("login") == "github-actions[bot]"
                and release.get("name") == match[2] and release.get("prerelease"))


def refresh(repo, tag, title, sha, directory="dist"):
    match = CANDIDATE.fullmatch(tag)
    if not match or match[2] != title:
        raise ValueError("Invalid candidate tag/title")
    code = int(match[1])
    pages = api(f"repos/{repo}/releases?per_page=100", "--paginate", "--slurp")
    releases = [release for page in pages for release in page]
    # Out-of-order runs and reruns must not roll the draft back or touch a
    # published candidate. Include published candidates in this comparison.
    for release in releases:
        if managed(release):
            old_code = int(CANDIDATE.fullmatch(release["tag_name"])[1])
            if old_code > code or (old_code == code and not release["draft"]):
                print("A newer or already-published candidate exists; leaving releases untouched.")
                return
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    if existing and (not existing["draft"] or not managed(existing)):
        raise ValueError("Refusing to overwrite a published or unrelated release")
    options = ("--repo", repo, "--target", sha, "--title", title,
               "--notes-file", f"{directory}/release-notes.md", "--draft", "--prerelease")
    assets = [f"{directory}/{name}" for name in ASSETS]
    if existing:
        gh("release", "edit", tag, *options)
        gh("release", "upload", tag, "--repo", repo, "--clobber", *assets)
    else:
        gh("release", "create", tag, *options, *assets)
    # Only clean up after both replacement assets were uploaded successfully.
    pages = api(f"repos/{repo}/releases?per_page=100", "--paginate", "--slurp")
    releases = [release for page in pages for release in page]
    current = next(r for r in releases if r["tag_name"] == tag)
    if not set(ASSETS).issubset({a["name"] for a in current.get("assets", [])}):
        raise ValueError("Replacement assets missing; retaining previous drafts")
    for release in releases:
        if not managed(release) or not release["draft"]:
            continue
        if int(CANDIDATE.fullmatch(release["tag_name"])[1]) >= code:
            continue
        # Recheck to avoid deleting a draft the operator has since published.
        endpoint = f"repos/{repo}/releases/{release['id']}"
        latest = api(endpoint)
        if latest["draft"] and managed(latest) and latest["tag_name"] == release["tag_name"]:
            gh("api", endpoint, "--method", "DELETE")
            print(f"Removed superseded draft {release['tag_name']}")
    print(f"Current candidate: {tag}")


if __name__ == "__main__":
    refresh(os.environ["GITHUB_REPOSITORY"], *sys.argv[1:])
