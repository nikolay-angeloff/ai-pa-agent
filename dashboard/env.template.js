// Substituted by docker-entrypoint.sh (envsubst) into /usr/share/nginx/html/env.js
// at container start. Keeps API_BASE_URL/WS_BASE_URL configurable per environment
// (local/aws) without rebuilding the Angular bundle.
window.__env = {
  API_BASE_URL: '${API_BASE_URL}',
  WS_BASE_URL: '${WS_BASE_URL}'
};
