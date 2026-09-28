#!/usr/bin/env python3
import os
import sys

print("Content-Type: text/html\r\n")
print("<html><body><h1>Hello from Python CGI</h1>")
print(f"<p>Method: {os.environ.get('REQUEST_METHOD', '')}</p>")
print(f"<p>Query: {os.environ.get('QUERY_STRING', '')}</p>")

if os.environ.get('REQUEST_METHOD') == 'POST':
    try:
        length = int(os.environ.get('CONTENT_LENGTH', 0))
        body = sys.stdin.read(length)
        print(f"<p>Received Body: {body}</p>")
    except Exception:
        pass

print("</body></html>")
