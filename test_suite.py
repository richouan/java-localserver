import http.client
import os
import socket
import sys
import time
import urllib.error
import urllib.request
import hashlib
import concurrent.futures

BASE_URL = "http://127.0.0.1:8080"
HOST = "127.0.0.1"
PORT = 8080

results = []

def record(test_name, passed, details=""):
    results.append((test_name, passed, details))
    status = " PASS " if passed else " FAIL "
    print(f"[{status}] {test_name}: {details}")

def test_basic_get():
    try:
        req = urllib.request.Request(f"{BASE_URL}/")
        with urllib.request.urlopen(req) as resp:
            content = resp.read().decode()
            ok = resp.status == 200 and "LocalServer" in content
            record("Basic GET / (200 OK)", ok, f"Status={resp.status}")
    except Exception as e:
        record("Basic GET / (200 OK)", False, str(e))

def test_empty_file():
    try:
        req = urllib.request.Request(f"{BASE_URL}/empty.html")
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            cl = resp.headers.get("Content-Length")
            ok = resp.status == 200 and len(body) == 0 and cl == "0"
            record("GET empty.html (200 OK, 0 bytes)", ok, f"Length={len(body)}, CL={cl}")
    except Exception as e:
        record("GET empty.html (200 OK, 0 bytes)", False, str(e))

def test_custom_404():
    try:
        urllib.request.urlopen(f"{BASE_URL}/nonexistent_route_404")
        record("Custom 404 Not Found", False, "Expected 404, got 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        ok = e.code == 404 and "404 Not Found" in body
        record("Custom 404 Not Found", ok, f"Status={e.code}, CustomBody={'Yes' if '404 Not Found' in body else 'No'}")
    except Exception as e:
        record("Custom 404 Not Found", False, str(e))

def test_custom_405():
    try:
        req = urllib.request.Request(f"{BASE_URL}/restricted", method="DELETE")
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
        conn.request("GET", "/redirect")
        resp = conn.getresponse()
        loc = resp.getheader("Location")
        conn.close()
        ok = resp.status == 301 and loc == "/index.html"
        record("301 Redirect (/redirect -> /index.html)", ok, f"Status={resp.status}, Location={loc}")
    except Exception as e:
        record("301 Redirect (/redirect -> /index.html)", False, str(e))

def test_directory_listing():
    try:
        os.makedirs("www/uploads", exist_ok=True)
        with open("www/uploads/listing_probe.txt", "w") as f:
            f.write("probe")
        req = urllib.request.Request(f"{BASE_URL}/listing")
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            ok = resp.status == 200 and "Index of /listing" in body and "listing_probe.txt" in body
            record("Directory Listing (autoindex)", ok, f"Status={resp.status}")
    except Exception as e:
        record("Directory Listing (autoindex)", False, str(e))

def test_session_cookies():
    try:
        conn = http.client.HTTPConnection(HOST, PORT)
        conn.request("GET", "/session")
        r1 = conn.getresponse()
        cookie = r1.getheader("Set-Cookie")
        body1 = r1.read().decode()

        if not cookie or "session_id=" not in cookie:
            record("Session & Cookies", False, "No Set-Cookie header received")
            conn.close()
            return

        sid_cookie = cookie.split(";")[0]
        conn.request("GET", "/session", headers={"Cookie": sid_cookie})
        r2 = conn.getresponse()
        body2 = r2.read().decode()
        conn.close()

        ok = "Visits: 1" in body1 and "Visits: 2" in body2
        record("Session & Cookies", ok, f"Initial={('Visits: 1' in body1)}, Subsequent={('Visits: 2' in body2)}")
    except Exception as e:
        record("Session & Cookies", False, str(e))

def test_status_metrics():
    try:
        req = urllib.request.Request(f"{BASE_URL}/status")
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
                                     headers={"Content-Type": "application/octet-stream"})
        with urllib.request.urlopen(req) as resp:
            uploaded_ok = resp.status == 201

        # 2. Retrieve
        with urllib.request.urlopen(f"{BASE_URL}/upload/{file_name}") as resp:
            downloaded = resp.read()
            retrieved_hash = hashlib.sha256(downloaded).hexdigest()
            hashes_match = expected_hash == retrieved_hash

        # 3. Delete
        req_del = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", method="DELETE")
        with urllib.request.urlopen(req_del) as resp:
            deleted_ok = resp.status == 204

        # 4. Confirm deleted
        try:
            urllib.request.urlopen(f"{BASE_URL}/upload/{file_name}")
            confirmed_deleted = False
        except urllib.error.HTTPError as e:
            confirmed_deleted = e.code == 404

        ok = uploaded_ok and hashes_match and deleted_ok and confirmed_deleted
        record("File Upload, Verify & DELETE Lifecycle", ok,
               f"201 Created={uploaded_ok}, Integrity={hashes_match}, 204 DELETE={deleted_ok}, 404 Deleted={confirmed_deleted}")
    except Exception as e:
        record("File Upload, Verify & DELETE Lifecycle", False, str(e))

