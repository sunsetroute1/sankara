package com.joshuatz.nfceinkwriter.trailtag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Generates a self-contained offline TrailTag HTML bundle (no network required). */
object TrailTagHtmlGenerator {

    private const val BUNDLE_DIR = "trailtag"

    fun indexFile(context: Context): File = bundleDir(context).resolve("index.html")

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
        return index
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
            put("localUrl", TrailTagQr.universalUrl(profile, session))
            put("status", status.name.lowercase())
            put("statusLabel", statusLabel(status))
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
:root{--green:#2e7d32;--yellow:#f9a825;--red:#c62828;--bg:#fafafa;--card:#fff;--text:#1a1a1a;--muted:#666;--accent:#ce1126}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);line-height:1.5;padding:16px;max-width:480px;margin:0 auto}
.bar{height:4px;background:var(--accent);border-radius:2px;margin-bottom:20px}
.badge{display:inline-block;padding:6px 12px;border-radius:20px;font-weight:700;font-size:13px;margin-bottom:16px}
.badge.active{background:#e8f5e9;color:var(--green)}
.badge.past_return,.badge.past-return{background:#fff8e1;color:#e65100}
.badge.emergency{background:#ffebee;color:var(--red)}
.badge.needs_update,.badge.needs-update{background:#eceff1;color:#546e7a}
.badge.none{background:#eceff1;color:#546e7a}
.card{background:var(--card);border-radius:12px;padding:16px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
h1{font-size:22px;margin-bottom:4px}
.subtitle{color:var(--muted);font-size:14px;margin-bottom:16px}
h2{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:var(--accent);margin-bottom:8px}
.row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #eee;font-size:15px}
.row:last-child{border-bottom:none}
.label{color:var(--muted)}
a.btn{display:block;background:var(--accent);color:#fff;text-align:center;padding:12px;border-radius:8px;text-decoration:none;font-weight:600;margin-top:8px}
a.link{color:var(--accent);word-break:break-all}
.photo{width:72px;height:72px;border-radius:50%;object-fit:cover;margin-bottom:12px;background:#eee}
.footer{font-size:12px;color:var(--muted);text-align:center;margin-top:24px}
.warn{background:#fff3e0;border-left:4px solid var(--yellow);padding:12px;border-radius:4px;margin-bottom:16px;font-size:14px}
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
  html+='<p class="subtitle">Outdoor safety profile</p>';
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
  if(data.tracking&&data.tracking.length){
    html+='<div class="card"><h2>Tracking</h2>';
    data.tracking.forEach(function(t){
      html+='<p><a class="link" href="'+esc(t.url)+'" target="_blank" rel="noopener">'+esc(t.icon+' '+t.label)+'</a></p>';
    });
    html+='</div>';
  }
  if(data.contacts&&data.contacts.length){
    html+='<div class="card"><h2>Emergency</h2>';
    data.contacts.forEach(function(c){
      if(c.primaryPhone)html+='<a class="btn" href="tel:'+esc(c.primaryPhone)+'">Call '+(c.name||'contact')+'</a>';
      if(c.secondaryPhone)html+='<a class="btn" style="background:#555;margin-top:8px" href="tel:'+esc(c.secondaryPhone)+'">Call secondary</a>';
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
})();
</script>
</body>
</html>
        """.trimIndent()
    }
}
