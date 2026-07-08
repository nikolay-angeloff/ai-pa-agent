#!/bin/sh
# Renders env.template.js -> env.js with the container's actual env vars before
# nginx starts. Required because this is a static build — Angular can't read
# process.env at request time, so the browser gets its config via a plain
# global (window.__env) loaded before the app bundle. See index.html.
set -eu

envsubst '${API_BASE_URL} ${WS_BASE_URL}' \
  < /usr/share/nginx/html/env.template.js \
  > /usr/share/nginx/html/env.js

exec "$@"
