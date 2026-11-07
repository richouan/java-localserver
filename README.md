# LocalServer

> A high-performance, single-threaded HTTP/1.1 web server built from scratch in Java using non-blocking I/O multiplexing (`java.nio.channels.Selector`) and the Reactor pattern.

---

## What is LocalServer?

**LocalServer** is a lightweight, zero-dependency HTTP/1.1 web server built to demonstrate low-level systems programming and event-driven I/O. 

Unlike traditional servlet containers (like Tomcat or early Apache) that spawn a heavy operating system thread for every connected client, LocalServer uses a **single-threaded Reactor loop** powered by Linux `epoll` via Java NIO. A single thread effortlessly handles thousands of concurrent connections with zero thread contention, minimal memory footprint, and sub-millisecond response times.

Conforms strictly to the **42 / 01-Edu `localhost` specification** and core **RFC 7230 / RFC 7231** HTTP standards.

---

## Key Features

- **Event-Driven Non-Blocking Core**: Exactly **one** `Selector` monitors all listening ports and client channels. Channels are strictly non-blocking (`configureBlocking(false)`).
- **Zero External Dependencies**: Pure Java 11+ standard library. No frameworks, no external parsers, no third-party JARs.
- **Full HTTP/1.1 Protocol Support**:
  - `GET`, `POST`, and `DELETE` method dispatching.
  - Streaming request parser state machine handling fragmented headers and chunked transfer decoding.
  - Persistent connections (`Keep-Alive`) with request pipelining.
  - Mandatory `Host` header enforcement (RFC 7230 §5.4).
- **Virtual Hosting & Multi-Port Listening**:
  - Bind to multiple TCP ports simultaneously (e.g., `8080` and `8081`).
  - Name-based virtual hosting: share the same port across different domain names using the `Host` header.
- **Asynchronous CGI Gateway**:
  - Multi-engine execution support: Python (`.py`), Perl (`.pl`), Bash (`.sh`), and compiled C++ executables (`.cgi`).
  - Full RFC 3875 environment variable passing (`REQUEST_METHOD`, `QUERY_STRING`, `CONTENT_LENGTH`, `PATH_INFO`, etc.).
  - Non-blocking pipe I/O prevents slow scripts from blocking the server loop.
- **File Uploads & Multipart Processing**:
  - Handles raw binary streaming uploads and `multipart/form-data`.
  - Configurable upload directories and client body size limits (`client_max_body_size`).
- **Directory Indexing (Autoindex)**:
  - Generates clean, human-readable directory listings on the fly when no default index file is present.
- **Session & Cookie Tracking**:
  - Cookie issuance (`Set-Cookie`) and session state management with an in-memory, size-bounded LRU cache.
- **Live Metrics Dashboard**:
  - Real-time server telemetry at `/status` showing uptime, total requests, active sockets, and memory consumption.
- **Hardened Security**:
  - Directory traversal protection (canonical path verification prevents `../` escaping root).
  - Header flood defense (`431 Request Header Fields Too Large`).
  - Null-byte injection prevention (`%00` sanitization).

---

## Quick Start

### Prerequisites
- **Java Development Kit (JDK) 11** or newer (`javac`, `java`).
- **Make** (standard on Linux/macOS).
- **Python 3** (optional, for running the automated audit test suite).

### 1. Build
Compile all source files cleanly into the `bin/` directory:
```bash
make build
```

### 2. Run
Start the server with the default configuration ([`config.conf`](config.conf)):
```bash
make run
```
You should see:
```text
Starting LocalServer...
Loaded config from: config.conf
Listening on 127.0.0.1:8080
Listening on 127.0.0.1:8081
Port 8080 already bound; sharing socket for virtual host 'vhost.com'.
```

