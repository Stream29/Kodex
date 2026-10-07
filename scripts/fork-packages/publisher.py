#!/usr/bin/env python3
"""Single-writer Maven upload; no Gradle, deletion, force or resume/overwrite mode."""
import argparse
import base64
import hashlib
import http.client
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from contract import FORKS, HOSTS, coordinate, digest, files, require, safe_name, smoke_gates
from pipeline import live_main, require_trusted_event, verify_bundle

ENDPOINT = "https://maven.pkg.github.com/stream29/kodex"
STORAGE_HOST = "github-registry-files.githubusercontent.com"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def http_error_302(self, req, fp, code, msg, headers):
        # Do not let urllib parse/requote a signed Location before our predicate.
        # A malformed URL could otherwise escape with its query in a ValueError.
        raise urllib.error.HTTPError(req.full_url, code, msg, headers, fp)

    http_error_301 = http_error_302
    http_error_303 = http_error_302
    http_error_307 = http_error_302
    http_error_308 = http_error_302


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
        url = f"{self.endpoint}/{path}"
        for hop in range(4):
            try:
                request = urllib.request.Request(url, data=data, headers=headers, method=method)
                with self.opener.open(request, timeout=60) as response:
                    body = response.read(512 * 1024 * 1024 + 1)
                    require(len(body) <= 512 * 1024 * 1024, "Remote artifact exceeds bounded size")
                    return body
            except urllib.error.HTTPError as error:
                code = error.code
                location = error.headers.get("Location")
                error.close()
                if method == "GET" and code == 404 and hop == 0:
                    return None
                if method == "GET" and code in (301, 302, 303, 307, 308) and hop < 3:
                    try:
                        if not location or not all("!" <= c <= "~" for c in location):
                            raise ValueError("Malformed storage location")
                        target = urllib.parse.urljoin(url, location or "")
                        parsed = urllib.parse.urlsplit(target)
                        trusted = bool(location) and all("!" <= c <= "~" for c in target) and (
                            parsed.scheme == "https" and parsed.hostname == STORAGE_HOST
                            and parsed.port in (None, 443) and parsed.username is None
                            and parsed.password is None and not parsed.fragment
                        )
                    except ValueError:
                        trusted = False
                    if not trusted:
                        raise RuntimeError("Maven GET refused an unsafe storage redirect") from None
                    # Storage URLs are signed by GitHub. Never forward the Maven Basic
                    # credential, even on further redirects; PUTs never redirect.
                    headers = {"User-Agent": self.headers["User-Agent"]}
                    url = target
                    continue
                # Never echo bodies, signed URLs, headers or credential-bearing text.
                raise RuntimeError(f"Maven {method} failed (HTTP {code}); no overwrite attempted") from None
            except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException, ValueError):
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
    operation = "upload"
    path = None
    written = 0
    try:
        for path in ordered:
            if time.monotonic() - last_check >= 30:
                operation = "head-check"
                check_head()
                last_check = time.monotonic()
            operation = "read-before-upload"
            current = client.get(path)
            # Some Maven registries generate sidecars during the payload PUT.
            # Accept only byte-identical content; never PUT over a present file.
            if current is not None:
                require(current == expected[path], "Version changed after preflight; stopping without overwrite")
                continue
            operation = "upload"
            client.put(path, expected[path])
            written += 1
        # Allow bounded eventual visibility; verify actual remote bytes, not SHA response headers.
        for attempt in range(6):
            operation = "head-check"
            path = None
            check_head()
            operation = "verify"
            for path, data in expected.items():
                if client.get(path) != data:
                    break
            else:
                operation = "head-check"
                path = None
                check_head()
                return "complete-remote-verified"
            if attempt < 5:
                wait(10)
        raise RuntimeError("Remote byte verification did not converge")
    except Exception as error:
        raise RuntimeError(
            f"Publication interrupted/failed during {operation}, path={path}, "
            f"successful PUTs={written}: remote version MAY BE PARTIAL. "
            "No deletion, resume or overwrite performed. Keep consumer pin unchanged; "
            "retain this bundle and inspect remote files before separately approved remediation."
        ) from error


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
