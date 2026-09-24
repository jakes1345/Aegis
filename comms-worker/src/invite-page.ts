/**
 * The page an invite link opens: https://<relay>/i#<invite>.
 *
 * The invite itself (the inviter's number and public keys, and the one-time
 * invite code) travels in the URL fragment, which browsers never send to a
 * server, so this page is the same static file for everyone and the relay
 * learns nothing from it being opened. The page reads the fragment, says who
 * sent the invite, links to the Aegis download, and hands the invite to the
 * app with an Android intent link. Copying the invite text into Aegis by
 * hand works too, for browsers that will not open the app.
 */
export function invitePage(): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Aegis invite</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #0e1116; color: #e6eaf1; font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; overflow-wrap: anywhere; }
  *, *::before, *::after { box-sizing: border-box; }
  main { max-width: 440px; margin: 0 auto; padding: 32px 16px 48px; }
  h1 { font-size: 20px; letter-spacing: .04em; margin: 0 0 4px; }
  .muted { color: #6f7a8b; font-size: 13px; }
  .card { background: #161b23; border-radius: 6px; padding: 16px; margin-top: 16px; }
  .from { font-size: 22px; font-weight: 700; color: #ff7a3d; }
  .num { font-family: ui-monospace, monospace; color: #a8b2c1; }
  ol { padding-left: 20px; margin: 8px 0 0; }
  li { margin: 10px 0; }
  a.button, button { display: block; width: 100%; box-sizing: border-box; text-align: center; margin-top: 10px; padding: 14px;
    border-radius: 4px; border: 0; font: 600 14px system-ui, sans-serif; letter-spacing: .06em; text-decoration: none; cursor: pointer; }
  .primary { background: #ff7a3d; color: #12161d; }
  .secondary { background: #1d2430; color: #e6eaf1; }
  textarea { width: 100%; box-sizing: border-box; margin-top: 8px; background: #0e1116; color: #a8b2c1; border: 1px solid #262e3a;
    border-radius: 4px; font: 11px ui-monospace, monospace; padding: 8px; height: 88px; resize: none; word-break: break-all; }
  .warn { color: #e8b33d; }
</style>
</head>
<body>
<main>
  <h1>AEGIS INVITE</h1>
  <p class="muted">End-to-end encrypted messages and calls between Aegis apps.</p>

  <div id="bad" class="card warn" hidden>This invite link is incomplete. Ask for it to be sent again, and open the whole link.</div>

  <div id="good" hidden>
    <div class="card">
      <div class="muted">You were invited by</div>
      <div class="from" id="from"></div>
      <div class="num" id="number"></div>
      <p class="muted">Joining gives you your own Aegis number on this relay, with <span id="who"></span> already in your contacts.
        The invite works once and expires seven days after it was made.</p>
    </div>

    <div class="card">
      <ol>
        <li><b>Install Aegis</b> (Android 12 or newer). Skip this if you have it.
          <a class="button secondary" href="https://github.com/jakes1345/Aegis/releases/latest" rel="noopener">DOWNLOAD AEGIS</a></li>
        <li><b>Open the invite in Aegis.</b> It fills everything in; tap REGISTER.
          <a class="button primary" id="open" href="#">OPEN IN AEGIS</a></li>
      </ol>
    </div>

    <div class="card">
      <div class="muted">If the button does nothing: copy this, then in Aegis go to COMMS → HAVE AN INVITE? → PASTE.</div>
      <textarea id="text" readonly></textarea>
      <button class="secondary" id="copy">COPY INVITE</button>
    </div>
  </div>

  <p class="muted">An Aegis number is not a phone number: it reaches only other Aegis apps on the same relay.</p>
</main>
<script>
(function () {
  var raw = "";
  try { raw = decodeURIComponent(location.hash.slice(1)); } catch (e) { raw = ""; }
  var prefix = "aegis:v1?";
  if (raw.indexOf(prefix) !== 0) { document.getElementById("bad").hidden = false; return; }
  var params = {};
  raw.slice(prefix.length).split("&").forEach(function (part) {
    var i = part.indexOf("=");
    if (i > 0) { try { params[part.slice(0, i)] = decodeURIComponent(part.slice(i + 1).replace(/\\+/g, " ")); } catch (e) {} }
  });
  if (!/^[1-9][0-9]{8}$/.test(params.n || "") || !params.i || !params.r) { document.getElementById("bad").hidden = false; return; }
  var number = params.n.slice(0, 3) + " " + params.n.slice(3, 6) + " " + params.n.slice(6);
  var name = (params.d || "").trim();
  document.getElementById("from").textContent = name || number;
  document.getElementById("number").textContent = name ? "Aegis number " + number : "Aegis number";
  document.getElementById("who").textContent = name || number;
  document.getElementById("text").value = raw;
  document.getElementById("open").href =
    "intent://invite?c=" + encodeURIComponent(raw) + "#Intent;scheme=aegis;package=com.xat.aegis;end";
  document.getElementById("copy").addEventListener("click", function () {
    var t = document.getElementById("text");
    t.select();
    var done = function () { document.getElementById("copy").textContent = "COPIED"; };
    if (navigator.clipboard) navigator.clipboard.writeText(raw).then(done, function () { document.execCommand("copy"); done(); });
    else { document.execCommand("copy"); done(); }
  });
  document.getElementById("good").hidden = false;
})();
</script>
</body>
</html>`;
}
