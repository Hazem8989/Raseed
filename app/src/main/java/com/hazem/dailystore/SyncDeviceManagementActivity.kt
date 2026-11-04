package com.hazem.dailystore

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hazem.dailystore.data.StoreTime
import com.hazem.dailystore.data.SyncDeviceAuthenticator
import com.hazem.dailystore.data.SyncProtocolRepository
import com.hazem.dailystore.data.SyncRelaySession
import org.json.JSONObject
import java.util.concurrent.Executors

/** Manager-only pairing, trust-root export, revocation and relay controls. */
class SyncDeviceManagementActivity : Activity() {
    private lateinit var repository: SyncProtocolRepository
    private lateinit var actorUserId: String
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("daily_shop_sync_relay", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actorUserId = intent.getStringExtra(EXTRA_ACTOR_USER_ID).orEmpty()
        if (actorUserId.isBlank()) { finish(); return }
        repository = SyncProtocolRepository(this)
        if (!runCatching { repository.localDeviceRole() }.isSuccess) {
            Toast.makeText(this, "تعذر قراءة إعدادات مزامنة الأجهزة", Toast.LENGTH_LONG).show()
            finish(); return
        }
        render()
        val savedEndpoint = preferences.getString(KEY_RELAY_WSS, null)
        if (!savedEndpoint.isNullOrBlank() && repository.localDeviceRole() != null) {
            SyncRelaySession.connect(applicationContext, savedEndpoint, repository)
        }
    }

