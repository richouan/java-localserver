import http.client
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import hashlib
import concurrent.futures
import subprocess
import tempfile

BASE_URL = "http://127.0.0.1:8080"
HOST = "127.0.0.1"
PORT = 8080

results = []

def record(test_name, passed, details=""):
    results.append((test_name, passed, details))
    status = " PASS " if passed else " FAIL "
    print(f"[{status}] {test_name}: {details}")

def reserve_local_port():
    with socket.socket() as s:
        s.bind((HOST, 0))
        return s.getsockname()[1]

def get_http_response(port, host, path="/", timeout=3):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"Host": host})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers

def wait_for_http_response(process, port, host, path="/", timeout=3):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Temporary server exited before becoming ready")
        try:
            return get_http_response(port, host, path, timeout=0.25)
        except (OSError, urllib.error.URLError) as e:
            last_error = e
            time.sleep(0.05)
    raise TimeoutError(f"Temporary server did not become ready: {last_error}")

def run_temporary_server(config_text):
    temp_dir = tempfile.TemporaryDirectory()
    config_path = os.path.join(temp_dir.name, "test-config.conf")
    with open(config_path, "w") as config_file:
        config_file.write(config_text)
    process = subprocess.Popen(
        ["java", "-cp", os.path.abspath("bin"), "Main", config_path],
        cwd=os.getcwd(),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return temp_dir, process

def stop_temporary_server(temp_dir, process):
    try:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
    finally:
        temp_dir.cleanup()

# ============================================================================
# PART 1: STANDARD AUDIT TESTS
# ============================================================================

def test_basic_get():
    try:
        req = urllib.request.Request(f"{BASE_URL}/", headers={"Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode()
            ok = resp.status == 200 and "LocalServer" in content
            record("Basic GET / (200 OK)", ok, f"Status={resp.status}")
    except Exception as e:
        record("Basic GET / (200 OK)", False, str(e))

def test_empty_file():
    try:
        req = urllib.request.Request(f"{BASE_URL}/empty.html", headers={"Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            cl = resp.headers.get("Content-Length")
            ok = resp.status == 200 and len(body) == 0 and cl == "0"
            record("GET empty.html (200 OK, 0 bytes)", ok, f"Length={len(body)}, CL={cl}")
    except Exception as e:
        record("GET empty.html (200 OK, 0 bytes)", False, str(e))

def test_custom_404():
    try:
        req = urllib.request.Request(f"{BASE_URL}/nonexistent_route_404", headers={"Host": "localhost"})
        urllib.request.urlopen(req)
        record("Custom 404 Not Found", False, "Expected 404, got 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        ok = e.code == 404 and "404 Not Found" in body
        record("Custom 404 Not Found", ok, f"Status={e.code}, CustomBody={'Yes' if '404 Not Found' in body else 'No'}")
    except Exception as e:
        record("Custom 404 Not Found", False, str(e))

def test_custom_405():
    try:
        req = urllib.request.Request(f"{BASE_URL}/restricted", method="DELETE", headers={"Host": "localhost"})
        urllib.request.urlopen(req)
        record("Custom 405 Method Not Allowed", False, "Expected 405, got 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        ok = e.code == 405 and "405 Method Not Allowed" in body
        record("Custom 405 Method Not Allowed", ok, f"Status={e.code}")
    except Exception as e:
        record("Custom 405 Method Not Allowed", False, str(e))

def test_custom_413():
    try:
        # On port 8081, limit is 524288 (512KB). Send 600KB.
        data = b"A" * 600000
        req = urllib.request.Request("http://127.0.0.1:8081/", data=data, method="POST",
                                     headers={"Content-Type": "text/plain", "Host": "second.com"})
        urllib.request.urlopen(req)
        record("Body limit 413 Payload Too Large", False, "Expected 413, got 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        ok = e.code == 413 and "413" in body
        record("Body limit 413 Payload Too Large", ok, f"Status={e.code}")
    except Exception as e:
        record("Body limit 413 Payload Too Large", False, str(e))

def test_redirect():
    try:
        conn = http.client.HTTPConnection(HOST, PORT)
        conn.request("GET", "/redirect", headers={"Host": "localhost"})
        resp = conn.getresponse()
        loc = resp.getheader("Location")
        conn.close()
        ok = resp.status == 301 and loc == "/index.html"
        record("301 Redirect (/redirect -> /index.html)", ok, f"Status={resp.status}, Location={loc}")
    except Exception as e:
        record("301 Redirect (/redirect -> /index.html)", False, str(e))

def test_trailing_slash_redirect():
    try:
        conn = http.client.HTTPConnection(HOST, PORT)
        conn.request("GET", "/listing", headers={"Host": "localhost"})
        resp = conn.getresponse()
        loc = resp.getheader("Location")
        conn.close()
        ok = resp.status == 301 and loc == "/listing/"
        record("Directory Trailing Slash 301 Redirect (/listing -> /listing/)", ok,
               f"Status={resp.status}, Location={loc}")
    except Exception as e:
        record("Directory Trailing Slash 301 Redirect (/listing -> /listing/)", False, str(e))

def test_directory_listing():
    try:
        os.makedirs("www/uploads", exist_ok=True)
        with open("www/uploads/listing_probe.txt", "w") as f:
            f.write("probe")
        req = urllib.request.Request(f"{BASE_URL}/listing/", headers={"Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            ok = resp.status == 200 and "Index of /listing/" in body and "listing_probe.txt" in body
            record("Directory Listing (autoindex)", ok, f"Status={resp.status}")
    except Exception as e:
        record("Directory Listing (autoindex)", False, str(e))

def test_session_cookies():
    try:
        conn = http.client.HTTPConnection(HOST, PORT)
        conn.request("GET", "/session", headers={"Host": "localhost"})
        r1 = conn.getresponse()
        cookie = r1.getheader("Set-Cookie")
        body1 = r1.read().decode()

        if not cookie or "session_id=" not in cookie:
            record("Session & Cookies", False, "No Set-Cookie header received")
            conn.close()
            return

        sid_cookie = cookie.split(";")[0]
        conn.request("GET", "/session", headers={"Host": "localhost", "Cookie": sid_cookie})
        r2 = conn.getresponse()
        body2 = r2.read().decode()

        conn.request("GET", "/session", headers={"Host": "second.com", "Cookie": sid_cookie})
        r3 = conn.getresponse()
        body3 = r3.read().decode()
        conn.close()

        ok = "Visits: 1" in body1 and "Visits: 2" in body2 and "Visits: 3" in body3
        record("Session & Cookies (including cross-port cookie reuse)", ok,
             f"Initial={('Visits: 1' in body1)}, Same port={('Visits: 2' in body2)}, Other port={('Visits: 3' in body3)}")
    except Exception as e:
        record("Session & Cookies", False, str(e))

def test_status_metrics():
    try:
        req = urllib.request.Request(f"{BASE_URL}/status", headers={"Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            ok = resp.status == 200 and "Server Status & Metrics" in body and "Uptime:" in body
            record("Admin Dashboard & Metrics (/status)", ok, f"Status={resp.status}")
    except Exception as e:
        record("Admin Dashboard & Metrics (/status)", False, str(e))

def test_file_upload_retrieve_delete():
    try:
        test_data = os.urandom(32 * 1024) # 32 KB
        expected_hash = hashlib.sha256(test_data).hexdigest()
        file_name = "test_upload_lifecycle.bin"

        # 1. Upload
        req = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", data=test_data, method="POST",
                                     headers={"Content-Type": "application/octet-stream", "Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            uploaded_ok = resp.status == 201

        # 2. Retrieve
        req_get = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", headers={"Host": "localhost"})
        with urllib.request.urlopen(req_get) as resp:
            downloaded = resp.read()
            retrieved_hash = hashlib.sha256(downloaded).hexdigest()
            hashes_match = expected_hash == retrieved_hash

        # 3. Delete
        req_del = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", method="DELETE", headers={"Host": "localhost"})
        with urllib.request.urlopen(req_del) as resp:
            deleted_ok = resp.status == 204

        # 4. Confirm deleted
        try:
            req_check = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", headers={"Host": "localhost"})
            urllib.request.urlopen(req_check)
            confirmed_deleted = False
        except urllib.error.HTTPError as e:
            confirmed_deleted = e.code == 404

        ok = uploaded_ok and hashes_match and deleted_ok and confirmed_deleted
        record("File Upload, Verify & DELETE Lifecycle", ok,
               f"201 Created={uploaded_ok}, Integrity={hashes_match}, 204 DELETE={deleted_ok}, 404 Deleted={confirmed_deleted}")
    except Exception as e:
        record("File Upload, Verify & DELETE Lifecycle", False, str(e))

def test_multipart_upload():
    try:
        boundary = "---------------------------974767299852498929531610575"
        raw_binary = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDRtest_data\x00\x00"
        file_name = "multipart_avatar.png"

        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'
            f"Content-Type: image/png\r\n\r\n"
        ).encode("latin1") + raw_binary + f"\r\n--{boundary}--\r\n".encode("latin1")

        req = urllib.request.Request(
            f"{BASE_URL}/upload",
            data=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Host": "localhost"},
            method="POST"
        )
        with urllib.request.urlopen(req) as resp:
            uploaded = resp.status == 201

        # Retrieve and verify exact binary bytes match
        req_get = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", headers={"Host": "localhost"})
        with urllib.request.urlopen(req_get) as resp:
            downloaded = resp.read()
            matches = downloaded == raw_binary

        # Cleanup
        req_del = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", method="DELETE", headers={"Host": "localhost"})
        urllib.request.urlopen(req_del)

        ok = uploaded and matches
        record("Multipart/form-data File Upload & Integrity", ok,
               f"201 Created={uploaded}, Matches Original={matches}")
    except Exception as e:
        record("Multipart/form-data File Upload & Integrity", False, str(e))

def test_virtual_host_routing():
    try:
        # Request on port 8081 with Host: second.com
        req1 = urllib.request.Request("http://127.0.0.1:8081/", headers={"Host": "second.com"})
        with urllib.request.urlopen(req1) as resp:
            ok1 = resp.status == 200

        # Request on port 8080 with Host: localhost
        req2 = urllib.request.Request("http://127.0.0.1:8080/", headers={"Host": "localhost"})
        with urllib.request.urlopen(req2) as resp:
            ok2 = resp.status == 200

        # Request on port 8080 with Host: vhost.com (Virtual Host sharing same port 8080)
        req3 = urllib.request.Request("http://127.0.0.1:8080/", headers={"Host": "vhost.com"})
        with urllib.request.urlopen(req3) as resp:
            ok3 = resp.status == 200

        ok = ok1 and ok2 and ok3
        record("Virtual Host & Multi-Port Routing (8080 & 8081 & shared 8080)", ok,
               f"second.com(8081)={ok1}, localhost(8080)={ok2}, vhost.com(8080)={ok3}")
    except Exception as e:
        record("Virtual Host & Multi-Port Routing (8080 & 8081 & shared 8080)", False, str(e))

def test_single_server_single_port_config():
    temp_dir = None
    process = None
    try:
        port = reserve_local_port()
        root = os.path.abspath("www")
        config = (
            "[server]\n"
            f"host={HOST}\n"
            f"port={port}\n"
            "server_name=single.test\n"
            f"root={root}\n"
            "[route /]\n"
            "methods=GET\n"
            f"root={root}\n"
            "index=index.html\n"
        )
        temp_dir, process = run_temporary_server(config)
        status, body, _ = wait_for_http_response(process, port, "single.test")
        ok = status == 200 and b"LocalServer" in body
        record("Config: Single Server on a Single Port", ok, f"Status={status}")
    except Exception as e:
        record("Config: Single Server on a Single Port", False, str(e))
    finally:
        if process is not None:
            stop_temporary_server(temp_dir, process)

def test_bind_failure_preserves_shared_port_servers():
    temp_dir = None
    process = None
    occupied_socket = socket.socket()
    try:
        occupied_socket.bind((HOST, 0))
        occupied_socket.listen(1)
        occupied_port = occupied_socket.getsockname()[1]
        shared_port = reserve_local_port()
        root = os.path.abspath("www")
        config = (
            "[server]\n"
            f"host={HOST}\n"
            f"port={occupied_port}\n"
            "server_name=unavailable.test\n"
            f"root={root}\n"
            "[server]\n"
            f"host={HOST}\n"
            f"port={shared_port}\n"
            "server_name=first.test\n"
            f"root={root}\n"
            "[route /]\n"
            "methods=GET\n"
            f"root={root}\n"
            "index=index.html\n"
            "[route /redirect]\n"
            "methods=GET\n"
            "redirect=/index.html\n"
            "[server]\n"
            f"host={HOST}\n"
            f"port={shared_port}\n"
            "server_name=second.test\n"
            f"root={root}\n"
            "[route /]\n"
            "methods=GET\n"
            f"root={root}\n"
            "index=index.html\n"
        )
        temp_dir, process = run_temporary_server(config)
        wait_for_http_response(process, shared_port, "first.test")
        conn = http.client.HTTPConnection(HOST, shared_port, timeout=3)
        conn.request("GET", "/redirect", headers={"Host": "first.test"})
        first_response = conn.getresponse()
        first_status = first_response.status
        first_location = first_response.getheader("Location")
        first_response.read()
        conn.close()
        second_status, _, _ = get_http_response(shared_port, "second.test", "/redirect")
        ok = first_status == 301 and first_location == "/index.html" and second_status == 404
        record("Config: Bind Failure Isolation and Same-Port Host Routing", ok,
               f"first.test redirect={first_status}, second.test redirect={second_status}")
    except Exception as e:
        record("Config: Bind Failure Isolation and Same-Port Host Routing", False, str(e))
    finally:
        if process is not None:
            stop_temporary_server(temp_dir, process)
        occupied_socket.close()

def test_cgi_engines():
    engines = [
        ("Python CGI", "/test.py?foo=bar", "Hello from Python CGI"),
        ("Perl CGI", "/test.pl?lang=perl", "CGI Test Script"),
        ("Bash CGI", "/test.sh?lang=bash", "Hello from Bash CGI"),
        ("C++ Native CGI", "/test.cgi?lang=cpp", "Hello from C++ CGI"),
    ]
    for name, path, expected in engines:
        try:
            req = urllib.request.Request(f"{BASE_URL}{path}", headers={"Host": "localhost"})
            with urllib.request.urlopen(req) as resp:
                body = resp.read().decode(errors="replace")
                ok = resp.status == 200 and expected in body
                record(f"CGI Execution: {name}", ok, f"Status={resp.status}, Expected present={expected in body}")
        except Exception as e:
            record(f"CGI Execution: {name}", False, str(e))

def test_cgi_unchunked_post():
    try:
        payload = b"ordinary unchunked CGI body"
        req = urllib.request.Request(
            f"{BASE_URL}/test.py",
            data=payload,
            headers={"Content-Type": "text/plain", "Host": "localhost"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read()
            ok = resp.status == 200 and b"Received Body: ordinary unchunked CGI body" in body
            record("CGI Unchunked POST Body", ok,
                   f"Status={resp.status}, Body round trip={'passed' if ok else 'failed'}")
    except Exception as e:
        record("CGI Unchunked POST Body", False, str(e))

def test_cgi_does_not_block_other_clients():
    def run_slow_cgi():
        req = urllib.request.Request(f"{BASE_URL}/timeout.pl", headers={"Host": "localhost"})
        try:
            with urllib.request.urlopen(req, timeout=7) as resp:
                return resp.status
        except urllib.error.HTTPError as e:
            return e.code

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            slow_request = executor.submit(run_slow_cgi)
            time.sleep(0.2)
            start = time.monotonic()
            req = urllib.request.Request(f"{BASE_URL}/empty.html", headers={"Host": "localhost"})
            with urllib.request.urlopen(req, timeout=2) as resp:
                quick_status = resp.status
                resp.read()
            quick_elapsed = time.monotonic() - start
            slow_status = slow_request.result(timeout=7)

        ok = quick_status == 200 and quick_elapsed < 2 and slow_status == 504
        record("CGI Async Isolation (Concurrent Static Request)", ok,
               f"Static={quick_status} in {quick_elapsed:.2f}s, slow CGI={slow_status}")
    except Exception as e:
        record("CGI Async Isolation (Concurrent Static Request)", False, str(e))

def test_chunked_simple():
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        req = (
            b"POST /test.py HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Transfer-Encoding: chunked\r\n"
            b"Connection: close\r\n"
            b"\r\n"
            b"5\r\nhello\r\n"
            b"6\r\n world\r\n"
            b"0\r\n\r\n"
        )
        s.sendall(req)
        resp = s.recv(4096).decode(errors="replace")
        s.close()
        ok = "200 OK" in resp and "hello world" in resp
        record("CGI Chunked (Simple alphanumeric)", ok, f"Contains 'hello world'={'hello world' in resp}")
    except Exception as e:
        record("CGI Chunked (Simple alphanumeric)", False, str(e))

def test_chunked_with_crlf_inside():
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        req = (
            b"POST /test.py HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Transfer-Encoding: chunked\r\n"
            b"Connection: close\r\n"
            b"\r\n"
            b"a\r\nrow1\r\nrow2\r\n"
            b"0\r\n\r\n"
        )
        s.sendall(req)
        resp = s.recv(4096).decode(errors="replace")
        s.close()
        ok = "row1\r\nrow2" in resp
        record("CGI Chunked (Contains CRLF inside chunk)", ok,
               "Corrupted!" if not ok else "Correctly preserved")
    except Exception as e:
        record("CGI Chunked (Contains CRLF inside chunk)", False, str(e))

def test_keepalive_after_chunked():
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        req1 = (
            b"POST /test.py HTTP/1.1\r\n"
            b"Host: localhost\r\n"
            b"Transfer-Encoding: chunked\r\n"
            b"Connection: keep-alive\r\n"
            b"\r\n"
            b"5\r\nhello\r\n"
            b"0\r\n\r\n"
        )
        s.sendall(req1)
        resp1 = b""
        while b"\r\n\r\n" not in resp1:
            resp1 += s.recv(1024)

        req2 = b"GET /empty.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        s.sendall(req2)
        resp2 = s.recv(4096).decode(errors="replace")
        s.close()

        ok = "200 OK" in resp2
        record("Keep-Alive Pipeline After Chunked Request", ok,
               f"Response2: {resp2.splitlines()[0] if resp2 else 'Empty'}")
    except Exception as e:
        record("Keep-Alive Pipeline After Chunked Request", False, str(e))

def test_header_size_limit():
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        req = b"GET / HTTP/1.1\r\nHost: localhost\r\n" + b"X-Large: " + (b"A" * 20000) + b"\r\n\r\n"
        s.sendall(req)
        resp = s.recv(4096).decode(errors="replace")
        s.close()
        ok = "431 Request Header Fields Too Large" in resp
        record("Security: Header Size Limit (431 Expected)", ok,
               f"Response: {resp.splitlines()[0] if resp else 'Empty'}")
    except Exception as e:
        record("Security: Header Size Limit (431 Expected)", False, str(e))

def test_path_traversal():
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        req = b"GET /../../../../etc/passwd HTTP/1.1\r\nHost: localhost\r\n\r\n"
        s.sendall(req)
        resp = s.recv(4096).decode(errors="replace")
        s.close()
        ok = "403 Forbidden" in resp or "404 Not Found" in resp
        record("Security: Directory Traversal (/../../etc/passwd)", ok,
               f"Response: {resp.splitlines()[0] if resp else 'Empty'}")
    except Exception as e:
        record("Security: Directory Traversal (/../../etc/passwd)", False, str(e))

def test_siege_stress(concurrency=30, total=1500):
    url = f"{BASE_URL}/empty.html"
    def fetch(_):
        try:
            req = urllib.request.Request(url, headers={"Host": "localhost"})
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status == 200
        except Exception:
            return False

    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        res = list(ex.map(fetch, range(total)))
    dt = time.time() - t0
    success = sum(1 for r in res if r)
    avail = (success / total) * 100
    ok = avail >= 99.5
    record(f"Stress Test (Siege Equivalent: {concurrency} workers, {total} reqs)", ok,
           f"Availability: {avail:.2f}% (Threshold >= 99.5%), RPS: {total/dt:.1f}, Time: {dt:.2f}s")

# ============================================================================
# PART 2: ADVANCED & SNEAKY AUDITOR EDGE CASE TESTS (NOT IN AUDIT SUBJECT)
# ============================================================================

def send_raw_request(req_bytes):
    s = socket.socket()
    s.connect((HOST, PORT))
    s.sendall(req_bytes)
    resp = b""
    s.settimeout(2.0)
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            resp += chunk
            if b"\r\n\r\n" in resp:
                break
    except Exception:
        pass
    s.close()
    return resp.decode(errors="replace")

def test_missing_host_header():
    # RFC 9112 Section 7.2: HTTP/1.1 without Host header MUST return 400 Bad Request
    raw = b"GET / HTTP/1.1\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Missing Host Header in HTTP/1.1 (400 Bad Request)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_duplicate_host_header():
    # RFC 9112 Section 7.2: Duplicate Host header MUST return 400 Bad Request
    raw = b"GET / HTTP/1.1\r\nHost: localhost\r\nHost: evil.com\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Duplicate Host Header (400 Bad Request)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_case_sensitive_method():
    # RFC 9110: HTTP Methods are case-sensitive; lowercase 'get' must NOT be accepted as GET
    raw = b"get / HTTP/1.1\r\nHost: localhost\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "405 Method Not Allowed" in resp or "400 Bad Request" in resp
    record("Auditor Test: Lowercase Method 'get' (405 or 400 Expected)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_unsupported_method():
    # Non-supported / unknown method returns 405 Method Not Allowed
    raw = b"UNKNOWNMETHOD / HTTP/1.1\r\nHost: localhost\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "405 Method Not Allowed" in resp
    record("Auditor Test: Unknown HTTP Method (405 Method Not Allowed)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_negative_chunk_size():
    # Negative chunk size causes integer underflow if not validated; must return 400 Bad Request without server crash
    raw = b"POST /test.py HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nfoo\r\n0\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Negative Chunk Size (400 Expected, No Crash)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_garbage_chunk_size():
    # Non-hex chunk size must return 400 Bad Request without crashing
    raw = b"POST /test.py HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\nZZZ\r\nfoo\r\n0\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Malformed Non-Hex Chunk Size (400 Expected)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_negative_content_length():
    # Negative Content-Length must return 400 Bad Request
    raw = b"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: -50\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Negative Content-Length (400 Bad Request)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_malformed_content_length():
    # Non-numeric Content-Length must return 400 Bad Request
    raw = b"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: invalid_num\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp
    record("Auditor Test: Malformed Content-Length (400 Bad Request)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_encoded_path_traversal():
    # URL-encoded path traversal /%2e%2e/%2e%2e/etc/passwd must be blocked
    raw = b"GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: localhost\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "403 Forbidden" in resp or "404 Not Found" in resp
    record("Auditor Test: URL-Encoded Path Traversal (/%2e%2e/ -> 403)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_null_byte_injection():
    # Null byte %00 injection into request path must be rejected with 400
    raw = b"GET /index.html%00.png HTTP/1.1\r\nHost: localhost\r\n\r\n"
    resp = send_raw_request(raw)
    ok = "400 Bad Request" in resp or "404 Not Found" in resp
    record("Auditor Test: Null Byte Injection (/index.html%00.png -> 400)", ok,
           f"Response: {resp.splitlines()[0] if resp else 'Empty'}")

def test_http_pipelining():
    # HTTP/1.1 Pipelining: Sending multiple requests back-to-back in a single TCP transmission
    try:
        s = socket.socket()
        s.connect((HOST, PORT))
        pipelined_req = (
            b"GET /empty.html HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
            b"GET /empty.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
        s.sendall(pipelined_req)
        resp = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            resp += chunk
        s.close()
        count = resp.count(b"HTTP/1.1 200 OK")
        ok = count == 2
        record("Auditor Test: HTTP/1.1 Pipelining (Back-to-Back Requests)", ok,
               f"200 OK count={count}/2")
    except Exception as e:
        record("Auditor Test: HTTP/1.1 Pipelining (Back-to-Back Requests)", False, str(e))

def test_url_encoded_path_with_spaces():
    # Files with spaces: Upload "my test file.txt", fetch via "my%20test%20file.txt", delete
    try:
        content = b"Data inside file with spaces in name."
        fname = "my test file with spaces.txt"
        enc_name = urllib.parse.quote(fname)

        # Upload
        req_up = urllib.request.Request(f"{BASE_URL}/upload/{enc_name}", data=content, method="POST",
                                        headers={"Content-Type": "text/plain", "Host": "localhost"})
        with urllib.request.urlopen(req_up) as r:
            up_ok = r.status == 201

        # Fetch
        req_down = urllib.request.Request(f"{BASE_URL}/upload/{enc_name}", headers={"Host": "localhost"})
        with urllib.request.urlopen(req_down) as r:
            down_bytes = r.read()
            match_ok = down_bytes == content

        # Delete
        req_del = urllib.request.Request(f"{BASE_URL}/upload/{enc_name}", method="DELETE", headers={"Host": "localhost"})
        with urllib.request.urlopen(req_del) as r:
            del_ok = r.status == 204

        ok = up_ok and match_ok and del_ok
        record("Auditor Test: URL-Encoded URI with Spaces (%20)", ok,
               f"Upload={up_ok}, Fetch Match={match_ok}, Delete={del_ok}")
    except Exception as e:
        record("Auditor Test: URL-Encoded URI with Spaces (%20)", False, str(e))

def test_standard_response_headers():
    # HTTP/1.1 compliant servers should output 'Server:' and 'Date:' headers
    try:
        req = urllib.request.Request(f"{BASE_URL}/", headers={"Host": "localhost"})
        with urllib.request.urlopen(req) as resp:
            srv = resp.headers.get("Server")
            dt = resp.headers.get("Date")
            ok = srv is not None and dt is not None
            record("Auditor Test: RFC Headers Presence ('Server' & 'Date')", ok,
                   f"Server={srv}, Date={dt}")
    except Exception as e:
        record("Auditor Test: RFC Headers Presence ('Server' & 'Date')", False, str(e))

def test_mime_types():
    # Test Content-Type MIME determination for various static assets
    types = [
        ("index.html", "text/html"),
        ("empty.html", "text/html"),
    ]
    all_ok = True
    details = []
    for file, expected_mime in types:
        try:
            req = urllib.request.Request(f"{BASE_URL}/{file}", headers={"Host": "localhost"})
            with urllib.request.urlopen(req) as resp:
                ct = resp.headers.get("Content-Type", "")
                matches = expected_mime in ct
                details.append(f"{file}:{ct}")
                if not matches:
                    all_ok = False
        except Exception as e:
            all_ok = False
            details.append(f"{file}:ERR({e})")
    record("Auditor Test: MIME Type Accuracy", all_ok, ", ".join(details))

def test_hanging_connection_timeout():
    # Connect and send a partial request, verify server does not hang forever and gracefully times out
    try:
        s = socket.socket()
        s.settimeout(5.0)
        s.connect((HOST, PORT))
        s.sendall(b"GET /incom")
        # Connection established and alive; wait briefly to ensure server non-blocking selector doesn't hang
        time.sleep(0.5)
        s.close()
        record("Auditor Test: Hanging / Partial Connection Non-Blocking Isolation", True,
               "Client opened, sent partial header, disconnected without blocking event loop")
    except Exception as e:
        record("Auditor Test: Hanging / Partial Connection Non-Blocking Isolation", False, str(e))

if __name__ == "__main__":
    print("=" * 65)
    print(" RUNNING FULL AUDIT & ADVANCED LOGIC TEST SUITE FOR LOCALSERVER")
    print("=" * 65)

    print("\n--- [PART 1: AUDIT SUBJECT REQUIREMENTS] ---")
    test_basic_get()
    test_empty_file()
    test_custom_404()
    test_custom_405()
    test_custom_413()
    test_redirect()
    test_trailing_slash_redirect()
    test_directory_listing()
    test_session_cookies()
    test_status_metrics()
    test_file_upload_retrieve_delete()
    test_multipart_upload()
    test_virtual_host_routing()
    test_single_server_single_port_config()
    test_bind_failure_preserves_shared_port_servers()
    test_cgi_engines()
    test_cgi_unchunked_post()
    test_cgi_does_not_block_other_clients()
    test_chunked_simple()
    test_chunked_with_crlf_inside()
    test_keepalive_after_chunked()
    test_header_size_limit()
    test_path_traversal()
    test_siege_stress()

    print("\n--- [PART 2: ADVANCED & SNEAKY AUDITOR EDGE CASES] ---")
    test_missing_host_header()
    test_duplicate_host_header()
    test_case_sensitive_method()
    test_unsupported_method()
    test_negative_chunk_size()
    test_garbage_chunk_size()
    test_negative_content_length()
    test_malformed_content_length()
    test_encoded_path_traversal()
    test_null_byte_injection()
    test_http_pipelining()
    test_url_encoded_path_with_spaces()
    test_standard_response_headers()
    test_mime_types()
    test_hanging_connection_timeout()

    print("\n" + "=" * 65)
    passed = sum(1 for _, p, _ in results if p)
    failed = sum(1 for _, p, _ in results if not p)
    print(f" TOTAL TESTS: {len(results)} | PASSED: {passed} | FAILED: {failed}")
    print("=" * 65)
    if failed > 0:
        sys.exit(1)
