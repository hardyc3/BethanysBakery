# Cloudflare media cache and takedown purge

Policy
- Cache only `GET /api/v1/media/{mediaId}` (and size variants) on the customer hostname.
- Origin sends `Cache-Control: public, max-age=31536000, immutable` and an `ETag` for media.
- Bypass cache for availability (`/api/v1/menu-dates*`), orders (`/api/v1/orders*`), auth/CSRF, webhooks, admin, and actuator paths.
- On `MEDIA_DELETED`, purge `/api/v1/media/<mediaId>*`. Chosen policy: **edge purge** with a Cloudflare API token scoped to `Zone.Cache Purge`. If the token is unavailable, temporarily set edge TTL to 300 s (takedown target ≤5 min) and record the exception.

Console / API setup
1. Caching → Cache Rules → Create rule:
   - Expression: `(http.host eq "bakery.cherry-home.com" and starts_with(http.request.uri.path, "/api/v1/media/"))`
   - Setting: Eligible for cache; Edge TTL "Respect origin"; Browser TTL "Respect origin".
2. Add a higher-priority Bypass rule for:
   `(starts_with(http.request.uri.path, "/api/v1/menu-dates") or starts_with(http.request.uri.path, "/api/v1/orders") or starts_with(http.request.uri.path, "/api/v1/webhooks") or starts_with(http.request.uri.path, "/admin/") or starts_with(http.request.uri.path, "/actuator/"))`
3. Create API token with `Zone:Cache Purge:Edit` for the zone; store it only in the operator secret store.

Purge after media deletion/abuse report
```bash
export CF_ZONE_ID=<zone-id>
export CF_API_TOKEN=<cache-purge-token>
MEDIA_ID=<uuid>
# Purge the original plus all generated variants. Cloudflare's `files` purge does not
# reliably wildcard-match, so send the exact four URLs.
PURGE_JSON=$(jq -nc --arg id "$MEDIA_ID" '{
  files: [
    ("https://bakery.cherry-home.com/api/v1/media/" + $id),
    ("https://bakery.cherry-home.com/api/v1/media/" + $id + "?size=400"),
    ("https://bakery.cherry-home.com/api/v1/media/" + $id + "?size=800"),
    ("https://bakery.cherry-home.com/api/v1/media/" + $id + "?size=1600")
  ]
}')
curl -fsS -X POST "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
  -H "Authorization: Bearer $CF_API_TOKEN" -H 'Content-Type: application/json' \
  --data "$PURGE_JSON"
```
The app emits `MEDIA_DELETED` with the `storageKey`; map it to the media UUID from the audit row before purging.

Verification (cached-then-deleted media probe)
```bash
MEDIA_ID=<known-media-uuid>
URL="https://bakery.cherry-home.com/api/v1/media/$MEDIA_ID?size=800"
curl -sSI "$URL" | grep -iE '^(HTTP/|cache-control:|cf-cache-status:|etag:)'
curl -sSI "$URL" | grep -i 'cf-cache-status: HIT' || curl -sSI "$URL" | grep -i 'cf-cache-status: HIT'
# Delete the media in admin, run the purge command above, then:
curl -sS -o /dev/null -w '%{http_code}\n' "$URL"     # 404, no stale body
curl -sSI "$URL" | grep -i 'cf-cache-status: MISS' || true
```
Also confirm availability/orders responses carry no shared-cache `Cf-Cache-Status: HIT`.

Rollback
- Remove/disable the cache rule in Cloudflare; purge the affected URLs again. Do not shorten origin immutable TTL without owner approval.

Notify
- Owner + ops on-call for takedown purge; security owner for abuse reports.
