print "Content-Type: text/html\r\n";
print "X-Custom-Header: Zone01Cgi\r\n";
print "\r\n";
print "<html><body>";
print "<h1>CGI Test Script</h1>";
print "<p>Method: " . ($ENV{'REQUEST_METHOD'} // '') . "</p>";
print "<p>Query: " . ($ENV{'QUERY_STRING'} // '') . "</p>";

if (($ENV{'REQUEST_METHOD'} // '') eq 'POST') {
    my $len = $ENV{'CONTENT_LENGTH'} // 0;
    my $body = "";
    if ($len > 0) {
        read(STDIN, $body, $len);
    }
    print "<p>Received Body: " . $body . "</p>";
}

print "</body></html>\n";