Now open your browser and navigate to:
- **Default Site**: [http://localhost:8080](http://localhost:8080)
- **Secondary Port**: [http://localhost:8081](http://localhost:8081)
- **Live Metrics**: [http://localhost:8080/status](http://localhost:8080/status)

### 3. Run the Test Suite
The repository includes an exhaustive 38-test automated audit suite testing standard RFC compliance, siege-equivalent stress loads, and sneaky evaluator edge cases:
```bash
make test
```

---

## Configuration

LocalServer uses a clean, Nginx-inspired configuration format:

```ini
# Server Block 1: Main Website
[server]
host=127.0.0.1
port=8080
server_name=localhost
root=www
body_limit=1048576       # 1 MB client body limit

[errors]
400=errors/400.html
404=errors/404.html
405=errors/405.html
413=errors/413.html
500=errors/500.html

[route /]
methods=GET,POST,DELETE
root=www
index=index.html

[route /upload]
methods=GET,POST,DELETE
root=www/uploads
upload_dir=www/uploads

[route /listing]
methods=GET
root=www/uploads
listing=true             # Enable autoindex directory listing

[route /redirect]
methods=GET
redirect=/index.html     # HTTP 301 Moved Permanently

# Server Block 2: Virtual Host on a Different Port
[server]
host=127.0.0.1
port=8081
server_name=second.com
root=www
```

---

## Architecture Overview

The codebase is organized into single-responsibility, modular classes:

```
src/
├── Main.java              # CLI entry point; loads configuration and ignites server
├── Server.java            # Single-threaded Reactor event loop (epoll Selector multiplexer)
├── Client.java            # Per-connection HTTP/1.1 parser state machine & buffer manager
├── RequestHandler.java    # HTTP request dispatcher (routes, static files, error pages)
├── CgiHandler.java        # Multi-engine asynchronous CGI process execution gateway
├── UploadHandler.java     # Binary & multipart/form-data upload file writer
├── SessionManager.java    # Cookie session management with 5,000-entry bounded cache
├── StatusHandler.java     # Real-time /status telemetry HTML generator
├── VHostResolver.java     # Port and Host header matching engine
├── Router.java            # Longest-prefix route matching
├── MimeTypes.java         # Content-Type registry & autoindex HTML builder
└── Config.java            # INI configuration loader and validator
```

### The Reactor Loop (`Server.java`)
```java
while (this.isRunning) {
    int ready = this.selector.select(1000); // Sleep at 0% CPU until socket activity
    checkTimeouts();                        // Sweep idle connections (> 30s)

    if (ready == 0) continue;

    Set<SelectionKey> keys = this.selector.selectedKeys();
    Iterator<SelectionKey> it = keys.iterator();

    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove(); // Mandatory: clear ready key to avoid stale re-processing

        if (!key.isValid()) continue;

        try {
            if (key.isAcceptable()) {
                handleAccept(key); // Accept incoming TCP handshake
            } else if (key.isReadable()) {
                handleRead(key);   // Read incoming bytes (strictly 1 read per cycle)
            } else if (key.isWritable()) {
                handleWrite(key);  // Stream response bytes (strictly 1 write per cycle)
            }
        } catch (Throwable t) {
            closeClient(key);      // Crash guard: faulty clients never crash the server
        }
    }
}
```

---

## Performance & Benchmarks

Benchmarked locally using concurrent client workers simulating high-density load:

| Metric | Result |
| :--- | :--- |
| **Throughput (RPS)** | **6,500 – 7,400 requests / second** |
| **Availability** | **100.00%** (0 dropped connections under siege) |
| **Idle CPU Usage** | **0.00%** (parked in Linux kernel `epoll_wait`) |
| **Idle Memory (Heap)** | **< 25 MB** |
| **Concurrency Model** | **1 Thread, Non-blocking I/O Multiplexing** |

---

## Makefile Commands

| Command | Description |
| :--- | :--- |
| `make` / `make build` | Compiles Java sources from `src/` into `bin/` |
| `make run` | Runs the server with `config.conf` |
| `make test` | Executes the 38-case automated test suite |
| `make clean` | Removes compiled `.class` files in `bin/` |
| `make re` | Performs a clean rebuild (`clean` + `build`) |
| `make help` | Displays available targets |

---

## Deep-Dive Documentation
For an exhaustive, low-level theoretical breakdown covering Linux kernel `epoll` data structures, system calls, TCP handshakes, and line-by-line code walks, refer to the master **[`STUDY_BOOK.md`](STUDY_BOOK.md)**.

---
