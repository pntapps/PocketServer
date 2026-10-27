"""WSGI entry used with STUDIO=1: serves the project under Code Studio's /absproxy/8000/ prefix."""
from django.core.wsgi import get_wsgi_application

PREFIX = "/absproxy/8000"
_app = get_wsgi_application()


def application(environ, start_response):
    path = environ.get("PATH_INFO", "")
    if path.startswith(PREFIX):
        environ["SCRIPT_NAME"] = PREFIX
        environ["PATH_INFO"] = path[len(PREFIX):] or "/"
    return _app(environ, start_response)
