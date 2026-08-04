package com.joshuatz.nfceinkwriter.trailtag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates self-contained offline TrailTag HTML (no network required). */
object TrailTagHtmlGenerator {

    private const val BUNDLE_DIR = "trailtag"

    fun indexFile(context: Context): File = bundleDir(context).resolve("index.html")

    fun latestAdventureFile(context: Context): File? {
        val dir = bundleDir(context)
        return dir.listFiles { f -> f.name.startsWith("adventure-") && f.name.endsWith(".html") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun bundleDir(context: Context): File =
        File(context.filesDir, BUNDLE_DIR).also { it.mkdirs() }

    fun generate(context: Context, profile: TrailTagProfile, session: TrailTagSession?): File {
        val dir = bundleDir(context)
        val imagesDir = File(dir, "images").also { it.mkdirs() }

        val status = TrailTagStatusResolver.resolve(session)
        val payload = buildJsonPayload(profile, session, status)
        File(dir, "profile.json").writeText(payload.toString(2))

        copyPhotoIfPresent(profile, imagesDir, payload)

        val html = buildHtml(payload)
        val index = File(dir, "index.html")
        index.writeText(html)

        session?.takeIf { it.active }?.let { activeSession ->
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            File(dir, "adventure-$stamp.html").writeText(
                buildCompactOfflineHtml(profile, activeSession),
            )
        }

        return index
    }

    /** Minified static page for QR data URIs — no JS, no external assets. */
    fun buildCompactOfflineHtml(profile: TrailTagProfile, session: TrailTagSession?): String {
        val status = TrailTagStatusResolver.resolve(session)
        val statusClass = status.name.lowercase().replace('_', '-')
        val sb = StringBuilder(2048)
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
        sb.append("<title>TrailTag — ").append(esc(profile.personLabel())).append("</title>")
        sb.append(COMPACT_CSS)
        sb.append("</head><body><div class=\"bar\"></div>")
        sb.append("<h1>").append(esc(profile.personLabel())).append("</h1>")
        sb.append("<p class=\"sub\">Outdoor safety profile · offline</p>")
        sb.append("<span class=\"badge ").append(statusClass).append("\">")
            .append(esc(statusLabel(status))).append("</span>")

        if (status == AdventureStatus.PAST_RETURN || status == AdventureStatus.EMERGENCY) {
            sb.append("<div class=\"warn\">Possibly overdue — contact emergency numbers below.</div>")
        }

        session?.let { s ->
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            sb.append("<div class=\"card\"><h2>Adventure</h2>")
            sb.append(row("Activity", s.activityType.label))
            if (s.location.isNotBlank()) sb.append(row("Location", s.location))
            if (s.route.isNotBlank()) sb.append(row("Route", s.route))
            sb.append(row("Started", timeFmt.format(Date(s.startTimeMs))))
            sb.append(row("Return", timeFmt.format(Date(s.expectedReturnMs))))
            if (s.notes.isNotBlank()) sb.append(row("Notes", s.notes.take(120)))
            sb.append("</div>")
        }

        appendAdventurerSmsSection(sb, profile)
        appendEmergencySection(sb, profile)
        appendTrackingSection(sb, profile)
        appendMedicalSection(sb, profile)
        appendVehicleSection(sb, profile)

        sb.append("<p class=\"foot\">TrailTag · Sankara · generated offline</p></body></html>")
        return sb.toString()
    }

    private fun buildJsonPayload(
        profile: TrailTagProfile,
        session: TrailTagSession?,
        status: AdventureStatus,
    ): JSONObject {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        val contacts = JSONArray()
        profile.contacts.filter { it.isConfigured() }.forEach { c ->
            contacts.put(JSONObject().apply {
                put("name", c.name)
                put("primaryPhone", c.primaryPhone)
                put("secondaryPhone", c.secondaryPhone)
            })
        }

        val tracking = JSONArray()
        profile.trackingLinks.filter { it.url.isNotBlank() }.forEach { link ->
            val provider = TrackingProviderRegistry.find(link.providerId)
            tracking.put(JSONObject().apply {
                put("label", link.label)
                put("icon", provider?.icon ?: "🔗")
                put("url", link.url)
            })
        }

        val adventure = session?.let { s ->
            JSONObject().apply {
                put("activity", s.activityType.label)
                put("location", s.location)
                put("route", s.route)
                put("startTime", timeFmt.format(Date(s.startTimeMs)))
                put("startTimeMs", s.startTimeMs)
                put("expectedReturn", timeFmt.format(Date(s.expectedReturnMs)))
                put("expectedReturnMs", s.expectedReturnMs)
                put("notes", s.notes)
                put("active", s.active)
            }
        }

        return JSONObject().apply {
            put("profileId", profile.id)
            put("displayName", profile.personLabel())
            put("photo", profile.photoUri?.let { "images/photo.jpg" } ?: JSONObject.NULL)
            put("sharingMode", profile.sharingMode.storageKey)
            put("hostedUrl", profile.hostedToken?.let { TrailTagQr.hostedUrl(it) } ?: JSONObject.NULL)
            put("status", status.name.lowercase())
            put("statusLabel", statusLabel(status))
            put("comms", profile.comms.toJson())
            put("contacts", contacts)
            put("medical", JSONObject().apply {
                put("bloodType", profile.medical.bloodType)
                put("allergies", profile.medical.allergies)
                put("notes", profile.medical.notes)
            })
            put("vehicle", JSONObject().apply {
                put("makeModel", profile.vehicle.makeModel)
                put("color", profile.vehicle.color)
                put("licensePlate", profile.vehicle.licensePlate)
            })
            put("tracking", tracking)
            put("adventure", adventure ?: JSONObject.NULL)
            put("generatedAt", dateFmt.format(Date()))
            put("noindex", true)
            put("offline", true)
        }
    }

    private fun copyPhotoIfPresent(profile: TrailTagProfile, imagesDir: File, payload: JSONObject) {
        val uri = profile.photoUri ?: return
        try {
            val src = File(uri)
            if (src.exists() && src.isFile) {
                src.copyTo(File(imagesDir, "photo.jpg"), overwrite = true)
            }
        } catch (_: Exception) {
            payload.put("photo", JSONObject.NULL)
        }
    }

    private fun statusLabel(status: AdventureStatus): String = when (status) {
        AdventureStatus.ACTIVE -> "Active"
        AdventureStatus.PAST_RETURN -> "Past expected return"
        AdventureStatus.EMERGENCY -> "Emergency threshold exceeded"
        AdventureStatus.NEEDS_UPDATE -> "Needs update"
        AdventureStatus.NONE -> "No active adventure"
    }

    private fun buildHtml(payload: JSONObject): String {
        val embedded = payload.toString()
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<meta name="robots" content="noindex,nofollow"/>
<title>TrailTag — ${payload.optString("displayName")}</title>
<style>
:root{--green:#2e7d32;--yellow:#e65100;--red:#c62828;--bg:#fafafa;--card:#fff;--text:#1a1a1a;--muted:#666;--accent:#ce1126}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);line-height:1.5;padding:16px;max-width:480px;margin:0 auto}
.bar{height:4px;background:var(--accent);border-radius:2px;margin-bottom:20px}
.badge{display:inline-block;padding:6px 12px;border-radius:20px;font-weight:700;font-size:13px;margin-bottom:16px}
.badge.active{background:#e8f5e9;color:var(--green)}
.badge.past_return,.badge.past-return{background:#fff8e1;color:#e65100}
.badge.emergency{background:#ffebee;color:var(--red)}
.badge.needs_update,.badge.needs-update,.badge.none{background:#eceff1;color:#546e7a}
.card{background:var(--card);border-radius:12px;padding:16px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
h1{font-size:22px;margin-bottom:4px}
.subtitle{color:var(--muted);font-size:14px;margin-bottom:16px}
h2{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:var(--accent);margin-bottom:8px}
.row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #eee;font-size:15px}
.row:last-child{border-bottom:none}
.label{color:var(--muted)}
a.btn{display:block;background:var(--accent);color:#fff;text-align:center;padding:12px;border-radius:8px;text-decoration:none;font-weight:600;margin-top:8px;font-size:15px}
a.btn.secondary{background:#455a64}
a.btn.sms{background:#1565c0;margin-top:6px}
a.link{color:var(--accent);word-break:break-all}
.photo{width:72px;height:72px;border-radius:50%;object-fit:cover;margin-bottom:12px;background:#eee}
.footer{font-size:12px;color:var(--muted);text-align:center;margin-top:24px}
.warn{background:#fff3e0;border-left:4px solid #f9a825;padding:12px;border-radius:4px;margin-bottom:16px;font-size:14px}
.hint{font-size:13px;color:var(--muted);margin:8px 0}
</style>
</head>
<body>
<div class="bar"></div>
<div id="app"></div>
<script type="application/json" id="trailtag-data">$embedded</script>
<script>
(function(){
  var data=JSON.parse(document.getElementById('trailtag-data').textContent);
  var statusClass=(data.status||'none').replace(/_/g,'-');
  var html='';
  if(data.photo){html+='<img class="photo" src="'+data.photo+'" alt=""/>';}
  html+='<h1>'+esc(data.displayName)+'</h1>';
  html+='<p class="subtitle">Outdoor safety profile · works offline</p>';
  html+='<span class="badge '+statusClass+'">'+esc(data.statusLabel)+'</span>';
  if(data.status==='past_return'||data.status==='emergency'){
    html+='<div class="warn">⚠️ Possibly overdue — consider contacting emergency contacts.</div>';
  }
  if(data.adventure){
    html+='<div class="card"><h2>Current adventure</h2>';
    html+=row('Activity',data.adventure.activity);
    if(data.adventure.location)html+=row('Location',data.adventure.location);
    if(data.adventure.route)html+=row('Route',data.adventure.route);
    html+=row('Started',data.adventure.startTime);
    html+=row('Expected return',data.adventure.expectedReturn);
    html+='</div>';
  }
  var comms=data.comms||{};
  if(comms.mobilePhone){
    html+='<div class="card"><h2>Text adventurer</h2>';
    if(comms.satelliteCapable){
      html+='<p class="hint">T-Satellite / satellite SMS — message may deliver when they have signal.</p>';
    }
    html+='<a class="btn sms" href="'+sms(comms.mobilePhone,comms.checkInSmsBody)+'">SMS '+esc(data.displayName)+'</a>';
    html+='<a class="btn secondary" href="tel:'+esc(comms.mobilePhone)+'">Call adventurer</a>';
    html+='</div>';
  }
  if(comms.inReachSmsNumber){
    html+='<div class="card"><h2>Garmin inReach SOS</h2>';
    html+='<p class="hint">SMS the inReach device number — works over Iridium when online.</p>';
    html+='<a class="btn sms" href="'+sms(comms.inReachSmsNumber,comms.inReachSmsBody)+'">SMS inReach device</a>';
    html+='</div>';
  }
  if(data.tracking&&data.tracking.length){
    html+='<div class="card"><h2>Tracking (online)</h2>';
    html+='<p class="hint">Opens live map when you have cell or Wi‑Fi.</p>';
    data.tracking.forEach(function(t){
      html+='<p><a class="link" href="'+esc(t.url)+'" rel="noopener">'+esc(t.icon+' '+t.label)+'</a></p>';
    });
    html+='</div>';
  }
  if(data.contacts&&data.contacts.length){
    html+='<div class="card"><h2>Emergency contacts</h2>';
    data.contacts.forEach(function(c){
      var label=c.name||'contact';
      if(c.primaryPhone){
        html+='<a class="btn" href="tel:'+esc(c.primaryPhone)+'">Call '+esc(label)+'</a>';
        html+='<a class="btn sms" href="sms:'+esc(c.primaryPhone)+'">SMS '+esc(label)+'</a>';
      }
      if(c.secondaryPhone){
        html+='<a class="btn secondary" href="tel:'+esc(c.secondaryPhone)+'">Call secondary</a>';
        html+='<a class="btn sms" href="sms:'+esc(c.secondaryPhone)+'">SMS secondary</a>';
      }
    });
    html+='</div>';
  }
  var med=data.medical||{};
  if(med.bloodType||med.allergies||med.notes){
    html+='<div class="card"><h2>Medical</h2>';
    if(med.bloodType)html+=row('Blood type',med.bloodType);
    if(med.allergies)html+=row('Allergies',med.allergies);
    if(med.notes)html+=row('Notes',med.notes);
    html+='</div>';
  }
  var veh=data.vehicle||{};
  if(veh.makeModel||veh.color||veh.licensePlate){
    html+='<div class="card"><h2>Vehicle</h2>';
    if(veh.makeModel)html+=row('Make / model',veh.makeModel);
    if(veh.color)html+=row('Color',veh.color);
    if(veh.licensePlate)html+=row('Plate',veh.licensePlate);
    html+='</div>';
  }
  html+='<p class="footer">Generated locally · TrailTag by Sankara</p>';
  document.getElementById('app').innerHTML=html;
  function row(l,v){return '<div class="row"><span class="label">'+esc(l)+'</span><span>'+esc(v||'—')+'</span></div>';}
  function esc(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');}
  function sms(phone,body){
    var p=String(phone||'').trim();
    if(!body)return 'sms:'+p;
    return 'sms:'+p+'?body='+encodeURIComponent(body);
  }
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun appendAdventurerSmsSection(sb: StringBuilder, profile: TrailTagProfile) {
        val comms = profile.comms
        if (!comms.hasAdventurerSms() && !comms.hasInReachSms()) return
        sb.append("<div class=\"card\"><h2>Reach adventurer</h2>")
        if (comms.hasAdventurerSms()) {
            if (comms.satelliteCapable) {
                sb.append("<p class=\"hint\">T-Satellite / satellite SMS</p>")
            }
            sb.append("<a class=\"btn sms\" href=\"")
                .append(escAttr(TrailTagLinkBuilder.sms(comms.mobilePhone, comms.checkInSmsBody)))
                .append("\">SMS ").append(esc(profile.personLabel())).append("</a>")
            sb.append("<a class=\"btn sec\" href=\"")
                .append(escAttr(TrailTagLinkBuilder.tel(comms.mobilePhone)))
                .append("\">Call adventurer</a>")
        }
        if (comms.hasInReachSms()) {
            sb.append("<p class=\"hint\">Garmin inReach (Iridium SMS)</p>")
            sb.append("<a class=\"btn sms\" href=\"")
                .append(escAttr(TrailTagLinkBuilder.sms(comms.inReachSmsNumber, comms.inReachSmsBody)))
                .append("\">SMS inReach device</a>")
        }
        sb.append("</div>")
    }

    private fun appendEmergencySection(sb: StringBuilder, profile: TrailTagProfile) {
        val contacts = profile.contacts.filter { it.isConfigured() }
        if (contacts.isEmpty()) return
        sb.append("<div class=\"card\"><h2>Emergency</h2>")
        contacts.forEach { c ->
            val label = c.name.ifBlank { "contact" }
            if (c.primaryPhone.isNotBlank()) {
                sb.append("<a class=\"btn\" href=\"")
                    .append(escAttr(TrailTagLinkBuilder.tel(c.primaryPhone)))
                    .append("\">Call ").append(esc(label)).append("</a>")
                sb.append("<a class=\"btn sms\" href=\"")
                    .append(escAttr(TrailTagLinkBuilder.sms(c.primaryPhone)))
                    .append("\">SMS ").append(esc(label)).append("</a>")
            }
            if (c.secondaryPhone.isNotBlank()) {
                sb.append("<a class=\"btn sec\" href=\"")
                    .append(escAttr(TrailTagLinkBuilder.tel(c.secondaryPhone)))
                    .append("\">Call secondary</a>")
                sb.append("<a class=\"btn sms\" href=\"")
                    .append(escAttr(TrailTagLinkBuilder.sms(c.secondaryPhone)))
                    .append("\">SMS secondary</a>")
            }
        }
        sb.append("</div>")
    }

    private fun appendTrackingSection(sb: StringBuilder, profile: TrailTagProfile) {
        val links = profile.trackingLinks.filter { it.url.isNotBlank() }
        if (links.isEmpty()) return
        sb.append("<div class=\"card\"><h2>Tracking</h2><p class=\"hint\">Online when signal available</p>")
        links.forEach { link ->
            sb.append("<p><a class=\"link\" href=\"")
                .append(escAttr(link.url))
                .append("\">").append(esc(link.label)).append("</a></p>")
        }
        sb.append("</div>")
    }

    private fun appendMedicalSection(sb: StringBuilder, profile: TrailTagProfile) {
        val med = profile.medical
        if (!med.hasContent()) return
        sb.append("<div class=\"card\"><h2>Medical</h2>")
        if (med.bloodType.isNotBlank()) sb.append(row("Blood type", med.bloodType))
        if (med.allergies.isNotBlank()) sb.append(row("Allergies", med.allergies))
        if (med.notes.isNotBlank()) sb.append(row("Notes", med.notes))
        sb.append("</div>")
    }

    private fun appendVehicleSection(sb: StringBuilder, profile: TrailTagProfile) {
        val veh = profile.vehicle
        if (!veh.hasContent()) return
        sb.append("<div class=\"card\"><h2>Vehicle</h2>")
        if (veh.makeModel.isNotBlank()) sb.append(row("Make / model", veh.makeModel))
        if (veh.color.isNotBlank()) sb.append(row("Color", veh.color))
        if (veh.licensePlate.isNotBlank()) sb.append(row("Plate", veh.licensePlate))
        sb.append("</div>")
    }

    private fun row(label: String, value: String): String =
        "<div class=\"row\"><span class=\"lbl\">${esc(label)}</span><span>${esc(value)}</span></div>"

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escAttr(text: String): String = esc(text)

    private const val COMPACT_CSS = """
<style>
:root{--accent:#ce1126;--bg:#f7f7f7;--card:#fff;--muted:#555}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:var(--bg);padding:14px;max-width:480px;margin:0 auto;line-height:1.45;font-size:15px}
.bar{height:4px;background:var(--accent);margin-bottom:14px}
h1{font-size:21px}h2{font-size:11px;text-transform:uppercase;color:var(--accent);margin-bottom:8px}
.sub{color:var(--muted);font-size:13px;margin:4px 0 12px}
.badge{display:inline-block;padding:6px 10px;border-radius:16px;font-weight:700;font-size:12px;margin-bottom:12px;background:#eceff1}
.badge.active{background:#e8f5e9;color:#2e7d32}
.badge.past-return,.badge.past_return{background:#fff8e1;color:#e65100}
.badge.emergency{background:#ffebee;color:#c62828}
.card{background:var(--card);border-radius:10px;padding:12px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.07)}
.row{display:flex;justify-content:space-between;padding:5px 0;border-bottom:1px solid #eee}
.row:last-child{border:none}.lbl{color:var(--muted)}
a.btn{display:block;background:var(--accent);color:#fff;text-align:center;padding:11px;border-radius:8px;text-decoration:none;font-weight:600;margin-top:6px}
a.btn.sms{background:#1565c0}a.btn.sec{background:#455a64}
a.link{color:var(--accent);word-break:break-all}
.warn{background:#fff3e0;border-left:3px solid #f9a825;padding:10px;margin-bottom:12px;font-size:14px}
.hint{font-size:12px;color:var(--muted);margin:6px 0}
.foot{font-size:11px;color:var(--muted);text-align:center;margin-top:16px}
</style>
"""
}
