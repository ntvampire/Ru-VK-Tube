package com.ruvk.tube.ui.details

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.ruvk.tube.R
import com.ruvk.tube.domain.models.MediaItem

class DetailsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        @Suppress("DEPRECATION")
        val mediaItem = intent.getSerializableExtra(EXTRA_MEDIA_ITEM) as? MediaItem

        if (savedInstanceState == null && mediaItem != null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.details_frame, DetailsFragment.newInstance(mediaItem))
                .commit()
        }
    }

    companion object {
        const val EXTRA_MEDIA_ITEM = "extra_media_item"
    }
}
