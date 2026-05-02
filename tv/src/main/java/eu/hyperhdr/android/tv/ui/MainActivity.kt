package eu.hyperhdr.android.tv.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import eu.hyperhdr.android.tv.R

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MainFragment())
                .commit()
        }
    }
}
