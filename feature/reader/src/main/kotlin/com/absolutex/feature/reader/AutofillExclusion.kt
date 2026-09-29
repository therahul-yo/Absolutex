package com.absolutex.feature.reader

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics

/**
 * Keeps the window this is composed in out of the autofill framework, so a password manager is
 * neither offered the book-password field nor asked to save what was typed into it.
 *
 * A book's password is not an account credential: saving one per book only fills a vault with
 * entries the user never wanted. `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` on the dialog's
 * own view and its ancestors up to the window's decor view drops the whole dialog window from
 * the autofill structure (the framework checks every ancestor), which a Compose-only semantics
 * flag cannot promise across services. Call it from inside a `Dialog`'s content: [LocalView] is
 * then the dialog window's view, never the activity's, so the reader behind it is untouched.
 * Restored on leaving, in case the views outlive the composition.
 */
@Composable
internal fun ExcludeDialogFromAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val marked = generateSequence<View>(view) { it.parent as? View }
            .map { it to it.importantForAutofill }
            .toList()
        marked.forEach { (v, _) -> v.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        onDispose { marked.forEach { (v, previous) -> v.importantForAutofill = previous } }
    }
}

/**
 * Marks one field as having no autofillable data type: the Compose-side statement of the same
 * opt-out, so the field is also skipped by any service reading Compose semantics directly.
 */
internal fun Modifier.notAutofillable(): Modifier =
    semantics { contentDataType = ContentDataType.None }
