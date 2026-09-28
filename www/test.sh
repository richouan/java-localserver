#!/bin/bash
echo -ne "Content-Type: text/html\r\n\r\n"
echo "<html><body><h1>Hello from Bash CGI</h1><p>Method: $REQUEST_METHOD</p><p>Query: $QUERY_STRING</p></body></html>"
