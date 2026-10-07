#!/usr/bin/env python3
"""Single-writer Maven upload; no Gradle, deletion, force or resume/overwrite mode."""
import argparse
import base64
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

from contract import FORKS, HOSTS, coordinate, digest, files, require, safe_name, smoke_gates
from pipeline import live_main, require_trusted_event, verify_bundle

ENDPOINT = "https://maven.pkg.github.com/stream29/kodex"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward Basic credentials to another host or HTTP.
        return None


class MavenHTTP:
    def __init__(self, endpoint, actor, token):
        self.endpoint = endpoint.rstrip("/")
        self.opener = urllib.request.build_opener(NoRedirect())
        basic = base64.b64encode(f"{actor}:{token}".encode()).decode()
        self.headers = {"Authorization": "Basic " + basic, "User-Agent": "kodex-fork-packages/1"}

    def request(self, method, path, data=None):
        path = safe_name(path)
        headers = dict(self.headers)
        if method == "PUT":
            headers.update({"Content-Type": "application/octet-stream", "If-None-Match": "*"})
        request = urllib.request.Request(f"{self.endpoint}/{path}", data=data,
                                         headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                body = response.read(512 * 1024 * 1024 + 1)
                require(len(body) <= 512 * 1024 * 1024, "Remote artifact exceeds bounded size")
                return body
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            if method == "GET" and code == 404:
                return None
            # Do not echo response bodies/URLs/headers or credential-bearing exception text.
            raise RuntimeError(f"Maven {method} failed (HTTP {code}); no overwrite attempted") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise RuntimeError(f"Maven {method} transport failed; remote state must be re-audited") from None

    def get(self, path):
        return self.request("GET", path)

    def put(self, path, data):
        return self.request("PUT", path, data)


def upload_set(bundle, fork, identity_sha256=None):
    doc, raw = verify_bundle(bundle, fork, identity_sha256)
    # Do not upload rolling artifact-level maven-metadata.xml: versions are exact, never dynamic.
    result = dict(raw)
    for path, data in raw.items():
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            result[path + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
    anchor = sorted(FORKS[fork]["modules"])[0]
    version = doc["identity"]["version"]
    manifest_path = (coordinate(FORKS[fork], anchor, version) +
                     f"/{anchor}-{version}-kodex-manifest.json")
    result[manifest_path] = (bundle / "manifest.json").read_bytes()
    for algorithm in ("sha256", "sha512"):
        result[manifest_path + "." + algorithm] = (
            hashlib.new(algorithm, result[manifest_path]).hexdigest()).encode()
    return result, manifest_path


def verify_receipts(directory, fork, bundle, identity_sha256):
    raw = files(directory)
    require(set(raw) == {f"{host}.json" for host in HOSTS}, "Missing/unexpected host smoke receipt")
    manifest_sha = digest((bundle / "manifest.json").read_bytes())
    for host in HOSTS:
        doc = json.loads(raw[f"{host}.json"])
        gates = smoke_gates(fork, host)
        require(doc == {
            "schema": 1, "fork": fork, "host": host, "tasks": gates["tasks"],
            "manifestSha256": manifest_sha, "identitySha256": identity_sha256,
            "runtime": gates["runtime"], "compileOnly": gates["compileOnly"],
        }, "Smoke receipt does not match the validated bundle/gates")


def classify(client, expected):
    observed = {path: client.get(path) for path in expected}
    found = {path: data for path, data in observed.items() if data is not None}
    if not found:
        return "absent"
    if len(found) == len(expected) and all(found[p] == b for p, b in expected.items()):
        return "complete-exact"
    # Exact content is required, not just a matching version string or present marker.
    raise RuntimeError(
        "Existing version is PARTIAL OR DIFFERENT. No files were changed. "
        "Keep consumer pin unchanged; retain the validated bundle and audit the remote "
        "version with a maintainer. Repair/removal requires separate approval, or use "
        "a new reviewed fork commit/version. This publisher never deletes or resumes it.")


def publish(client, expected, manifest_path, check_head, wait=time.sleep):
    check_head()
    state = classify(client, expected)
    if state == "complete-exact":
        check_head()
        return state
    # Metadata redirects and POMs become visible last, but Maven is NOT transactional.
    def rank(path):
        if path.startswith(manifest_path):
            return 3
        if ".module" in path or ".pom" in path:
            return 2
        return 1
    ordered = sorted(expected, key=lambda path: (rank(path), path))
    last_check = time.monotonic()
    try:
        for path in ordered:
            if time.monotonic() - last_check >= 30:
                check_head()
                last_check = time.monotonic()
            current = client.get(path)
            # Some Maven registries generate sidecars during the payload PUT.
            # Accept only byte-identical content; never PUT over a present file.
            if current is not None:
                require(current == expected[path], "Version changed after preflight; stopping without overwrite")
                continue
            client.put(path, expected[path])
        # Allow bounded eventual visibility; verify actual remote bytes, not SHA response headers.
        for attempt in range(6):
            check_head()
            if all(client.get(path) == data for path, data in expected.items()):
                check_head()
                return "complete-remote-verified"
            if attempt < 5:
                wait(10)
        raise RuntimeError("Remote byte verification did not converge")
    except Exception:
        raise RuntimeError(
            "Publication interrupted/failed: remote version MAY BE PARTIAL. "
            "No deletion, resume or overwrite performed. Keep consumer pin unchanged; "
            "retain this bundle and inspect remote files before separately approved remediation."
        ) from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fork", choices=FORKS, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--identity-sha256", required=True)
    parser.add_argument("--receipts", type=Path, required=True)
    args = parser.parse_args()
    require(os.environ.get("GITHUB_ACTIONS") == "true" and
            os.environ.get("GITHUB_REPOSITORY") == "Stream29/Kodex" and
            os.environ.get("GITHUB_REF") == "refs/heads/main" and
            os.environ.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch"),
            "Only the authorized main CI publish job may write packages")
    require_trusted_event()
    token = os.environ.get("GITHUB_TOKEN")
    actor = os.environ.get("GITHUB_ACTOR")
    require(token and actor, "Missing repository token/actor")
    verify_receipts(args.receipts, args.fork, args.bundle, args.identity_sha256)
    expected, manifest_path = upload_set(args.bundle, args.fork, args.identity_sha256)
    client = MavenHTTP(ENDPOINT, actor, token)
    state = publish(client, expected, manifest_path, lambda: live_main(os.environ["GITHUB_SHA"]))
    print(f"{state}: {len(expected)} immutable files checked; manifest SHA-256 "
          f"{digest(expected[manifest_path])}. Consumer pin must be updated separately.")


if __name__ == "__main__":
    main()
