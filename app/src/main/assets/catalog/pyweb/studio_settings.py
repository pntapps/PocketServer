
# --- PocketServer: preview inside Code Studio ---------------------------------------------
# Run `STUDIO=1 python manage.py runserver 0.0.0.0:8000`, then open
# https://<this device's IP>:8090/absproxy/8000/ (or http://127.0.0.1:8091/absproxy/8000/ on the device).
# code-server forwards /absproxy/8000/... unchanged; mysite/studio_wsgi.py tells Django it lives
# under that prefix, so links, redirects and static files carry it. Logins also need Code
# Studio's HTTPS origin to be trusted.
import os as _os

if _os.environ.get("STUDIO"):
    import socket as _socket

    def _lan_ip():
        # No packet is sent: connecting a UDP socket only picks the outgoing interface.
        try:
            with _socket.socket(_socket.AF_INET, _socket.SOCK_DGRAM) as s:
                s.connect(("10.255.255.255", 1))
                return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"

    WSGI_APPLICATION = "mysite.studio_wsgi.application"
    # Django caches STATIC_URL before the first request sets the prefix, so spell it out.
    STATIC_URL = "/absproxy/8000/static/"
    CSRF_TRUSTED_ORIGINS = [f"https://{_lan_ip()}:8090", "https://127.0.0.1:8090", "http://127.0.0.1:8091"]
