#!/usr/bin/env python3
"""Bump Swift packages referenced by an Xcode project to their newest release.

Dependabot doesn't handle XCRemoteSwiftPackageReference entries in .pbxproj
files, so this does it: for every GitHub-hosted package pinned with
exactVersion (or a minimumVersion range), find the newest stable x.y.z tag
whose commit is at least --cooldown-days old and rewrite the version in the
project. Package.resolved is regenerated afterwards by xcodebuild.

Prints one "name old -> new" line per change. Needs `git` and `gh` (for
commit dates) on PATH.
"""
import argparse, datetime, json, re, subprocess, sys

BLOCK = re.compile(
    r'(/\* XCRemoteSwiftPackageReference "(?P<name>[^"]+)" \*/ = \{.*?'
    r'repositoryURL = "?(?P<url>[^";]+)"?;.*?'
    r'requirement = \{(?P<req>.*?)\};)',
    re.S,
)
STABLE = re.compile(r'^v?(\d+)\.(\d+)\.(\d+)$')


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def parse_version(s):
    m = STABLE.match(s.strip())
    return tuple(map(int, m.groups())) if m else None


def tags(url):
    """{version tuple: (tag name, commit sha)} for stable tags."""
    out = {}
    for line in run("git", "ls-remote", "--tags", url).splitlines():
        sha, ref = line.split("\t")
        name = ref.removeprefix("refs/tags/")
        peeled = name.endswith("^{}")
        name = name.removesuffix("^{}")
        v = parse_version(name)
        if v and (peeled or v not in out):
            out[v] = (name, sha)  # peeled (^{}) entry = the commit, wins
    return out


def commit_date(repo, sha):
    iso = run("gh", "api", f"repos/{repo}/commits/{sha}", "--jq", ".commit.committer.date").strip()
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pbxproj")
    ap.add_argument("--cooldown-days", type=int, default=14)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    text = open(a.pbxproj).read()
    cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=a.cooldown_days)
    changes = []

    for m in BLOCK.finditer(text):
        name, url, req = m["name"], m["url"], m["req"]
        key = "version" if "kind = exactVersion;" in req else "minimumVersion"
        cur_m = re.search(rf'\b{key} = "?([^";]+)"?;', req)
        gh = re.match(r'https://github\.com/([^/]+/[^/.]+?)(?:\.git)?/?$', url)
        if not cur_m or not gh:
            print(f"skip {name}: unsupported requirement or non-GitHub URL", file=sys.stderr)
            continue
        cur = parse_version(cur_m[1])
        if not cur:
            print(f"skip {name}: can't parse version {cur_m[1]!r}", file=sys.stderr)
            continue

        target = None
        for v, (tag, sha) in sorted(tags(url).items(), reverse=True):
            if v <= cur:
                break
            if commit_date(gh[1], sha) <= cutoff:
                target = v
                break
            print(f"{name} {tag}: newer than {a.cooldown_days} days, waiting", file=sys.stderr)
        if not target:
            continue

        new = ".".join(map(str, target))
        new_req = req[:cur_m.start(1)] + new + req[cur_m.end(1):]
        block = m[1]
        text = text.replace(block, block.replace(req, new_req), 1)
        changes.append(f"{name} {cur_m[1]} -> {new}")

    if changes and not a.dry_run:
        open(a.pbxproj, "w").write(text)
    print("\n".join(changes))


if __name__ == "__main__":
    main()
