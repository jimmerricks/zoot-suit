package com.mickstarify.zooforzotero.SyncSetup

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.mickstarify.zooforzotero.LibraryActivity.LibraryActivity
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.SyncSetup.viewmodels.SyncSetupViewModel
import com.mickstarify.zooforzotero.common.Constants
import com.mickstarify.zooforzotero.common.collectWhileStarted
import dagger.hilt.android.AndroidEntryPoint

/**
 * The first screen: sign in on zotero.org ([ZoteroSignInActivity]) or with an API key
 * ([ApiKeySetupActivity]). Either one only stores the login and closes; back here,
 * onResume finds it and opens the library.
 */
@AndroidEntryPoint
class SyncSetupActivity : AppCompatActivity() {
    private val viewModel: SyncSetupViewModel by viewModels()

    private lateinit var options: Map<SyncOption, MaterialCardView>

    override fun onCreate(savedInstanceState: Bundle?) {
        // no toolbar: dark status bar icons on the light page, light ones in dark mode
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_setup)
        padForSystemBars(findViewById(R.id.sync_setup_root))

        options = mapOf(
            SyncOption.ZoteroAPI to option(
                R.id.sign_in_option_zotero, "Sign in with Zotero", "Log in on zotero.org",
                R.drawable.outline_language_24
            ),
            SyncOption.ZoteroAPIManual to option(
                R.id.sign_in_option_api_key, "API key", "Paste a key you created on zotero.org",
                R.drawable.outline_key_24
            ),
        )
        for ((choice, card) in options) {
            card.setOnClickListener {
                viewModel.dispatchEvent(SyncSetupViewModel.Event.SelectSyncOption(choice))
            }
        }

        findViewById<TextView>(R.id.sync_setup_legal).apply {
            text = legalText()
            movementMethod = LinkMovementMethod.getInstance()
        }

        val proceed = findViewById<MaterialButton>(R.id.sync_setup_continue)
        proceed.setOnClickListener {
            viewModel.dispatchEvent(SyncSetupViewModel.Event.ProceedWithSetup)
        }

        collectWhileStarted(viewModel.stateFlow) { state ->
            proceed.isEnabled = state.isProceedEnabled
            for ((choice, card) in options) showOption(card, choice == state.selectedSyncOption)
        }
        collectWhileStarted(viewModel.effect) { effect ->
            when (effect) {
                SyncSetupViewModel.Effect.NavigateToZoteroApiSetup ->
                    startActivity(Intent(this, ZoteroSignInActivity::class.java))
                SyncSetupViewModel.Effect.NavigateToApiKeyEntry ->
                    startActivity(Intent(this, ApiKeySetupActivity::class.java))
                SyncSetupViewModel.Effect.NavigateToLibrary -> navigateToLibrary()
            }
        }
    }

    private fun option(id: Int, title: String, description: String, icon: Int): MaterialCardView {
        val card = findViewById<MaterialCardView>(id)
        card.findViewById<TextView>(R.id.sign_in_option_title).text = title
        card.findViewById<TextView>(R.id.sign_in_option_description).text = description
        card.findViewById<ImageView>(R.id.sign_in_option_icon).setImageResource(icon)
        card.contentDescription = "$title. $description"
        return card
    }

    /** The chosen option filled in the launcher icon's purple with a tick; the other plain. */
    private fun showOption(card: MaterialCardView, selected: Boolean) {
        val onSelected = ContextCompat.getColor(this, R.color.signInOnSelected)
        val accent = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorAccent)
        val surface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface)
        val secondary = MaterialColors.getColor(card, android.R.attr.textColorSecondary)

        card.isSelected = selected
        card.setCardBackgroundColor(if (selected) ContextCompat.getColor(this, R.color.signInSelected) else surface)
        card.strokeColor = accent
        card.strokeWidth = if (selected) resources.getDimensionPixelSize(R.dimen.sign_in_option_stroke) else 0
        card.cardElevation = resources.displayMetrics.density * if (selected) 8 else 2
        card.findViewById<TextView>(R.id.sign_in_option_title).apply {
            setTextColor(if (selected) onSelected else onSurface)
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        card.findViewById<TextView>(R.id.sign_in_option_description)
            .setTextColor(if (selected) onSelected and 0xCCFFFFFF.toInt() else secondary)
        card.findViewById<ImageView>(R.id.sign_in_option_icon)
            .setColorFilter(if (selected) onSelected else secondary)
        card.findViewById<View>(R.id.sign_in_option_icon_box).background.mutate()
            .setTint(if (selected) onSelected and 0x1AFFFFFF else accent and 0x33FFFFFF)
        card.findViewById<View>(R.id.sign_in_option_tick).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE
        card.findViewById<ImageView>(R.id.sign_in_option_tick).setColorFilter(onSelected)
    }

    /** "By using this app, you agree to our Terms of Use and Privacy Policy", both links. */
    private fun legalText(): CharSequence = SpannableStringBuilder().apply {
        append(getString(R.string.legal_agreement_prefix))
        link(getString(R.string.terms_of_use), Constants.Legal.TERMS_OF_USE_URL)
        append(getString(R.string.legal_agreement_middle))
        link(getString(R.string.privacy_policy), Constants.Legal.PRIVACY_POLICY_URL)
    }

    private fun SpannableStringBuilder.link(text: String, url: String) {
        val start = length
        append(text)
        setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = openInBrowser(url)
        }, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun openInBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun navigateToLibrary() {
        val intent = Intent(this, LibraryActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // signed in (either way, or in another window): on to the library
        val auth = AuthenticationStorageImpl(this)
        if (auth.hasCredentials()) {
            navigateToLibrary()
        }
    }
}

/** Keeps [view]'s content clear of the status and navigation bars (edge to edge, no toolbar). */
internal fun padForSystemBars(view: View) {
    val start = view.paddingTop
    val bottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(top = start + bars.top, bottom = bottom + bars.bottom)
        insets
    }
}
