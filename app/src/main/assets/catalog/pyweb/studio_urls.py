
# --- PocketServer: static files when previewing inside Code Studio (STUDIO=1) --------------
import os as _os

if _os.environ.get("STUDIO"):
    from django.contrib.staticfiles.views import serve as _serve_static
    from django.urls import re_path as _re_path

    from django.views.debug import default_urlconf as _welcome

    urlpatterns += [_re_path(r"^static/(?P<path>.*)$", _serve_static)]
    # The extra pattern hides Django's "install worked" page; keep it at / until the project has its own.
    urlpatterns += [_re_path(r"^$", _welcome)]
