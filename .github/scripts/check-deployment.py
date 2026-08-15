#!/usr/bin/env python3
"""Check that a Central Portal deployment contains every module we bundled.

    check-deployment.py <status.json> <bundle-modules-dir>

The upload returning 200 says nothing about what the deployment ended up
containing. 0.1.3 uploaded seven publications through the OSSRH staging bridge
and the resulting deployment held four: the js and both iOS modules were
accepted, never reported as failing, and simply were not there. By the time
that was visible in the portal the release was already publishing, and a
version on Central cannot be replaced.

So compare the two lists directly, and fail the build while failing is still
free.
"""
import json
import os
import sys


def artifact_id(purl):
    """pkg:maven/<group>/<artifactId>@<version>[?type=klib] -> <artifactId>."""
    return purl.split("/")[-1].split("@")[0]


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    status_path, bundle_dir = argv[1], argv[2]

    status = json.load(open(status_path, encoding="utf-8"))
    purls = status.get("purls") or []
    state = status.get("deploymentState", "?")

    print(f"Deployment {status.get('deploymentId', '?')} is {state}.")
    print("Components the Portal registered:")
    for p in sorted(purls):
        print("  " + p)

    got = {artifact_id(p) for p in purls}
    want = set(os.listdir(bundle_dir))
    if not want:
        print(f"::error::No modules under {bundle_dir} — this check would pass vacuously.")
        return 1

    missing = sorted(want - got)
    if missing:
        print(f"::error::The deployment is missing: {', '.join(missing)}")
        print(f"Bundled {len(want)} modules; the Portal registered {len(got)}.")
        print("Do not release this deployment — drop it and investigate.")
        return 1

    print(f"All {len(want)} bundled modules are present in the deployment.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
