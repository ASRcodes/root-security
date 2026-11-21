package com.asrcodes.dontmissmom

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth

class PhoneLinkActivity : BaseActivity() {
    private val repository = AppDependencies.repository()
    private var saving = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_link)
        if (repository.currentUid == null) { logout(); return }
        val field = findViewById<TextInputEditText>(R.id.etPhoneLink)
        field.doAfterTextChanged { findViewById<TextInputLayout>(R.id.phoneInputLayout).error = null }
        findViewById<View>(R.id.btnSavePhone).setOnClickListener { savePhone() }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { savePhone(); true } else false }
        findViewById<View>(R.id.tvDifferentAccount).setOnClickListener { logout() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = logout() })
    }
    private fun savePhone() {
        if (saving) return
        val layout = findViewById<TextInputLayout>(R.id.phoneInputLayout)
        val phone = PhoneNumbers.normalize(findViewById<TextInputEditText>(R.id.etPhoneLink).text.toString())
        if (phone == null) { layout.error = getString(R.string.phone_invalid); return }
        if (!isOnline()) { layout.error = getString(R.string.connection_error); return }
        saving = true
        findViewById<MaterialButton>(R.id.btnSavePhone).apply { isEnabled = false; setText(R.string.saving) }
        repository.updatePhone(phone) { result ->
            if (isDestroyed) return@updatePhone
            saving = false
            findViewById<MaterialButton>(R.id.btnSavePhone).apply { isEnabled = true; setText(R.string.phone_save) }
            if (result.isSuccess) {
                val target = if (FirstLaunchPermissions.needsSetup(this)) LocationDisclosureActivity::class.java else MainActivity::class.java
                startActivity(Intent(this, target).putExtras(intent)); finish()
            }
            else failure(result.exceptionOrNull())
        }
    }
    private fun logout() {
        FirebaseAuth.getInstance().signOut()
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