    private fun render() {
        val role = repository.localDeviceRole() ?: "غير مهيأ"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFFF6F8F5.toInt())
        }
        root.addView(TextView(this).apply {
            text = "مزامنة الأجهزة وحماية الاقتران"
            textSize = 22f; gravity = Gravity.RIGHT; setTextColor(0xFF184B37.toInt())
        }, match())
        root.addView(TextView(this).apply {
            text = "الدور: $role\nمعرّف هذا الجهاز: ${repository.localDeviceId()}"
            textSize = 12f; gravity = Gravity.RIGHT; setPadding(0, 12, 0, 18)
            setTextColor(0xFF526057.toInt())
        }, match())
        status = TextView(this).apply {
            text = "لا يتم اعتماد أي جهاز ثانوي قبل التحقق من شهادة جذر الثقة."
            textSize = 13f; gravity = Gravity.RIGHT; setPadding(0, 8, 0, 16)
            setTextColor(0xFF526057.toInt())
        }
        root.addView(status, match())

        val roleNow = repository.localDeviceRole()
        if (roleNow == null) {
            addButton(root, "تهيئة هذا الهاتف كجهاز أساسي") {
                runTask("تهيئة الجهاز الأساسي") {
                    repository.configureAsPrimary(actorUserId, "الجهاز الأساسي")
                }
            }
            addButton(root, "إنشاء طلب اقتران لهذا الهاتف كثانوي") { createSecondaryRequest() }
        } else if (roleNow == "PRIMARY") {
            addButton(root, "نسخ جذر الثقة لتثبيته في Cloudflare") { copyTrustRoot() }
            addButton(root, "إنشاء دعوة اقتران لجهاز ثانوي") { createInvitation() }
            addButton(root, "تسجيل طلب اقتران وارد من جهاز ثانوي") { enrollSecondaryFromRequest() }
            addButton(root, "إلغاء شهادة جهاز ثانوي") { revokeSecondary() }
            addButton(root, "إعداد عنوان وسيط المزامنة والاتصال") { configureRelay() }
        } else {
            addButton(root, "إنشاء طلب اقتران لهذا الهاتف") { createSecondaryRequest() }
            addButton(root, "إكمال الاقتران ببيانات الجهاز الأساسي") { acceptEnrollmentResponse() }
            addButton(root, "إعداد عنوان وسيط المزامنة والاتصال") { configureRelay() }
        }
        addButton(root, "تحديث حالة الاتصال") {
            status.text = if (SyncRelaySession.isConnected()) "الاتصال المشفر جاهز." else "الاتصال غير جاهز؛ ستستمر إعادة المحاولة تلقائياً عند وجود إعداد صالح."
        }
        addButton(root, "قطع اتصال المزامنة") {
            SyncRelaySession.disconnect()
            preferences.edit().remove(KEY_RELAY_WSS).apply()
            status.text = "تم قطع الاتصال وإيقاف إعادة الاتصال التلقائية."
        }
        addButton(root, "عودة") { finish() }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun addButton(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title; isAllCaps = false; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6; bottomMargin = 6
        })
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun runTask(label: String, action: () -> Unit) {
        status.text = "$label..."
        worker.execute {
            try {
                action()
                main.post {
                    status.text = "$label: اكتملت العملية."
                    Toast.makeText(this, "$label: تم بنجاح", Toast.LENGTH_LONG).show()
                    render()
                }
            } catch (error: Exception) {
                main.post {
                    status.text = "$label: ${error.message ?: "فشلت العملية"}"
                    Toast.makeText(this, error.message ?: "تعذر إتمام العملية", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        showOutput(label, value)
        Toast.makeText(this, "تم نسخ البيانات إلى الحافظة", Toast.LENGTH_SHORT).show()
    }

    private fun showOutput(title: String, value: String) {
        val text = TextView(this).apply {
            this.text = value; textSize = 12f; setTextIsSelectable(true); setPadding(20, 12, 20, 12)
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("نسخ") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
                Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun prompt(title: String, hint: String, multiline: Boolean = false, action: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            if (multiline) { minLines = 4; gravity = Gravity.TOP or Gravity.RIGHT }
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("متابعة") { _, _ -> action(input.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun copyTrustRoot() = runCatching {
        copyText("STORE_ROOT_PUBLIC_KEYS", repository.primaryRelayTrustRootJson())
    }.onFailure { Toast.makeText(this, it.message ?: "تعذر استخراج جذر الثقة", Toast.LENGTH_LONG).show() }

    private fun createInvitation() {
        runTask("إنشاء دعوة اقتران") {
            val invitation = repository.createPairingInvitation(actorUserId)
            val bundle = JSONObject()
                .put("version", 1)
                .put("store_id", repository.storeId())
                .put("primary_device_id", repository.localDeviceId())
                .put("invitation_token", invitation.token)
                .put("store_time_zone_id", repository.storeTimeZoneId())
                .put("expires_at", invitation.expiresAt)
                .toString()
            main.post { copyText("طلب دعوة The Daily Shop", bundle) }
        }
    }

    private fun createSecondaryRequest() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 0) }
        val invite = EditText(this).apply { hint = "الصق حزمة دعوة الجهاز الأساسي"; minLines = 3; gravity = Gravity.TOP or Gravity.RIGHT }
        val name = EditText(this).apply { hint = "اسم هذا الجهاز"; setSingleLine(true) }
        panel.addView(invite, match()); panel.addView(name, match())
        AlertDialog.Builder(this).setTitle("إنشاء طلب اقتران ثانوي").setView(panel)
            .setPositiveButton("إنشاء الطلب") { _, _ ->
                val invitationText = invite.text.toString().trim()
                val displayName = name.text.toString().trim()
                runTask("إنشاء طلب الاقتران") {
                    val bundle = JSONObject(invitationText)
                    require(bundle.getInt("version") == 1)
                    val deviceId = repository.localDeviceId()
                    val keyPair = SyncDeviceAuthenticator.getOrCreate(this, deviceId)
                    val payload = JSONObject()
                        .put("version", 1)
                        .put("store_id", bundle.getString("store_id"))
                        .put("primary_device_id", bundle.getString("primary_device_id"))
                        .put("invitation_token", bundle.getString("invitation_token"))
                        .put("device_id", deviceId)
                        .put("display_name", displayName)
                        .put("public_key", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
                    val signature = SyncDeviceAuthenticator.signBytes(
                        keyPair.private, payload.toString().toByteArray(Charsets.UTF_8)
                    )
                    val request = JSONObject(payload.toString())
                        .put("request_signature", Base64.encodeToString(signature, Base64.NO_WRAP))
                        .toString()
                    main.post { copyText("طلب اقتران الجهاز الثانوي", request) }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun enrollSecondaryFromRequest() {
        prompt("تسجيل الجهاز الثانوي", "الصق طلب الاقتران القادم من الجهاز الثانوي", true) { raw ->
            runTask("إصدار شهادة الجهاز") {
                val request = JSONObject(raw)
                require(request.getInt("version") == 1)
                require(request.getString("store_id") == repository.storeId())
                require(request.getString("primary_device_id") == repository.localDeviceId())
                val remotePublicKey = request.getString("public_key")
                val requestPayload = JSONObject()
                    .put("version", request.getInt("version"))
                    .put("store_id", request.getString("store_id"))
                    .put("primary_device_id", request.getString("primary_device_id"))
                    .put("invitation_token", request.getString("invitation_token"))
                    .put("device_id", request.getString("device_id"))
                    .put("display_name", request.getString("display_name"))
                    .put("public_key", remotePublicKey)
                require(SyncDeviceAuthenticator.verifyBytes(
                    Base64.decode(remotePublicKey, Base64.DEFAULT),
                    requestPayload.toString().toByteArray(Charsets.UTF_8),
                    Base64.decode(request.getString("request_signature"), Base64.DEFAULT)
                )) { "طلب الاقتران لا يثبت امتلاك المفتاح الخاص للجهاز الثانوي" }
                val enrollment = repository.enrollSecondaryDevice(
                    actorUserId,
                    request.getString("invitation_token"),
                    request.getString("device_id"),
                    request.getString("display_name"),
                    remotePublicKey
                )
                val response = JSONObject()
                    .put("version", 1)
                    .put("store_id", enrollment.storeId)
                    .put("primary_device_id", enrollment.primaryDeviceId)
                    .put("device_id", enrollment.deviceId)
                    .put("credential", enrollment.credential)
                    .put("display_name", request.getString("display_name"))
                    .put("store_time_zone_id", enrollment.storeTimeZoneId)
                    .put("device_certificate_json", enrollment.deviceCertificateJson)
                    .toString()
                main.post { copyText("بيانات إكمال الاقتران", response) }
                SyncRelaySession.refreshDeviceRegistry()
            }
        }
    }

    private fun acceptEnrollmentResponse() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 0) }
        val response = EditText(this).apply { hint = "الصق بيانات الاقتران التي أرسلها الجهاز الأساسي"; minLines = 4; gravity = Gravity.TOP or Gravity.RIGHT }
        val relay = EditText(this).apply { hint = "عنوان HTTPS للوسيط، مثال https://your-worker.workers.dev"; setSingleLine(true) }
        panel.addView(response, match()); panel.addView(relay, match())
        AlertDialog.Builder(this).setTitle("إكمال اقتران هذا الجهاز").setView(panel)
            .setPositiveButton("تحقق واحفظ") { _, _ ->
                val responseText = response.text.toString().trim()
                val https = relay.text.toString().trim()
                runTask("التحقق من الشهادة وحفظ الاقتران") {
                    val bundle = JSONObject(responseText)
                    require(bundle.getInt("version") == 1)
                    repository.configureAsSecondaryFromRelay(
                        https,
                        actorUserId,
                        bundle.getString("store_id"),
                        bundle.getString("primary_device_id"),
                        bundle.getString("credential"),
                        bundle.getString("display_name"),
                        bundle.getString("device_certificate_json"),
                        bundle.optString("store_time_zone_id", StoreTime.DEFAULT_ZONE_ID)
                    )
                    val wss = https.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                    require(wss.startsWith("wss://"))
                    preferences.edit().putString(KEY_RELAY_WSS, wss).apply()
                    SyncRelaySession.connect(applicationContext, wss, repository)
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun revokeSecondary() {
        prompt("إلغاء شهادة جهاز", "أدخل معرّف الجهاز الثانوي UUID") { deviceId ->
            runTask("إلغاء شهادة الجهاز") {
                repository.revokeDevice(actorUserId, deviceId)
                SyncRelaySession.refreshDeviceRegistry()
                main.post { status.text = "أُلغيت صلاحية الجهاز محلياً؛ أُضيف بيان الإلغاء إلى قائمة الإرسال المؤجل." }
            }
        }
    }

    private fun configureRelay() {
        prompt("عنوان وسيط المزامنة", "wss://your-worker.workers.dev") { endpoint ->
            runTask("حفظ عنوان الوسيط وبدء المصادقة") {
                require(endpoint.startsWith("wss://", ignoreCase = true)) { "يجب إدخال عنوان WSS مشفر" }
                preferences.edit().putString(KEY_RELAY_WSS, endpoint).apply()
                SyncRelaySession.connect(applicationContext, endpoint, repository)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTOR_USER_ID = "actor_user_id"
        private const val KEY_RELAY_WSS = "relay_wss_url"
        fun intent(context: Context, actorUserId: String) =
            Intent(context, SyncDeviceManagementActivity::class.java)
                .putExtra(EXTRA_ACTOR_USER_ID, actorUserId)
    }
}
