package com.ruvk.tube.ui.main

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.ruvk.tube.R

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, MainFragment())
                .commit()
        }
    }
}
