# Android certificate pin rotation

Prerequisites
- Android release process can ship a pin set with two entries (current + next).
- Know which certificate is pinned: Cloudflare edge certificate for `*.cherry-home.com` (tunnel means no origin cert is pinned).
- Owner approval for the change window; keep the previous signed APK.

Procedure
1. Obtain the next public key hash (SPKI SHA-256) from staging edge:
   ```bash
   echo | openssl s_client -connect staging-bakery.cherry-home.com:443 -servername staging-bakery.cherry-home.com 2>/dev/null \
     | openssl x509 -pubkey -noout \
     | openssl pkey -pubin -outform DER 2>/dev/null \
     | openssl dgst -sha256 -binary | openssl base64
   ```
2. Ship an Android release whose pin set contains **both** old and new hashes. Verify the app installs and can reach staging and prod.
3. Wait for adoption (minimum 7 days or the owner-defined threshold). Watch crash/analytics for pin failures.
4. Flip the edge certificate / Cloudflare managed certificate for the affected hostnames. Re-run the hash command; it must match the new hash.
5. After a full adoption window, ship a release that removes the old hash. Never ship a single-pin release during rotation.

Verification
```bash
echo | openssl s_client -connect bakery.cherry-home.com:443 -servername bakery.cherry-home.com 2>/dev/null \
  | openssl x509 -noout -issuer -dates -fingerprint -sha256
curl -fsS https://bakery.cherry-home.com/api/v1/config
```
- Old app build still works while both hashes present; new app build works after the flip.
- No TLS/pin errors in support reports.

Rollback
- Revert Cloudflare to the previous certificate (or re-enable the old certificate) and keep the dual-pin build installed. Since pin sets are client-side, never remove the old hash before the rollback window closes.

Notify
- Owner + Android on-call + ops on-call before flip and after verification; support team for user-visible TLS errors.
