#!/usr/bin/env python3
"""Issue #36: isolated AI dev HTTP/demo/SQLite recovery smoke.

This never registers a package in BE, touches a BE database, or calls a paid model.
The registration receipt is an explicitly marked transport double.
Usage: python issue36-ai-http-smoke.py --repo ~/Desktop/finsight-ai --out ~/Desktop/issue36-ai-http-smoke-report.json
Requires the AI project's requirements-test.txt dependencies.
"""
import argparse
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

import httpx


def require(value, message):
    if not value:
        raise RuntimeError(message)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for(label, action, predicate, timeout=20):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = action()
            if predicate(last):
                return last
        except (httpx.HTTPError, ConnectionError) as ex:
            last = str(ex)
        time.sleep(.25)
    raise RuntimeError(f"Timeout waiting for {label}: {str(last)[:180]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", type=Path, required=True, help="AI repository with integration/ and runtime/")
    ap.add_argument("--out", type=Path, default=Path("issue36-ai-http-smoke-report.json"))
    args = ap.parse_args()
    repo = args.repo.expanduser().resolve()
    required = [repo / "integration/demo-package.json", repo / "integration/initial.request.json",
                repo / "integration/final.request.template.json", repo / "FinSight_AI_프롬프트_초안.md"]
    require(all(p.is_file() for p in required), "AI repository missing demo fixtures or prompt; check --repo")
    with tempfile.TemporaryDirectory(prefix="issue36-ai-demo-") as temp:
        tmp = Path(temp)
        env = {**os.environ,
               "PYTHONPATH": str(repo / "runtime"),
               "RAG_DATABASE": str(tmp / "jobs.sqlite3"),
               "RAG_CATALOG_DATABASE": str(tmp / "sources.sqlite3"),
               "RAG_PACKAGE": str(repo / "integration/demo-package.json"),
               "RAG_BE_TOKEN": secrets.token_urlsafe(42),
               "RAG_ADMIN_PASSWORD": secrets.token_urlsafe(42),
               "RAG_ADMIN_USER": "reviewer",
               "RAG_PROVIDER": "demo", "RAG_MODEL": "local-demo",
               "RAG_PROMPT_VERSION": "feedback-integration-v1",
               "RAG_POLICY_VERSION": "feedback-policy-p0-v1",
               "RAG_ALLOW_PAID_CALLS": "false"}
        env.pop("OPENAI_API_KEY", None)
        port = free_port()
        base = f"http://127.0.0.1:{port}"
        log_path = tmp / "server.log"
        current = None
        log_handle = log_path.open("w", encoding="utf-8")
        def start():
            nonlocal current
            current = subprocess.Popen(
                [sys.executable, "-m", "uvicorn", "finsight_rag.service:create_app", "--factory",
                 "--host", "127.0.0.1", "--port", str(port), "--workers", "1", "--no-access-log"],
                cwd=repo, env=env, stdout=log_handle, stderr=subprocess.STDOUT)
            wait_for("AI server startup", lambda: httpx.get(base + "/health/live", timeout=2),
                     lambda r: r.status_code == 200 and r.json().get("status") == "up")
        def stop():
            nonlocal current
            if current is not None:
                current.terminate()
                try: current.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    current.kill(); current.wait(timeout=5)
                current = None
        def call(method, route, *, auth=True, **kwargs):
            h = kwargs.pop("headers", {})
            if auth: h["Authorization"] = "Bearer " + env["RAG_BE_TOKEN"]
            return httpx.request(method, base + route, headers=h, timeout=10, **kwargs)
        def status(job_id):
            r = call("GET", f"/internal/v1/jobs/{job_id}")
            require(r.status_code == 200, f"job status HTTP {r.status_code}")
            return r.json()
        def reviewed(job_id):
            s = status(job_id)
            r = httpx.post(
                         base + f"/admin/api/jobs/{job_id}/review",
                         json={"approved": True, "generationOrdinal": s["generationOrdinal"],
                               "snapshotHash": s["ragSnapshotHash"], "notes": "isolated demo review"},
                         auth=("reviewer", env["RAG_ADMIN_PASSWORD"]),
                         headers={"Origin": base, "X-Review-Action": "1"}, timeout=10)
            require(r.status_code == 200, f"review HTTP {r.status_code}: {r.text[:160]}")
            return r.json()
        def result(job_id):
            r = call("GET", f"/internal/v1/jobs/{job_id}/result")
            require(r.status_code == 200, f"result HTTP {r.status_code}")
            return r.json()
        def delivery(job_id, state, h):
            return call("POST", f"/internal/v1/jobs/{job_id}/delivery",
                        json={"status": state, "resultHash": h, "notes": "synthetic BE transport double"})

        checks = []
        try:
            start()
            require(call("GET", "/health/ready", auth=False).status_code == 503, "ready before registration")
            require(call("GET", "/internal/v1/package", auth=False).status_code == 401, "BE bearer gate")
            pkg = call("GET", "/internal/v1/package").json()
            require(pkg["package"]["kind"] == "local_demo", "only local_demo allowed")
            reg = call("POST", "/internal/v1/package/registration", json={
                "registered": True, "inventory": pkg["inventory"],
                "receipt": "LOCAL_TRANSPORT_DOUBLE_NO_BE_DATABASE"})
            require(reg.status_code == 200, f"demo transport registration HTTP {reg.status_code}")
            require(call("GET", "/health/ready", auth=False).status_code == 200, "ready after demo registration")
            checks.append("auth and demo registration gate")

            initial = json.loads((repo / "integration/initial.request.json").read_text(encoding="utf-8"))
            initial["beRequestId"] = "feedback:initial:" + str(uuid4())
            initial["feedbackId"] = str(uuid4())
            initial["input"]["sessionId"] = str(uuid4())
            r = call("POST", "/internal/v1/jobs", json=initial)
            require(r.status_code == 202, f"INITIAL submit HTTP {r.status_code}: {r.text[:160]}")
            initial_id = r.json()["jobId"]
            dup = call("POST", "/internal/v1/jobs", json=initial)
            require(dup.status_code == 202 and dup.json()["jobId"] == initial_id, "INITIAL duplicate must reuse job")
            wait_for("INITIAL review", lambda: status(initial_id), lambda x: x["status"] == "awaiting_review")
            require(call("GET", f"/internal/v1/jobs/{initial_id}/result").status_code == 409,
                    "INITIAL content is private before approval")
            checks.append("INITIAL idempotence and approval privacy")

            final = json.loads((repo / "integration/final.request.template.json").read_text(encoding="utf-8"))
            final["initialJobId"] = initial_id
            final["beRequestId"] = "feedback:final:" + str(uuid4())
            final["feedbackId"] = str(uuid4())
            for k in ("sessionId", "news", "predictions", "submittedAt"):
                final["input"][k] = copy.deepcopy(initial["input"][k])
            r = call("POST", "/internal/v1/jobs", json=final)
            require(r.status_code == 202, f"FINAL submit HTTP {r.status_code}: {r.text[:160]}")
            final_id = r.json()["jobId"]
            wait_for("FINAL awaiting INITIAL", lambda: status(final_id),
                     lambda x: x["status"] == "awaiting_initial")
            reviewed(initial_id)
            wait_for("FINAL review", lambda: status(final_id), lambda x: x["status"] == "awaiting_review")
            require(call("GET", f"/internal/v1/jobs/{final_id}/result").status_code == 409,
                    "FINAL content is private before approval")
            reviewed(final_id)
            before_initial, before_final = result(initial_id), result(final_id)
            require(before_initial["resultHash"] != "" and before_final["resultHash"] != "", "result hashes")
            refs = before_initial["output"]["references"]
            require(len(refs) == 2 and refs[0]["chunkId"] == refs[1]["chunkId"]
                    and refs[0]["citationExcerpt"] != refs[1]["citationExcerpt"], "distinct citations from same chunk")
            checks.append("INITIAL to FINAL reviews and citation identity")

            h = before_initial["resultHash"]
            require(delivery(initial_id, "blocked", h).status_code == 200, "blocked delivery HTTP")
            require(call("GET", f"/internal/v1/jobs/{initial_id}/result").status_code == 409,
                    "blocked output must be private")
            require(delivery(initial_id, "stored", h).status_code == 200, "stored recovery HTTP")
            require(result(initial_id)["resultHash"] == h, "stored recovery must keep original result hash")
            require(delivery(initial_id, "stored", h).status_code == 200, "duplicate stored should be idempotent")
            require(delivery(initial_id, "blocked", h).status_code == 409, "late blocked must not regress stored")
            require(delivery(final_id, "stored", before_final["resultHash"]).status_code == 200,
                    "FINAL stored delivery")
            checks.append("blocked-to-stored and duplicate/late-delivery guard")

            state_before = {j: status(j) for j in (initial_id, final_id)}
            stop()
            start()
            require(call("GET", "/health/ready", auth=False).status_code == 200, "registration retained on restart")
            for job_id, previous in state_before.items():
                after = status(job_id)
                require(after["jobId"] == previous["jobId"]
                        and after["ragSnapshotHash"] == previous["ragSnapshotHash"]
                        and after["generationOrdinal"] == previous["generationOrdinal"]
                        and after["delivery"]["status"] == previous["delivery"]["status"],
                        "job state changed after process restart")
            require(result(initial_id)["resultHash"] == before_initial["resultHash"]
                    and result(final_id)["resultHash"] == before_final["resultHash"],
                    "result hash changed after process restart")
            checks.append("actual AI process restart and SQLite persistence")
            report = {"result": "PASS", "scope": "AI dev HTTP demo only; NO BE/MYSQL/FE and NO paid model",
                      "timestampUtc": datetime.now(timezone.utc).isoformat(),
                      "providerMode": "demo", "packageId": pkg["package"]["packageId"],
                      "initialJobId": initial_id, "finalJobId": final_id,
                      "initialResultHash": before_initial["resultHash"],
                      "finalResultHash": before_final["resultHash"], "checks": checks}
            out = args.out.expanduser().resolve()
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False, indent=2))
        except Exception as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            log_handle.flush()
            if log_path.exists():
                lines=log_path.read_text(encoding="utf-8", errors="replace").splitlines()
                print("AI log tail (review before sharing):", *lines[-8:], sep="\n", file=sys.stderr)
            raise
        finally:
            stop()
            log_handle.close()

if __name__ == "__main__":
    main()
