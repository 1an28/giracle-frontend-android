import http.server, ssl, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n=int(self.headers.get('content-length',0)); body=self.rfile.read(n)
        open('/tmp/pushtest/got.bin','wb').write(body)
        open('/tmp/pushtest/got.hdr','w').write(str(dict(self.headers)))
        self.send_response(201); self.end_headers()
    def log_message(self,*a): pass
s=http.server.HTTPServer(('127.0.0.1',8443),H)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain('/tmp/pushtest/c.pem','/tmp/pushtest/k.pem')
s.socket=ctx.wrap_socket(s.socket,server_side=True); s.serve_forever()
