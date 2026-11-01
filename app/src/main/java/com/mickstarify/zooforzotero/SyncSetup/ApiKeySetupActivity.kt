package com.mickstarify.zooforzotero.SyncSetup

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.SyncSetup.viewmodels.ManualApiViewModel
import com.mickstarify.zooforzotero.common.collectWhileStarted
import dagger.hilt.android.AndroidEntryPoint

private const val SECURITY_SETTINGS_URL = "https://www.zotero.org/settings/security#applications"

/**
 * Sign-in with an API key the user made on zotero.org. A key Zotero accepts is stored and
 * the screen closes; [SyncSetupActivity] then opens the library.
 */
@AndroidEntryPoint
class ApiKeySetupActivity : AppCompatActivity() {
    private val viewModel: ManualApiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_key_setup)
        padForSystemBars(findViewById(R.id.api_key_root))

        val linkText = "Zotero security settings"
        findViewById<TextView>(R.id.api_key_open_settings_text).text =
            SpannableString("Open $linkText").apply {
                setSpan(UnderlineSpan(), 5, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        MaterialColors.getColor(this@ApiKeySetupActivity, androidx.appcompat.R.attr.colorAccent, 0)
                    ), 5, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        findViewById<android.view.View>(R.id.api_key_open_settings).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SECURITY_SETTINGS_URL))) }
        }

        val field = findViewById<TextInputLayout>(R.id.api_key_field)
        val input = findViewById<TextInputEditText>(R.id.api_key_input)
        val submit = findViewById<MaterialButton>(R.id.api_key_continue)
        val back = findViewById<MaterialButton>(R.id.api_key_back)

        // the field keeps its own text across rotation; the ViewModel gets every change
        input.doAfterTextChanged {
            viewModel.dispatchEvent(ManualApiViewModel.Event.ApiKeyChanged(it?.toString() ?: ""))
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.dispatchEvent(ManualApiViewModel.Event.SubmitApiKey)
                true
            } else false
        }
        submit.setOnClickListener { viewModel.dispatchEvent(ManualApiViewModel.Event.SubmitApiKey) }
        back.setOnClickListener { viewModel.dispatchEvent(ManualApiViewModel.Event.NavigateBack) }

        val spinner = IndeterminateDrawable.createCircularDrawable(
            this,
            CircularProgressIndicatorSpec(this, null).apply {
                indicatorSize = (16 * resources.displayMetrics.density).toInt()
                trackThickness = (2 * resources.displayMetrics.density).toInt()
                indicatorColors = intArrayOf(MaterialColors.getColor(submit, android.R.attr.textColorSecondary))
            }
        )

        collectWhileStarted(viewModel.stateFlow) { state ->
            input.isEnabled = !state.isValidating
            back.isEnabled = !state.isValidating
            submit.isEnabled = state.isSubmitEnabled && !state.isValidating
            submit.text = if (state.isValidating) "Checking…" else "Continue"
            submit.icon = if (state.isValidating) spinner else null
            // the helper text comes back when the error goes
            field.error = state.errorMessage
            field.isErrorEnabled = state.errorMessage != null
        }
        collectWhileStarted(viewModel.effect) { effect ->
            when (effect) {
                ManualApiViewModel.Effect.NavigateToLibrary -> finish()
                ManualApiViewModel.Effect.NavigateBack -> finish()
            }
        }
    }
}