def test_cgi_engines():
    engines = [
        ("Python CGI", "/test.py?foo=bar", "Hello from Python CGI"),
        ("Perl CGI", "/test.pl?lang=perl", "CGI Test Script"),
        ("Bash CGI", "/test.sh?lang=bash", "Hello from Bash CGI"),
        ("C++ Native CGI", "/test.cgi?lang=cpp", "Hello from C++ CGI"),
    ]
    for name, path, expected in engines:
        try:
            with urllib.request.urlopen(f"{BASE_URL}{path}") as resp:
                body = resp.read().decode(errors="replace")
                ok = resp.status == 200 and expected in body
                record(f"CGI Execution: {name}", ok, f"Status={resp.status}, Expected present={expected in body}")
        except Exception as e:
            record(f"CGI Execution: {name}", False, str(e))

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
        payload = b"row1\r\nrow2" # 10 bytes = 0xa
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
            with urllib.request.urlopen(url, timeout=3) as resp:
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
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST"
        )
        with urllib.request.urlopen(req) as resp:
            uploaded = resp.status == 201

        # Retrieve and verify exact binary bytes match
        with urllib.request.urlopen(f"{BASE_URL}/upload/{file_name}") as resp:
            downloaded = resp.read()
            matches = downloaded == raw_binary

        # Cleanup
        req_del = urllib.request.Request(f"{BASE_URL}/upload/{file_name}", method="DELETE")
        urllib.request.urlopen(req_del)

        ok = uploaded and matches
        record("Multipart/form-data File Upload & Integrity", ok,
               f"201 Created={uploaded}, Matches Original={matches}")
    except Exception as e:
        record("Multipart/form-data File Upload & Integrity", False, str(e))

def test_trailing_slash_redirect():
    try:
        conn = http.client.HTTPConnection(HOST, PORT)
        conn.request("GET", "/listing")
        resp = conn.getresponse()
        loc = resp.getheader("Location")
        conn.close()
        ok = resp.status == 301 and loc == "/listing/"
        record("Directory Trailing Slash 301 Redirect (/listing -> /listing/)", ok,
               f"Status={resp.status}, Location={loc}")
    except Exception as e:
        record("Directory Trailing Slash 301 Redirect (/listing -> /listing/)", False, str(e))

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

        ok = ok1 and ok2
        record("Virtual Host & Multi-Port Routing (8080 & 8081)", ok,
               f"Host:second.com (8081)={ok1}, Host:localhost (8080)={ok2}")
    except Exception as e:
        record("Virtual Host & Multi-Port Routing (8080 & 8081)", False, str(e))

if __name__ == "__main__":
    print("=" * 60)
    print(" RUNNING FULL AUDIT & LOGIC TEST SUITE FOR LOCALSERVER")
    print("=" * 60)
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
    test_cgi_engines()
    test_chunked_simple()
    test_chunked_with_crlf_inside()
    test_keepalive_after_chunked()
    test_header_size_limit()
    test_path_traversal()
    test_siege_stress()
    print("=" * 60)
    passed = sum(1 for _, p, _ in results if p)
    failed = sum(1 for _, p, _ in results if not p)
    print(f" TOTAL: {len(results)} | PASSED: {passed} | FAILED: {failed}")
    print("=" * 60)
