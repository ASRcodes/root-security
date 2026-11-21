package com.asrcodes.dontmissmom

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : BaseActivity() {
    private val auth = FirebaseAuth.getInstance()
    private lateinit var signInButton: MaterialButton
    private var localEntryChosen = false
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) { render(false); return@registerForActivityResult }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val token = account.idToken ?: throw IllegalStateException()
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                .addOnSuccessListener { if (!localEntryChosen) route() }.addOnFailureListener { render(false, R.string.sign_in_error) }
        } catch (_: Exception) { render(false, R.string.sign_in_error) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localEntryChosen = savedInstanceState?.getBoolean(STATE_LOCAL_ENTRY_CHOSEN, false) ?: false
        if (FirstLaunchPermissions.needsSetup(this)) {
            startActivity(Intent(this, LocationDisclosureActivity::class.java).putExtras(intent)
                .putExtra(FirstLaunchPermissions.RETURN_TO_LOGIN, true))
            finish()
            return
        }
        setContentView(R.layout.activity_login)
        signInButton = findViewById(R.id.cardGoogleSignIn)
        signInButton.setOnClickListener {
            if (!isOnline()) { render(false, R.string.connection_error); return@setOnClickListener }
            localEntryChosen = false
            if (auth.currentUser != null) { route(); return@setOnClickListener }
            render(true)
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()
            signIn.launch(GoogleSignIn.getClient(this, options).signInIntent)
        }
        findViewById<View>(R.id.btnLoginRetry).setOnClickListener { if (auth.currentUser != null) route() else signInButton.performClick() }
        findViewById<View>(R.id.btnOpenLocalProtection).setOnClickListener {
            localEntryChosen = true
            render(false)
            startActivity(Intent(this, org.suraksha.kavach.MainActivity::class.java).putExtra("page", "HOME"))
        }
        if (auth.currentUser != null && !localEntryChosen) route()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LOCAL_ENTRY_CHOSEN, localEntryChosen)
        super.onSaveInstanceState(outState)
    }

    private fun route() {
        localEntryChosen = false
        val user = auth.currentUser ?: return render(false)
        render(true)
        FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
            .addOnSuccessListener { profile ->
                if (isFinishing || isDestroyed || localEntryChosen) return@addOnSuccessListener
                val target = if (profile.getString("phone").isNullOrBlank()) PhoneLinkActivity::class.java else MainActivity::class.java
                startActivity(Intent(this, target).putExtras(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }.addOnFailureListener { render(false, R.string.connection_error) }
    }

    private fun render(busy: Boolean, error: Int? = null) {
        if (isDestroyed || !::signInButton.isInitialized) return
        signInButton.isEnabled = !busy
        signInButton.setText(if (busy) R.string.signing_in else R.string.google_sign_in)
        findViewById<TextView>(R.id.tvLoginError).apply { show(error != null); if (error != null) setText(error) }
        findViewById<View>(R.id.btnLoginRetry).show(error != null)
    }

    companion object {
        private const val STATE_LOCAL_ENTRY_CHOSEN = "local_entry_chosen"
    }
}
